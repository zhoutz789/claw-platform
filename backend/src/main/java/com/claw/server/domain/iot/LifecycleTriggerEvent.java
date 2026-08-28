package com.claw.server.domain.iot;

import com.claw.server.common.enums.AssetStatus;

/**
 * 生命周期联动领域事件：遥测触发全生命周期状态推进，由资产域监听器（LifecycleLinkageListener）消费。
 */
public class LifecycleTriggerEvent {

    private final Long assetId;
    private final AssetStatus targetStatus;
    private final String reason;

    public LifecycleTriggerEvent(Long assetId, AssetStatus targetStatus, String reason) {
        this.assetId = assetId;
        this.targetStatus = targetStatus;
        this.reason = reason;
    }

    public Long getAssetId() {
        return assetId;
    }

    public AssetStatus getTargetStatus() {
        return targetStatus;
    }

    public String getReason() {
        return reason;
    }
}
