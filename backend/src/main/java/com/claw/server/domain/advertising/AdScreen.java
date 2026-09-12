package com.claw.server.domain.advertising;

import com.claw.server.common.enums.ScreenTerminalType;
import com.claw.server.common.enums.ScreenType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 广告屏（终端无关）：资产 AD_SCREEN 设备与外部手机 app 同为一条记录。
 * 对应 claw.ad_screens（V120）。
 */
@Entity
@Table(name = "ad_screens", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdScreen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_type", nullable = false)
    private ScreenTerminalType terminalType;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "asset_id")
    private Long assetId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "screen_type", nullable = false)
    private ScreenType screenType = ScreenType.SCREEN;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String geofence;

    /** CSV 能力标签。 */
    private String capabilities;

    @Column(name = "owner_id")
    private Long ownerId;

    @Builder.Default
    @Column(nullable = false)
    private String status = "ONLINE";

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
