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

    /**
     * V82 · 服务站寄售入库（POST /api/v1/station/consignment/inbound）。
     *
     * <p><b>设备标识二选一</b>（V82 扫码枪改造）：
     * <ul>
     *   <li>{@code deviceId} —— 设备主键，原有入参；</li>
     *   <li>{@code deviceNo} —— 设备编号（{@code claw.devices.device_no}，扫码枪扫出来的
     *       往往是「DEV-000123」这类带前缀的编号而非纯数字主键）。</li>
     * </ul>
     * 二者<b>必须有一个</b>，另一个可空；两个都传时以 {@code deviceId} 为准。
     * 之所以不用 {@code @NotNull}：Bean Validation 无法表达「二选一」，且本 DTO 的
     * {@code @RequestBody} 未标注 {@code @Valid}，实际由
     * {@code StationConsignmentController} 手写校验（缺则 10001）。
     *
     * <p>站点 ID 由登录站长的作用域带出、厂家 ID 由 {@code inventory.owner_manufacturer_id}
     * （货权方）带出，二者均不接受前端指定 —— 从入参层面杜绝「厂家替服务站选站分拨」
     * 的越权与误操作。
     */
    public record StationConsignmentInbound(
            Long deviceId,
            String deviceNo) {
    }
}
