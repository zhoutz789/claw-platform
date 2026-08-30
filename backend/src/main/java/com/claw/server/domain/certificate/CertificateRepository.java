package com.claw.server.domain.certificate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备合格证仓储（对应 claw.device_certificates，V56）。
 *
 * <p><b>为什么显式指定 bean 名</b>：{@code domain/order} 下另有一个同名
 * {@code CertificateRepository}（订单合格证，对应 claw.certificates），
 * Spring Data 默认按接口简名生成 bean 名，两者都会是 {@code certificateRepository}，
 * 在 {@code spring.main.allow-bean-definition-overriding=false}（Spring Boot 默认）下
 * 直接抛 BeanDefinitionOverrideException、<b>整个应用上下文起不来</b>。
 * 因此这里显式命名为 {@code deviceCertificateRepository}。
 * 两个 bean 的泛型类型不同（{@code Certificate} vs {@code order.Certificate}），
 * 按类型注入仍可正常区分。
 */
@Repository("deviceCertificateRepository")
public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    Optional<Certificate> findByDeviceId(Long deviceId);

    Optional<Certificate> findByCertNo(String certNo);
}
