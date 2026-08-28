package com.claw.server.domain.order.event;

import java.util.List;

/**
 * 订单支付完成领域事件：由 {@code CustomerOrderService.pay} 发布，
 * {@code OrderSharedPoolIntegration} 在事务提交后（AFTER_COMMIT）消费，决定是否入池。
 *
 * <p>事件携带订单当前快照（含 usageMode / stationId），监听器据此（重新）评估入池资格。
 * V38 起携带 {@code assetIds}（本次支付涉及的资产列表，用于多资产订单逐台入池）；
 * 为空时监听器回退到 {@code assetId}（兼容旧单资产直购订单）。
 */
public class OrderPaidEvent {

    private final Long orderId;
    private final Long assetId;
    private final Long buyerUserId;
    private final Long stationId;
    private final String usageMode;
    private final List<Long> assetIds;

    public OrderPaidEvent(Long orderId, Long assetId, Long buyerUserId, Long stationId,
                          String usageMode, List<Long> assetIds) {
        this.orderId = orderId;
        this.assetId = assetId;
        this.buyerUserId = buyerUserId;
        this.stationId = stationId;
        this.usageMode = usageMode;
        this.assetIds = assetIds;
    }

    /** 旧 5 参构造器（兼容单资产直购：assetIds 置空，监听器回退到 assetId）。 */
    public OrderPaidEvent(Long orderId, Long assetId, Long buyerUserId, Long stationId, String usageMode) {
        this(orderId, assetId, buyerUserId, stationId, usageMode, List.of());
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getAssetId() {
        return assetId;
    }

    public Long getBuyerUserId() {
        return buyerUserId;
    }

    public Long getStationId() {
        return stationId;
    }

    public String getUsageMode() {
        return usageMode;
    }

    public List<Long> getAssetIds() {
        return assetIds;
    }
}
