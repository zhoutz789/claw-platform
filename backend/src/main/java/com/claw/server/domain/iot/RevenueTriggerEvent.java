package com.claw.server.domain.iot;

/**
 * 收益联动领域事件：遥测触发收益分账重算意图，由资金域监听器（后续接入）消费。
 * payload 携带用量快照 JSON，便于分账规则按实际用量计费。
 */
public class RevenueTriggerEvent {

    private final Long assetId;
    private final String payload;

    public RevenueTriggerEvent(Long assetId, String payload) {
        this.assetId = assetId;
        this.payload = payload;
    }

    public Long getAssetId() {
        return assetId;
    }

    public String getPayload() {
        return payload;
    }
}
