package com.claw.server.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/** 站点域请求（station 入参）。 */
public final class StationRequests {

    private StationRequests() {
    }

    /** 投放现货（资产投放至站点成为现货）。 */
    public record StockIn(
            @NotNull Long stationId,
            @NotBlank String skuCode,
            @Min(1) Integer qty) {
    }

    /** 服务站扫码发放满电电池（S4）。 */
    public record ScanOut(
            @NotBlank String qrCode,
            String orderNo) {
    }

    /** 服务站扫码回收欠电电池（S4）。 */
    public record ScanIn(
            @NotBlank String qrCode,
            BigDecimal soc,
            String orderNo) {
    }

    /* ----------------------- 模块四 · 服务站三层解耦请求 ----------------------- */

    /** 库存层：服务站入站收货。 */
    public record StationInbound(
            @NotNull Long stationId,
            @NotBlank String skuCode,
            @Min(1) Integer qty) {
    }

    /** 库存层：服务站盘点调整（±delta）。 */
    public record StationAdjust(
            @NotNull Long stationId,
            @NotBlank String skuCode,
            @NotNull Integer deltaQty,
            String reason) {
    }

    /** 项目层：新建项目。 */
    public record StationProjectCreate(
            @NotNull Long stationId,
            @NotBlank String name,
            Long parentId,
            Integer sortNo) {
    }

    /** 项目层：更新项目（改名/改父/状态）。 */
    public record StationProjectUpdate(
            String name,
            Long parentId,
            Integer sortNo,
            String status) {
    }

    /** 项目层：占用库存（只写 alloc 表）。 */
    public record StationProjectAlloc(
            @NotNull Long stationStockId,
            @Min(1) Integer qty,
            String note) {
    }

    /** 结算层：按 (stationId, 周期) 生成草稿。 */
    public record StationSettlementGenerate(
            @NotNull Long stationId,
            Instant periodStart,
            Instant periodEnd) {
    }
}
