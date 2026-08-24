package com.claw.server.domain.user;

import com.claw.server.common.enums.KycStatus;
import com.claw.server.common.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 用户（对应 claw.users）。
 * KYC 状态由 {@code KycService} 维护；camdigikey_ref 在 CamDigiKey eKYC 成功后回填。
 */
@Entity
@Table(name = "users", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String phone;

    private String email;
    private String passwordHash;
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    private String camdigikeyRef;

    private Instant kycVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(nullable = false)
    @Builder.Default
    private String locale = "en";

    @Builder.Default
    private Long tenantId = 1L;

    /** 所属部门（数据范围 DEPARTMENT/TYPE enforcement 维度）。 */
    private Long departmentId;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
