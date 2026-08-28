package com.claw.server.domain.airspace;

import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.iot.RiskTriggerEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 风控联动监听器（airspace 域）：
 * 消费 iot 域发布的 {@link RiskTriggerEvent}，对无人机资产触发锁机/告警，落实低空经济合规闭环。
 * 事件在遥测事务提交后（AFTER_COMMIT）执行，避免 iot 域直接依赖风控域。
 * 注意：@TransactionalEventListener 方法本身不能再标 @Transactional（Spring 启动会拒绝），
 * 下游 DroneSafetyService.simulate 自带事务，此处无需声明。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RiskLinkageListener {

    private final DroneSafetyService droneSafetyService;

    @TransactionalEventListener
    public void onRisk(RiskTriggerEvent event) {
        if (event.getAssetType() != AssetType.DRONE) {
            log.debug("风控联动跳过非无人机资产 {}", event.getAssetId());
            return;
        }
        droneSafetyService.simulate(event.getAssetId(), event.getCause(), event.getDetail());
        log.info("风控联动：资产 {} 触发锁机/告警 cause={}", event.getAssetId(), event.getCause());
    }
}
