package com.claw.server.domain.compliance;

import com.claw.server.common.enums.NfzLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 禁飞图层（对应 claw.nfz_layers，V143 新增）。
 *
 * <p>配置驱动的空间规则：{@code polygonJson} 为 [[lng,lat], ...] 闭合环（不重复首点，
 * 环由 {@link NfzService} 隐式闭合）；{@code timeWindowJson} 为
 * {@code {"tz":"UTC","daily":[{"start":"HH:mm","end":"HH:mm"}]}}，时间一律 UTC。
 *
 * <p><b>source 是语义关键位</b>：{@code BAKED-IN} = 航空安全固有约束，代码里恒定拒绝
 * （不看 {@code enabled}、不受档位影响）；{@code REGULATION-*} = 前期运营限制，
 * 可随政策放开由运营侧 {@code enabled=false} 收起。
 */
@Entity
@Table(name = "nfz_layers", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NfzLayer {

    /** 固有安全约束来源标记：命中该 source 的图层恒定拒绝，不可被接口关闭。 */
    public static final String SOURCE_BAKED_IN = "BAKED-IN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 图层名（与 source 组成幂等键）。 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 覆盖省域（可空：跨省图层）。 */
    @Column(name = "province", length = 64)
    private String province;

    /** 图层级别（ABSOLUTE / OPERATION / TIME_WINDOW）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    @Builder.Default
    private NfzLevel level = NfzLevel.ABSOLUTE;

    /** 时段窗口（JSONB，仅 TIME_WINDOW 级别使用）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "time_window_json", columnDefinition = "jsonb")
    private String timeWindowJson;

    /** 多边形闭合环（JSONB，[[lng,lat], ...]，不重复首点）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "polygon_json", columnDefinition = "jsonb")
    private String polygonJson;

    /** 图层来源（BAKED-IN / REGULATION-2025 / ...）。 */
    @Column(name = "source", length = 32)
    private String source;

    /** 是否启用（BAKED-IN 图层即使 false 也被代码恒定拒绝）。 */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
