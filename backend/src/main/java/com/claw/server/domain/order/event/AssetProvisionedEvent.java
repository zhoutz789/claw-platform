package com.claw.server.domain.order.event;

/**
 * 资产生成领域事件（V38）：由 {@code UnitRegistrationService.registerUnits} 在登记生成资产后发布，
 * {@code AssetProvisionedIntegration}（资产域）在事务提交后（AFTER_COMMIT）消费，将资产推入运营闭环。
 *
 * <p>融合「产品端 + 商品端」核心事件：携带产品/商品/厂家/买家溯源与部署信息，
 * 监听器据 usageMode / stationId 决定资产去向（入池 / 绑定站点 / 留库待激活）。
 */
public class AssetProvisionedEvent {

    private final Long orderId;
    private final Long orderItemId;
    private final Long assetId;
    private final String assetType;       // VEHICLE / DRONE / BATTERY / ...
    private final Long productId;         // 产品端：产品模板/品类溯源
    private final Long skuId;             // 商品端：SKU 溯源
    private final Long manufacturerId;    // 厂家
    private final Long ownerId;           // 买家（产权人）
    private final String usageMode;       // SELF / SHARED（决定监听器是否入池）
    private final Long stationId;         // 部署站点（可为空）
    private final String qrCode;
    private final String vin;             // 车辆
    private final String frameNo;         // 车辆(主部件)
    private final String motorNo;         // 车辆
    private final String componentNosJson;// 主要元件编号集合

    public AssetProvisionedEvent(Long orderId, Long orderItemId, Long assetId, String assetType,
                                 Long productId, Long skuId, Long manufacturerId, Long ownerId,
                                 String usageMode, Long stationId, String qrCode, String vin,
                                 String frameNo, String motorNo, String componentNosJson) {
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.assetId = assetId;
        this.assetType = assetType;
        this.productId = productId;
        this.skuId = skuId;
        this.manufacturerId = manufacturerId;
        this.ownerId = ownerId;
        this.usageMode = usageMode;
        this.stationId = stationId;
        this.qrCode = qrCode;
        this.vin = vin;
        this.frameNo = frameNo;
        this.motorNo = motorNo;
        this.componentNosJson = componentNosJson;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public Long getAssetId() {
        return assetId;
    }

    public String getAssetType() {
        return assetType;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public Long getManufacturerId() {
        return manufacturerId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getUsageMode() {
        return usageMode;
    }

    public Long getStationId() {
        return stationId;
    }

    public String getQrCode() {
        return qrCode;
    }

    public String getVin() {
        return vin;
    }

    public String getFrameNo() {
        return frameNo;
    }

    public String getMotorNo() {
        return motorNo;
    }

    public String getComponentNosJson() {
        return componentNosJson;
    }
}
