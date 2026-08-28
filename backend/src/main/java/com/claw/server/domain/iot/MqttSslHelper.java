package com.claw.server.domain.iot;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * MQTT SSL 工具：构建 {@link javax.net.ssl.SSLSocketFactory}（纯 JDK，无需 BouncyCastle）。
 *
 * <p>用途：下行/入站网关在 broker-url 为 {@code ssl://} 时启用 TLS。
 * <ul>
 *   <li>trustStorePath：信任证书（PEM/X509，如 EMQX 的 CA），直接解析、免去 keytool 转换；</li>
 *   <li>keyStorePath：双向 TLS（mutual TLS）时的客户端证书（PKCS12，含私钥）。</li>
 * </ul>
 * 证书由 {@code scripts/gen_emqx_certs.sh} 生成，口令通过配置注入。
 */
public final class MqttSslHelper {

    private MqttSslHelper() {
    }

    public static javax.net.ssl.SSLSocketFactory build(String trustStorePath, String trustStorePass,
                                                       String keyStorePath, String keyStorePass) throws Exception {
        // 信任库：以 PEM 解析 CA 证书构建（不依赖 JKS/keytool）
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        try (InputStream in = Files.newInputStream(Paths.get(trustStorePath))) {
            Certificate ca = cf.generateCertificate(in);
            ts.setCertificateEntry("ca", ca);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(ts);

        KeyManager[] km = null;
        if (keyStorePath != null && !keyStorePath.isBlank()) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Paths.get(keyStorePath))) {
                ks.load(in, keyStorePass.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, keyStorePass.toCharArray());
            km = kmf.getKeyManagers();
        }

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(km, tmf.getTrustManagers(), new SecureRandom());
        return ctx.getSocketFactory();
    }
}
