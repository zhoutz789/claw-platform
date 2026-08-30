package com.claw.server.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 身份证号加密存储（AES-256-GCM）+ 列表脱敏展示。
 *
 * <p>入驻申请单的 {@code id_card_no} 属个人敏感信息，必须加密落库；列表与详情页默认只展示脱敏值
 * （保留前 4 位与后 2 位，中间以 * 遮蔽）。
 *
 * <p>密文格式：{@code Base64(12字节 IV | 16字节 GCM Tag | 密文)}，与列类型 VARCHAR(255) 兼容。
 * 密钥由 {@code claw.security.id-card-key} 提供，经 SHA-256 摘要后取 32 字节作为 AES-256 密钥；
 * 未配置时回退到一个固定开发密钥并打 WARN 日志（生产必须通过环境变量注入）。
 */
@Slf4j
@Component
public class IdCardCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String DEV_FALLBACK_KEY = "claw-dev-only-id-card-key-please-override";

    private final SecretKey secretKey;

    public IdCardCipher(@Value("${claw.security.id-card-key:}") String configuredKey) {
        String material = (configuredKey == null || configuredKey.isBlank()) ? DEV_FALLBACK_KEY : configuredKey;
        if (material.equals(DEV_FALLBACK_KEY)) {
            log.warn("未配置 claw.security.id-card-key，身份证加密回退到开发默认密钥 —— 生产环境必须通过环境变量注入");
        }
        this.secretKey = new SecretKeySpec(sha256(material.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    /**
     * 加密明文。空值原样返回（null-safe，避免把空串加密成密文后破坏非空判断）。
     *
     * @param plain 明文身份证号
     * @return Base64 密文；输入为空时返回 null
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.error("身份证号加密失败", e);
            throw new IllegalStateException("id.card.encrypt.failed", e);
        }
    }

    /**
     * 解密密文。密文为空或格式非法时返回 null（历史明文数据可原样展示，不阻断查询）。
     *
     * @param cipherTextBase64 Base64 密文
     * @return 明文；输入为空或解密失败时返回 null
     */
    public String decrypt(String cipherTextBase64) {
        if (cipherTextBase64 == null || cipherTextBase64.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherTextBase64);
            if (all.length <= IV_LENGTH_BYTES) {
                return null;
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] cipherText = new byte[all.length - IV_LENGTH_BYTES];
            System.arraycopy(all, IV_LENGTH_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 历史明文 / 密钥变更导致的不可解密，一律降级为 null，不阻断列表查询
            log.debug("身份证号解密失败（历史明文或密钥变更），按空处理");
            return null;
        }
    }

    /**
     * 脱敏展示：保留前 4 位与后 2 位，中间以 * 遮蔽；长度过短时全部遮蔽。
     *
     * @param cipherTextBase64 Base64 密文
     * @return 脱敏文本（如 {@code 1234**********89}）；空值返回占位符 {@code "—"}
     */
    public String mask(String cipherTextBase64) {
        String plain = decrypt(cipherTextBase64);
        if (plain == null || plain.isBlank()) {
            return "—";
        }
        int len = plain.length();
        if (len <= 6) {
            return "*".repeat(len);
        }
        int maskLen = Math.min(len - 6, 12);
        return plain.substring(0, 4) + "*".repeat(maskLen) + plain.substring(len - 2);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 生成一个新的随机密钥（Base64），供运维初始化 claw.security.id-card-key 使用。 */
    public static String generateKeyBase64() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            SecretKey key = gen.generateKey();
            return Base64.getEncoder().encodeToString(key.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }
}
