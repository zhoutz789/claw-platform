package com.claw.server.domain.compliance;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 金融消费者投诉（对应 claw.financial_consumer_complaints，V8 表）。
 * 渠道：平台 PLATFORM | 金融消费者中心 CONSUMER_CENTER | NBC 热线 NBC_HOTLINE。
 * 状态：RECEIVED 受理 | PROCESSING 处理中 | RESOLVED 已解决。
 */
@Entity
@Table(name = "financial_consumer_complaints", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String complaintNo;

    @Column(nullable = false)
    private Long userId;

    /** PLATFORM | CONSUMER_CENTER | NBC_HOTLINE。 */
    @Column(nullable = false, length = 32)
    private String channel;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "RECEIVED";

    private String resolution;

    private Long resolvedBy;

    private Instant resolvedAt;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
