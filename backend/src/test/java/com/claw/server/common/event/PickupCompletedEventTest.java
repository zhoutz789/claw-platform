package com.claw.server.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PickupCompletedEvent} 与 {@code FulfillmentService.buildPickupPayload} 实际 payload 的对齐验证。
 *
 * <p>背景：该 record 原本声明 {@code fulfillmentOrderItemId}，而真实 payload 写的是
 * {@code deviceId} —— 字段名对不上，反序列化出来永远是 0，属于「没被使用所以没被发现」的死代码。
 * V86 已按真实 payload 对齐；本测试锁定这一契约，防止再次跑偏。
 */
class PickupCompletedEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("真实 payload（deviceId 而非 fulfillmentOrderItemId）可完整反序列化")
    void matchesRealPayload() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "orderId", 1001L,
                "stationId", 7L,
                "customerUserId", 55L,
                "manufacturerId", 9L,
                "assetId", 33L,
                "deviceId", 88L));

        PickupCompletedEvent event = objectMapper.readValue(payload, PickupCompletedEvent.class);

        assertEquals(1001L, event.orderId());
        assertEquals(7L, event.stationId());
        assertEquals(55L, event.customerUserId());
        assertEquals(9L, event.manufacturerId());
        assertEquals(33L, event.assetId());
        assertEquals(88L, event.deviceId(), "payload 里是 deviceId，record 必须同名才能反序列化到");
    }

    @Test
    @DisplayName("payload 里的 0L 伪值归一为 null（缺资产与资产 id=0 可区分）")
    void zeroPseudoValueIsNormalizedToNull() throws Exception {
        // FulfillmentService.buildPickupPayload 在 assetId/deviceId 缺失时填 0L（历史逻辑）
        String payload = objectMapper.writeValueAsString(Map.of(
                "orderId", 1002L,
                "assetId", 0L,
                "deviceId", 0L));

        PickupCompletedEvent event = objectMapper.readValue(payload, PickupCompletedEvent.class);

        assertEquals(1002L, event.orderId());
        assertNull(event.assetId(), "0L 应归一为 null，否则「缺资产」与「资产 id=0」不可区分");
        assertNull(event.deviceId());
    }
}
