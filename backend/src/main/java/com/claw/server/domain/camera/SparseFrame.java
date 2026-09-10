package com.claw.server.domain.camera;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 稀疏时间轴帧（永久留存，低成本长程回溯）。
 * 边缘按 retention_policy.sparse_interval_sec 抽帧，供前端时间轴缩略图。
 */
@Entity
@Table(name = "sparse_frame", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SparseFrame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long streamId;
    private Instant ts;
    @Column(columnDefinition = "text")
    private String thumbKey;      // 缩略图对象存储键
}
