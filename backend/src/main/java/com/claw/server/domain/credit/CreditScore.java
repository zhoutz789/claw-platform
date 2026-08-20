package com.claw.server.domain.credit;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Claw Score 信用分（对应 claw.credit_scores，V8 表）。
 * 分数 0-1000；因子权重：换电频次 30% | 准时付费 30% | 里程出车 20% | 好评率 10% | 收入稳定 10%。
 * 高分权益：降首付 / 提额 / 解锁周租月租。
 */
@Entity
@Table(name = "credit_scores", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private Integer score = 600;

    /** 评分因子明细（JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private String factors = "{}";

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
