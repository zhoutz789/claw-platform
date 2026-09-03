package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 服务站下项目（模块四 · 项目层）。
 *
 * <p>与既有 {@code projects}（资产项目域）正交解耦，语义是"挂在服务站下的库存项目树"，
 * 自引用 {@code parentId} 形成树，{@code depth}/{@code sortNo} 用于树形展示与重排。
 */
@Entity
@Table(name = "station_projects", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stationId;

    @Column(nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    private String name;

    /** 自引用：父项目（根=NULL）。 */
    private Long parentId;

    @Column(nullable = false)
    @Builder.Default
    private Integer depth = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortNo = 0;

    /** ACTIVE / ARCHIVED。 */
    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
