package com.claw.server.domain.iot;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 下行指令签名工具（对齐选型书 4.3）。
 *
 * <p>规则：{@code sign = HMAC_SHA256(json_without_sign_field, secret)}。
 * 平台下发前对"不含 sign 字段的报文 JSON"做 HMAC；设备收到后剔除 sign 字段、用同一规则
 * 复算并比对，从而校验指令来源与完整性（防伪造）。{@link #nonce()} 用于防重放（时间戳窗口 ±5min）。
 */
public final class MqttSigner {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private MqttSigner() {
    }

    public static String hmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 签名失败", e);
        }
    }

    public static String nonce() {
        try {
            String seed = java.util.UUID.randomUUID().toString() + System.nanoTime();
            return toHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("nonce 生成失败", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
