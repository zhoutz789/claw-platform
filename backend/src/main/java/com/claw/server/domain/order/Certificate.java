package com.claw.server.domain.order;

import com.claw.server.common.enums.CertificateType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 合格证（对应 V34 claw.certificates，自建表）。
 * 按 asset + order 出具；本期出证口径为 QUALIFICATION（资质合格证）。
 * product_link_id 保持可空（不依赖 product-link 增量）。
 */
@Entity
@Table(name = "certificates", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CertificateType certType;

    @Column(nullable = false, unique = true, length = 40)
    private String certNo;

    private Long productLinkId;

    private Long assetId;

    @Column(columnDefinition = "text")
    private String dataJson;

    @Column(length = 20)
    private String templateVersion;

    @Column(length = 60)
    private String issuedBy;

    private Instant issuedAt;

    @Column(length = 500)
    private String fileUrl;

    private Long orderId;

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
