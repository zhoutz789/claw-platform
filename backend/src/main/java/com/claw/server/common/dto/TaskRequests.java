package com.claw.server.common.dto;

import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** 任务大厅请求（task 入参）。 */
public final class TaskRequests {

    private TaskRequests() {
    }

    /** 发布任务。P0 仅 LOGISTICS 完整闭环；其余 taskType 亦接受并落库。 */
    public record Publish(
            @NotNull TaskType taskType,
            @NotBlank String title,
            String description,
            @NotNull @Positive BigDecimal rewardAmount,
            String currency,
            @NotNull AssetCapability capabilityRequired,
            BigDecimal geoLat,
            BigDecimal geoLng,
            Integer serviceRadiusM,
            String pickupAddr,
            String dropoffAddr,
            String cargoType,
            BigDecimal weightKg,
            String originAddr,
            String destAddr,
            String rideType,
            BigDecimal estDistanceKm,
            Integer estDurationMin,
            String fareModel,
            String advertiser,
            String mediaUrl,
            String displayDuration,
            String screenType) {
    }

    /** 进度上报。 */
    public record Progress(
            Integer progressPct,
            String note) {
    }

    /** 接单（绑定资产）。 */
    public record Accept(
            @NotNull Long assetId) {
    }
}
