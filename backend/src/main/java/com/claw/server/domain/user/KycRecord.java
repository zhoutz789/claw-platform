package com.claw.server.domain.user;

import com.claw.server.common.enums.KycMethod;
import com.claw.server.common.enums.KycStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * KYC 凭证记录（对应 claw.kyc_records）。
 * 不存储原始证件，仅存 CamDigiKey 授权引用与已授权字段清单（脱敏）。
 */
@Entity
@Table(name = "kyc_records", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private KycStatus status = KycStatus.PENDING;

    private String idType;            // NATIONAL_ID | PASSPORT | CAMDIGIKEY
    private String camdigikeyTokenRef;
    private String fieldsGranted;     // JSON：已授权字段集合
    private String consentVersion;
    private Instant verifiedAt;
    private String rejectedReason;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
