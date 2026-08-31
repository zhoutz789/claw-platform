package com.claw.server.domain.airspace;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.DroneSafetyCause;
import com.claw.server.common.enums.DroneSafetyEventStatus;
import com.claw.server.common.enums.DroneSafetyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 无人机飞行安全管控服务（类比车辆断缴锁车）。
 * 资产存在任一 OPEN 安全事件即判定 LOCKED，禁止起飞；解除后恢复正常。
 */
@Service
@RequiredArgsConstructor
public class DroneSafetyService {

    private final DroneSafetyEventRepository eventRepository;

    /** 当前安全态：存在 OPEN 事件即为 LOCKED。 */
    public DroneSafetyStatus currentStatus(Long assetId) {
        boolean locked = eventRepository.existsByAssetIdAndStatus(assetId, DroneSafetyEventStatus.OPEN);
        return locked ? DroneSafetyStatus.LOCKED : DroneSafetyStatus.NORMAL;
    }

    public List<DroneSafetyEvent> listEvents(Long assetId) {
        return eventRepository.findByAssetIdOrderByCreatedAtDesc(assetId);
    }

    /** 模拟触发锁机（越界/失联/低电量/人工）。 */
    @Transactional
    public DroneSafetyEvent simulate(Long assetId, DroneSafetyCause cause, String detail) {
        DroneSafetyEvent e = DroneSafetyEvent.builder()
                .assetId(assetId)
                .cause(cause)
                .status(DroneSafetyEventStatus.OPEN)
                .detail(detail)
                .createdAt(Instant.now())
                .build();
        return eventRepository.save(e);
    }

    /**
     * 解除最早触发的一条未解除事件（FIFO）。
     *
     * <p>两处修正（N1 错误契约）：
     * <ol>
     *   <li><b>确定性</b>：改用带 {@code OrderByCreatedAtAsc} 的查询。原实现是
     *       {@code findByAssetIdAndStatus(...).stream().findFirst()}，该查询没有 ORDER BY，
     *       命中哪一条由数据库返回顺序决定 —— 同一份数据可能解除不同的事件；</li>
     *   <li><b>语义化错误码</b>：原来抛裸 {@code IllegalArgumentException}，
     *       全局异常处理器没有对应 handler，会兜成 500 + {@code "internal error"}。
     *       「当前没有可解除的事件」是<b>状态冲突</b>而非服务端故障 → 40961（HTTP 409）。
     *       前端据此弹 info 提示而不是报错。</li>
     * </ol>
     *
     * @throws BizException 40961 error.drone.safety.nothing.to.resolve（无 OPEN 事件）
     */
    @Transactional
    public DroneSafetyEvent resolve(Long assetId) {
        return eventRepository
                .findFirstByAssetIdAndStatusOrderByCreatedAtAsc(assetId, DroneSafetyEventStatus.OPEN)
                .map(e -> {
                    e.setStatus(DroneSafetyEventStatus.RESOLVED);
                    e.setResolvedAt(Instant.now());
                    return eventRepository.save(e);
                })
                .orElseThrow(() -> BizException.of(40961, "error.drone.safety.nothing.to.resolve"));
    }
}
