package com.claw.server.domain.merchant;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 商家主体（对应 V52 claw.merchants，商家入驻骨架）。
 * 完整招商审批流留 Phase 2；本轮回填骨架（状态 PENDING/ACTIVE/REJECTED 由审批流推进）。
 */
@Entity
@Table(name = "merchants", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 160)
    private String contact;

    @Column(length = 64)
    private String country;

    /** PENDING / ACTIVE / REJECTED。 */
    @Column(nullable = false, length = 24)
    @Builder.Default
    private String status = "PENDING";

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Boolean deleted = false;
}
