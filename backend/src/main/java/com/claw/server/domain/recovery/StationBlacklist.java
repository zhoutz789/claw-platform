package com.claw.server.domain.recovery;

import com.claw.server.common.enums.BlacklistType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 站点黑名单（对应 V13 claw.station_blacklist）。
 * 用户/站点违规后拉黑，禁止使用/运营。
 */
@Entity
@Table(name = "station_blacklist", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationBlacklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long stationId;
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BlacklistType blacklistType;

    @Column(nullable = false)
    private String reason;

    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Instant blacklistedAt = Instant.now();

    private Instant expiresAt;

    private Long blacklistedBy;

    @Column(nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    private Long resolvedBy;
    private Instant resolvedAt;
    private String resolutionNote;

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
