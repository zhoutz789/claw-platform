package com.claw.server.domain.operator;

import com.claw.server.common.enums.KycApprovalStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "operator_kyc_records", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorKycRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long operatorId;

    private Long stationId;

    @Column(nullable = false)
    private String kycMethod;

    private String kycTransactionId;
    private String fullName;
    private String idNumber;
    private String idType;
    private LocalDate dateOfBirth;
    private String address;
    private Boolean phoneVerified;

    private Integer clawScore;
    private String backgroundCheck;
    private Boolean criminalRecord;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private KycApprovalStatus status = KycApprovalStatus.PENDING;

    private Long approvedBy;
    private Instant approvedAt;
    private String rejectReason;

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
