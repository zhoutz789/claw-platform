package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 无人机 ↔ 电池 绑定服务（电池供需视图基础，V140）。
 *
 * <p>镜像 {@link VehicleBatteryBindingService}，差异在于无人机<b>可并行</b>使用多块电池
 * （热插拔换电 / 机场充电），因此：
 * <ul>
 *   <li>「当前绑定」仍以「同一无人机至多一条 active 记录」保证（闭合旧 + 开新）；</li>
 *   <li>额外提供 {@link #supplyDemandView()} 供需视图，供调度侧判断电池池是否吃紧。</li>
 * </ul>
 *
 * <p>绑定前置校验无人机/电池资产存在：外键目标为 {@code drones(asset_id)} /
 * {@code batteries(asset_id)}，若直接落库撞外键会抛 {@code DataIntegrityViolationException}
 * 被全局兜成 500，前端无法区分「资产不存在」与「服务挂了」。故先查后写，给出 404 语义。
 *
 * <p>「同一块电池被两台无人机同时占用」只做<b>软校验</b>（告警日志，不阻断）：换电/机场
 * 调度过程中电池可能短暂处于过渡态，硬拒会误伤正常作业，但该告警是运维侧发现重复占用的
 * 唯一信号，必须留下。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DroneBatteryService {

    private final DroneBatteryBindingRepository bindingRepository;
    private final DroneRepository droneRepository;
    private final BatteryRepository batteryRepository;

    /**
     * 绑定电池到无人机：若该无人机已有生效绑定则先关闭（active=false + unboundAt），再写入新绑定。
     *
     * @param droneAssetId   无人机 asset_id（须存在于 drones）
     * @param batteryAssetId 电池 asset_id（须存在于 batteries）
     * @param cycles         该次绑定起始循环次数（可为空，空则记 0）
     * @return 新建的绑定记录（active=true）
     * @throws BizException 10001 error.drone.battery.invalid（入参为空，HTTP 400）
     * @throws BizException 40466 error.drone.not.found（无人机资产不存在，HTTP 404）
     * @throws BizException 40467 error.battery.not.found（电池资产不存在，HTTP 404）
     */
    @Transactional
    public DroneBatteryBinding bind(Long droneAssetId, Long batteryAssetId, Integer cycles) {
        if (droneAssetId == null || batteryAssetId == null) {
            throw BizException.invalidParam("error.drone.battery.invalid");
        }
        if (droneRepository.findByAssetId(droneAssetId).isEmpty()) {
            throw BizException.of(40466, "error.drone.not.found", droneAssetId);
        }
        if (batteryRepository.findByAssetId(batteryAssetId).isEmpty()) {
            throw BizException.of(40467, "error.battery.not.found", batteryAssetId);
        }

        Instant now = Instant.now();
        bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(droneAssetId)
                .ifPresent(prev -> {
                    prev.setActive(false);
                    prev.setUnboundAt(now);
                    bindingRepository.save(prev);
                    log.info("无人机 {} 解绑旧电池 {}", droneAssetId, prev.getBatteryAssetId());
                });

        softCheckBatteryNotShared(batteryAssetId);

        DroneBatteryBinding binding = DroneBatteryBinding.builder()
                .droneAssetId(droneAssetId)
                .batteryAssetId(batteryAssetId)
                .boundAt(now)
                .unboundAt(null)
                .cycles(cycles != null ? Math.max(0, cycles) : 0)
                .active(true)
                .build();
        DroneBatteryBinding saved = bindingRepository.save(binding);
        log.info("无人机 {} 绑定电池 {} cycles={}", droneAssetId, batteryAssetId, saved.getCycles());
        return saved;
    }

    /**
     * 解绑当前生效电池（无生效绑定时幂等无操作）。
     *
     * @param droneAssetId 无人机 asset_id
     * @return 是否确有生效绑定被关闭
     */
    @Transactional
    public boolean unbind(Long droneAssetId) {
        Optional<DroneBatteryBinding> current =
                bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(droneAssetId);
        if (current.isEmpty()) {
            log.info("无人机 {} 无生效绑定，解绑幂等跳过", droneAssetId);
            return false;
        }
        DroneBatteryBinding prev = current.get();
        prev.setActive(false);
        prev.setUnboundAt(Instant.now());
        bindingRepository.save(prev);
        log.info("无人机 {} 解绑电池 {}", droneAssetId, prev.getBatteryAssetId());
        return true;
    }

    /**
     * 当前生效电池 id。
     *
     * @param droneAssetId 无人机 asset_id
     * @return 当前绑定电池 asset_id；无则 empty
     */
    @Transactional(readOnly = true)
    public Optional<Long> currentBattery(Long droneAssetId) {
        return bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(droneAssetId)
                .map(DroneBatteryBinding::getBatteryAssetId);
    }

    /**
     * 无人机电池绑定历史（按绑定时间倒序）。
     *
     * @param droneAssetId 无人机 asset_id
     * @return 绑定历史
     */
    @Transactional(readOnly = true)
    public List<DroneBatteryBinding> history(Long droneAssetId) {
        return bindingRepository.findByDroneAssetIdOrderByBoundAtDesc(droneAssetId);
    }

    /**
     * 电池重复占用软校验（非阻断）：该电池已生效绑定在其他无人机上时仅告警。
     *
     * <p>换电/机场调度存在过渡态，硬拒会误伤正常作业；但「一块电池同时挂在两台无人机上」
     * 是重复占用的唯一信号，不拦也要留痕，交由运维研判。
     *
     * @param batteryAssetId 电池 asset_id
     */
    private void softCheckBatteryNotShared(Long batteryAssetId) {
        try {
            if (bindingRepository.existsByBatteryAssetIdAndActiveTrue(batteryAssetId)) {
                log.warn("电池 {} 当前已被其他无人机占用，本次为重复绑定（软校验，未阻断）", batteryAssetId);
            }
        } catch (Exception e) {
            log.warn("电池重复占用软校验跳过（非阻断）battery={}", batteryAssetId, e);
        }
    }

    /**
     * 电池供需视图（调度看板）。
     *
     * <p>各指标分块独立取数并各自兜异常：任一块依赖表不可用时只该块缺失，
     * 不整体 500 —— 看板是「尽量给数」的场景，不是强一致查询。
     *
     * @return 供需指标（batteryTotal / batteryInUse / batteryAvailable / droneBound / generatedAt）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> supplyDemandView() {
        Map<String, Object> view = new HashMap<>();

        long batteryTotal = 0L;
        try {
            batteryTotal = batteryRepository.count();
        } catch (Exception e) {
            log.warn("电池总数取数失败（供需视图跳过该块）", e);
        }
        view.put("batteryTotal", batteryTotal);

        long inUse = 0L;
        try {
            inUse = bindingRepository.countByActiveTrue();
        } catch (Exception e) {
            log.warn("在用电池数取数失败（供需视图跳过该块）", e);
        }
        view.put("batteryInUse", inUse);
        view.put("batteryAvailable", Math.max(0L, batteryTotal - inUse));

        // 每台无人机至多一条 active 绑定，故「在用电池数」即「已绑定无人机数」。
        view.put("droneBound", inUse);

        view.put("generatedAt", Instant.now());
        return view;
    }
}
