package com.claw.server.domain.iot;

import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.DroneSafetyCause;

/**
 * 风控联动领域事件：遥测触发风控告警，由风控域监听器（RiskLinkageListener）消费。
 * 放在 iot 域以便风控/资产域反向依赖（符合 ArchUnit 边界：仅守护 ledger 仓储封闭 + common 不反向依赖域）。
 */
public class RiskTriggerEvent {

    private final Long assetId;
    private final AssetType assetType;
    private final DroneSafetyCause cause;
    private final String detail;

    public RiskTriggerEvent(Long assetId, AssetType assetType, DroneSafetyCause cause, String detail) {
        this.assetId = assetId;
        this.assetType = assetType;
        this.cause = cause;
        this.detail = detail;
    }

    public Long getAssetId() {
        return assetId;
    }

    public AssetType getAssetType() {
        return assetType;
    }

    public DroneSafetyCause getCause() {
        return cause;
    }

    public String getDetail() {
        return detail;
    }
}
