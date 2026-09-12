package com.claw.server.domain.autonomy;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 自学习模型（训练数据集引用/精度/是否平台共享）。
 * 对应 claw.learning_models（V121）。
 */
@Entity
@Table(name = "learning_models", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String version;

    @Column(name = "trained_on_dataset_ref")
    private String trainedOnDatasetRef;

    private BigDecimal accuracy;

    @Builder.Default
    @Column(nullable = false)
    private Boolean shared = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
