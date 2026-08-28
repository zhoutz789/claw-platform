package com.claw.server.domain.asset;

import com.claw.server.domain.iot.LifecycleTriggerEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 生命周期联动监听器（asset 域）：
 * 消费 iot 域发布的 {@link LifecycleTriggerEvent}，按遥测健康度推进资产状态（如 SOH 过低 → 退役），
 * 复用 {@link AssetService#autoTransition} 的状态机校验 + 审计纪律，保证与手工操作一致的闭环。
 * 事件在遥测事务提交后（AFTER_COMMIT）执行，避免 iot 域直接依赖资产域。
 * 注意：@TransactionalEventListener 方法本身不能再标 @Transactional（Spring 启动会拒绝），
 * 下游 AssetService.autoTransition 自带事务，此处无需声明。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LifecycleLinkageListener {

    private final AssetService assetService;

    @TransactionalEventListener
    public void onLifecycle(LifecycleTriggerEvent event) {
        assetService.autoTransition(event.getAssetId(), event.getTargetStatus(), event.getReason());
        log.info("生命周期联动：资产 {} → {}", event.getAssetId(), event.getTargetStatus());
    }
}
