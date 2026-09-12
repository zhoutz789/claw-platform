package com.claw.server.domain.adapter;

import java.math.BigDecimal;

/**
 * 归一化遥测视图（协议无关的资产级遥测快照）。
 *
 * <p>由 {@link DeviceAdapterContract#normalizeTelemetry(String)} 从设备原始报文解析得到，
 * 字段对齐 claw.telemetry（资产级）口径：定位 / 速度 / 电量 / 健康度。
 */
public record TelemetryView(
        Long assetId,
        BigDecimal lat,
        BigDecimal lng,
        BigDecimal speedKph,
        BigDecimal soc,
        BigDecimal soh,
        BigDecimal temp
) {
}
