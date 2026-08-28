package com.claw.server.domain.order;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.OrderDtos.CertificateView;
import com.claw.server.common.enums.CertificateType;
import com.claw.server.common.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 合格证出证服务：支付后（PAID 及以后）出具/查询 QUALIFICATION 合格证，
 * 绑定 order_id + asset_id，幂等（已出具则直接返回），并把订单置 CERTIFICATED。
 *
 * <p>cert_no 内联生成：CERT-{Q|C}-{yyyyMM}-{6 位大写随机}，保证唯一（生成时查重）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCertificateService {

    private final CertificateRepository certificateRepository;
    private final CustomerOrderRepository orderRepository;

    /** 支付后出具/查询合格证（幂等）。 */
    @Transactional
    public CertificateView issueOrGet(Long orderId) {
        CustomerOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> BizException.notFound("error.order.not.found"));
        if (order.getStatus() != OrderStatus.PAID
                && order.getStatus() != OrderStatus.CERTIFICATED
                && order.getStatus() != OrderStatus.SHIPPED
                && order.getStatus() != OrderStatus.COMPLETED) {
            throw BizException.of(40971, "error.order.certificate.status");
        }

        return certificateRepository.findByOrderIdAndCertTypeAndDeletedFalse(orderId, CertificateType.QUALIFICATION)
                .map(this::toView)
                .orElseGet(() -> {
                    Certificate cert = Certificate.builder()
                            .certType(CertificateType.QUALIFICATION)
                            .certNo(generateCertNo(CertificateType.QUALIFICATION))
                            .assetId(order.getAssetId())
                            .orderId(order.getId())
                            .dataJson(buildDataJson(order))
                            .templateVersion("v1")
                            .issuedBy("CLAW")
                            .issuedAt(Instant.now())
                            .tenantId(1L)
                            .deleted(false)
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    cert = certificateRepository.save(cert);

                    order.setCertificateId(cert.getId());
                    order.setStatus(OrderStatus.CERTIFICATED);
                    order.setUpdatedAt(Instant.now());
                    orderRepository.save(order);
                    log.info("出具合格证 orderId={} certNo={}", orderId, cert.getCertNo());
                    return toView(cert);
                });
    }

    private String generateCertNo(CertificateType type) {
        String prefix = type == CertificateType.QUALIFICATION ? "Q" : "C";
        String ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String rand;
        int attempts = 0;
        do {
            rand = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            attempts++;
        } while (certificateRepository.findByCertNo("CERT-" + prefix + "-" + ym + "-" + rand).isPresent()
                && attempts < 10);
        return "CERT-" + prefix + "-" + ym + "-" + rand;
    }

    private String buildDataJson(CustomerOrder order) {
        return "{\"buyerUserId\":" + order.getBuyerUserId()
                + ",\"assetId\":" + order.getAssetId()
                + ",\"orderNo\":\"" + order.getOrderNo() + "\"}";
    }

    private CertificateView toView(Certificate c) {
        return new CertificateView(c.getId(),
                c.getCertType() == null ? null : c.getCertType().name(),
                c.getCertNo(), c.getOrderId(), c.getAssetId(),
                c.getDataJson(), c.getTemplateVersion(), c.getIssuedBy(), c.getIssuedAt());
    }
}
