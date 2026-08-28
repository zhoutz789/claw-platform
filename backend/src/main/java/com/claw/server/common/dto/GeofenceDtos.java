package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电子围栏 / 位置域出入参（common 层：不得 import 任何 domain 类）。
 * 对应 Increment 3 A 期：通用电子围栏 CRUD + 越界判定。
 * 位置/轨迹视图另见 {@link LocationDtos}。
 */
public final class GeofenceDtos {

    private GeofenceDtos() {
    }

    /** 电子围栏视图。 */
    public record GeofenceView(
            Long id,
            String ownerType,
            Long ownerId,
            String fenceType,
            BigDecimal centerLat,
            BigDecimal centerLng,
            Integer radiusM,
            String polygonWkt,
            String triggerAction,
            String status,
            Instant createdAt) {
    }

    /** 创建围栏请求。 */
    public record CreateGeofenceReq(
            String ownerType,
            Long ownerId,
            String fenceType,
            BigDecimal centerLat,
            BigDecimal centerLng,
            Integer radiusM,
            String polygonWkt,
            String triggerAction) {
    }

    /** 更新围栏请求（全字段可选，仅覆盖非空项）。 */
    public record UpdateGeofenceReq(
            String ownerType,
            Long ownerId,
            String fenceType,
            BigDecimal centerLat,
            BigDecimal centerLng,
            Integer radiusM,
            String polygonWkt,
            String triggerAction,
            String status) {
    }

    /** 围栏越界判定视图。 */
    public record GeofenceBreachView(
            Long geofenceId,
            String ownerType,
            Long ownerId,
            boolean breached,
            String reason,
            BigDecimal pointLat,
            BigDecimal pointLng,
            Double distanceMeters) {
    }
}
