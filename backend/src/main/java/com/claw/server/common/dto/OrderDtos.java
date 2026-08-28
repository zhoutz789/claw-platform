package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 客户订单域出入参（common 层：不得 import 任何 domain 类，只用基础类型/String/BigDecimal）。
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * 创建客户订单请求。
     *
     * <p>新契约（V38）：按「订单头 + 多行 {@link OrderLineReq}（sku+qty）」下单，
     * 资产在登记时逐台产生，下单不再要求 assetId。
     * 兼容旧单资产直购：仅传 assetId（lines 为空）时退化为 1 个订单项 + quantity=1。
     */
    public record CreateCustomerOrderReq(
            Long buyerUserId,
            Long assetId,
            Long productId,
            Long skuId,
            String assetType,
            Integer quantity,
            BigDecimal unitPrice,
            Long stationId,
            List<OrderLineReq> lines) {

        /** 旧 8 参构造器（兼容历史调用 / 单资产直购）。 */
        public CreateCustomerOrderReq(Long buyerUserId, Long assetId, Long productId, Long skuId,
                                      String assetType, Integer quantity, BigDecimal unitPrice, Long stationId) {
            this(buyerUserId, assetId, productId, skuId, assetType, quantity, unitPrice, stationId, null);
        }
    }

    /** 订单项行（按 sku+qty 下单）。 */
    public record OrderLineReq(
            Long skuId,
            String assetType,
            Integer quantity,
            BigDecimal unitPrice) {
    }

    /** 支付请求。 */
    public record PayReq(
            String payOrderNo,
            BigDecimal paidAmount) {
    }

    /** 选择使用模式请求。 */
    public record ChooseModeReq(
            String usageMode,
            Long stationId) {
    }

    /** 单台设备登记请求（发货前逐台录入）。 */
    public record UnitRegistrationReq(
            String qrCode,          // 逐台唯一二维码（必填）
            String vin,             // 车架号（车辆）
            String frameNo,         // 车架号(主部件)
            String motorNo,         // 电机号
            String serialNumber,    // 出厂序列号
            String componentNosJson,// 主要元件编号集合（JSON，见设计 §2.4）
            Long manufacturerId,    // 厂家（可选，缺省留空）
            Long productId,         // 商品（可选，缺省取订单 productId）
            String remoteId,        // 无人机 Remote ID
            String model,           // 型号
            BigDecimal capacityKwh // 电池容量（电池）
    ) {
    }

    /** 批量登记请求（一次登记 N 台）。 */
    public record UnitRegistrationBatchReq(
            List<UnitRegistrationReq> units) {
    }

    /** 登记行视图（含生成资产 + 进度）。 */
    public record UnitRegistrationView(
            Long id,
            Long orderId,
            Long orderItemId,
            int seq,
            String qrCode,
            String vin,
            String frameNo,
            String motorNo,
            Long assetId,
            String status,
            String componentNosJson) {
    }

    /** 单 SKU 登记进度。 */
    public record ItemRegistrationProgress(
            Long orderItemId,
            Long skuId,
            String assetType,
            int quantity,
            int registered) {
    }

    /** 订单登记总进度（前端「去登记」面板用）。 */
    public record OrderRegistrationProgressView(
            List<ItemRegistrationProgress> items,
            List<UnitRegistrationView> registrations,
            boolean canShip) {
    }

    /** 客户订单视图。 */
    public record CustomerOrderView(
            Long id,
            String orderNo,
            Long buyerUserId,
            Long productId,
            Long assetId,
            String assetType,
            String usageMode,
            String status,
            BigDecimal totalAmount,
            BigDecimal depositAmount,
            String depositNo,
            String payOrderNo,
            Long stationId,
            Long poolEntryId,
            Long splitRuleId,
            Long certificateId,
            Instant shippedAt,
            Instant completedAt,
            String cancelReason,
            String refundStatus,
            Instant createdAt) {
    }

    /** 订单项视图。 */
    public record CustomerOrderItemView(
            Long id,
            Long orderId,
            Long assetId,
            Long skuId,
            String assetType,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal) {
    }

    /** 合格证视图。 */
    public record CertificateView(
            Long id,
            String certType,
            String certNo,
            Long orderId,
            Long assetId,
            String dataJson,
            String templateVersion,
            String issuedBy,
            Instant issuedAt) {
    }
}
