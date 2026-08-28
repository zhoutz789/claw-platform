package com.claw.server.domain.asset;

import com.claw.server.common.dto.AssetRequests.BindDevice;
import com.claw.server.domain.order.event.AssetProvisionedEvent;
import com.claw.server.domain.sharedpool.SharedPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;

/**
 * 资产生成 → 运营闭环接线（V38，资产域 AFTER_COMMIT 监听器）。
 *
 * <p>消费 {@link AssetProvisionedEvent}，据 usageMode / stationId 将资产推入流转闭环：
 * <ul>
 *   <li>SHARED（且 stationId 非空）→ 经 {@link SharedPoolService#poolAsset} 入共享池；</li>
 *   <li>有 stationId（自营 / 站方部署）→ 经 {@link AssetService#bindDevice} 推 IN_USE + DEPLOY 产权链首笔；</li>
 *   <li>纯自营待用户激活 → 资产留 IN_STOCK。</li>
 * </ul>
 *
 * <p>跨域边界（ArchUnit）：仅经本域 AssetService 服务接口 + SharedPoolService 服务接口交互，
 * 不直持他域 Repository。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssetProvisionedIntegration {

    private static final BigDecimal OWNER_RATE = new BigDecimal("0.70");
    private static final BigDecimal STATION_RATE = new BigDecimal("0.15");

    private final AssetService assetService;
    private final SharedPoolService sharedPoolService;

    @TransactionalEventListener
    public void onAssetProvisioned(AssetProvisionedEvent event) {
        Long assetId = event.getAssetId();
        if (assetId == null) {
            log.warn("AssetProvisionedIntegration: 事件缺 assetId，跳过");
            return;
        }

        // 共享模式：入池（需站点）
        if ("SHARED".equals(event.getUsageMode())) {
            if (event.getStationId() == null) {
                log.warn("AssetProvisionedIntegration: SHARED 资产缺 stationId，跳过入池 assetId={}", assetId);
                return;
            }
            sharedPoolService.poolAsset(assetId, event.getOwnerId(), event.getStationId(),
                    OWNER_RATE, STATION_RATE, BigDecimal.ZERO, BigDecimal.ZERO);
            log.info("资产生成入共享池 assetId={} stationId={}", assetId, event.getStationId());
            return;
        }

        // 自营且有部署站点：上线部署（IN_STOCK → IN_USE + DEPLOY 产权链首笔）
        if (event.getStationId() != null) {
            assetService.bindDevice(new BindDevice(assetId, event.getStationId(), null, null, null),
                    event.getOwnerId());
            log.info("资产生成绑定站点 assetId={} stationId={}", assetId, event.getStationId());
            return;
        }

        // 纯自营：资产留 IN_STOCK，待用户扫码激活再 bindDevice
        log.info("资产生成留库待激活 assetId={}", assetId);
    }
}
