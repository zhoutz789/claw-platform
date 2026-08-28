package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 位置 / 轨迹域出入参（common 层：不得 import 任何 domain 类）。
 * 对应 Increment 3 A 期：产品聚合位置（派生视图）+ 历史轨迹回放。
 * 数据源复用既有 tracks / telemetry_latest，不自建冗余表。
 */
public final class LocationDtos {

    private LocationDtos() {
    }

    /** 产品聚合位置（派生自该产品下最新上报资产的 telemetry_latest）。 */
    public record ProductLocationView(
            Long assetId,
            String assetNo,
            BigDecimal lat,
            BigDecimal lng,
            Instant reportedAt) {
    }

    /** 轨迹点视图（来自 tracks）。 */
    public record TrackPointView(
            Instant ts,
            BigDecimal lat,
            BigDecimal lng,
            BigDecimal speed,
            BigDecimal soc) {
    }
}
