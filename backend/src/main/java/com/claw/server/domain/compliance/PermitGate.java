package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.DroneCommandType;
import com.claw.server.domain.iot.DroneTrajectory;
import com.claw.server.domain.iot.DroneTrajectoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 无人机合规闸门（切片 3 核心）：受控指令下发前的唯一准入判定点。
 *
 * <p><b>校验序</b>（顺序即优先级，先命中的先起作用）：
 * <ol>
 *   <li><b>安全指令豁免</b> —— LAND / RETURN_HOME / HOLD / PAUSE / CANCEL_TASK 在空中安全优先原则下
 *       <b>任何档位都不拦</b>（无人机已在空中时，禁止它降落/返航等于制造事故）；</li>
 *   <li><b>零容忍区</b> —— {@code source='BAKED-IN'} 图层（机场/王宫/遗产区）<b>恒定拒绝</b>：
 *       不看 {@code enabled}、不受 {@code permit_gate_mode} 影响。OFF 只豁免「运营限制」，
 *       不豁免「安全底线」；</li>
 *   <li><b>档位开关</b> —— {@code OFF} 直接放行（跳过③④）；</li>
 *   <li><b>NFZ 运营限制层</b> —— 启用中的 REGULATION-* 图层（含时段窗口）；</li>
 *   <li><b>许可有效性</b> —— 仅 {@code regulatory_profile=KH-EARLY-OPERATION} 时要求有效许可
 *       （KH-GENERAL 不强制许可，仅 NFZ）；</li>
 *   <li><b>省域范围</b> —— 作业省域已知时要求许可覆盖该省（未知时在⑤按「不限省域」处理）。</li>
 * </ol>
 *
 * <p><b>档位语义</b>：STRICT = 不通过即抛 {@link BizException}（403，HTTP FORBIDDEN）；
 * ADVISORY = 记 WARN 日志后放行（提示而非阻断）；OFF = 跳过运营限制（安全底线仍生效）。
 *
 * <p><b>位置来源</b>：无人机最新航迹点（{@code drone_trajectory} 最新一条）。无位置记录时
 * 空间类校验跳过并记入 notes —— 不能凭空断定一架未知位置无人机的越界与否。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermitGate {

    /**
     * 安全指令集合：任何档位下都不被闸门拦截（空中安全优先）。
     * 注意 RESUME 不在其中 —— 恢复作业属于继续执行，仍受控。
     */
    public static final Set<DroneCommandType> SAFETY_COMMANDS = Set.of(
            DroneCommandType.LAND,
            DroneCommandType.RETURN_HOME,
            DroneCommandType.HOLD,
            DroneCommandType.PAUSE,
            DroneCommandType.CANCEL_TASK);

    private final NfzService nfzService;
    private final PermitService permitService;
    private final DroneComplianceConfigService configService;
    private final DroneTrajectoryRepository trajectoryRepository;

    /** 闸门决策结果。 */
    public record Decision(
            /** 是否放行。 */
            boolean allowed,
            /** 是否命中零容忍区（恒定拒绝；ADVISORY 档也不放行）。 */
            boolean hardDenied,
            /** 生效闸门档位。 */
            String mode,
            /** 生效合规档位。 */
            String profile,
            /** 拒绝/告警原因（可读，供前端与审计日志）。 */
            List<String> reasons,
            /** 判定时刻。 */
            Instant checkedAt) {

        static Decision allow(String mode, String profile, List<String> reasons, Instant at) {
            return new Decision(true, false, mode, profile, reasons, at);
        }

        static Decision deny(String mode, String profile, List<String> reasons, Instant at) {
            return new Decision(false, false, mode, profile, reasons, at);
        }

        static Decision hardDeny(String mode, String profile, List<String> reasons, Instant at) {
            return new Decision(false, true, mode, profile, reasons, at);
        }
    }

    /** 便捷入口（以当前时刻、未知省域判定）。 */
    public Decision check(Long assetId, DroneCommandType command) {
        return check(assetId, command, null, Instant.now());
    }

    /**
     * 准入判定主入口。
     *
     * @param assetId  无人机 asset_id
     * @param command  待下发指令
     * @param province 作业省域（可空：远控指令通常未知）
     * @param at       判定时刻
     * @return 决策（STRICT 拒绝时直接抛 {@link BizException}，不返回）
     * @throws BizException 40305 error.drone.permit.denied（合规闸门拒绝，HTTP 403）
     */
    public Decision check(Long assetId, DroneCommandType command, String province, Instant at) {
        // ① 安全指令豁免：空中安全优先，任何档位都不拦。
        if (SAFETY_COMMANDS.contains(command)) {
            return Decision.allow("SAFETY-EXEMPT", configService.regulatoryProfile(),
                    List.of("safety command exempt: " + command), at);
        }

        String profile = configService.regulatoryProfile();
        String mode = configService.permitGateMode();
        List<String> denialReasons = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean hardDenied = false;

        // ② 零容忍区：恒定拒绝（不看档位、不看 enabled）。
        Optional<double[]> position = latestPosition(assetId);
        if (position.isEmpty()) {
            notes.add("no known position for asset " + assetId + "; spatial NFZ not evaluated");
        } else {
            double lng = position.get()[0];
            double lat = position.get()[1];
            Optional<NfzLayer> bakedIn = nfzService.firstBakedInHit(lng, lat, at);
            if (bakedIn.isPresent()) {
                // 命中零容忍区：置 hardDenied，ADVISORY/OFF 都不能放行（安全底线不可配置放开）。
                hardDenied = true;
                denialReasons.add("zero-tolerance zone: " + bakedIn.get().getName()
                        + " (source=" + bakedIn.get().getSource() + ", baked-in, always denied)");
            }
        }

        // ③ 档位开关：OFF 只豁免「运营限制」（④⑤），不豁免上面的安全底线（②）。
        if (!hardDenied && DroneComplianceConfigService.MODE_OFF.equals(mode)) {
            notes.add("permit gate mode=OFF; operational checks skipped (baked-in zones still enforced)");
            return Decision.allow(mode, profile, merge(notes, denialReasons), at);
        }

        // ④ NFZ 运营限制层（省/时段）。
        if (!hardDenied) {
            position.ifPresent(pos -> nfzService
                    .firstOperationalHit(pos[0], pos[1], at)
                    .ifPresent(layer -> denialReasons.add(
                            "nfz layer: " + layer.getName() + " (level=" + layer.getLevel() + ")")));

            // ⑤ 许可有效性（仅前期运营限制档要求）。
            if (configService.permitRequired()
                    && permitService.validPermitFor(assetId, province, at).isEmpty()) {
                denialReasons.add("no valid airspace permit for asset " + assetId
                        + " (profile=" + profile + ")");
            }
        }

        // ⑥ 省域范围：已在⑤的 coversProvince 中判定（省域未知 → 按不限省域处理），此处仅记 notes。
        if (province != null && !province.isBlank()) {
            notes.add("operation province=" + province.trim());
        }

        // 零容忍区：恒定拒绝，任何档位（含 ADVISORY/OFF）都抛 403。
        if (hardDenied) {
            log.warn("合规闸门命中零容忍区（恒定拒绝）asset={} command={} mode={} 原因: {}",
                    assetId, command, mode, String.join("; ", denialReasons));
            throw BizException.of(BizException.COMPLIANCE_DENIED, "error.drone.permit.denied",
                    String.join("; ", denialReasons));
        }

        if (!denialReasons.isEmpty()) {
            String joined = String.join("; ", denialReasons);
            if (DroneComplianceConfigService.MODE_ADVISORY.equals(mode)) {
                log.warn("合规闸门 ADVISORY 放行 asset={} command={} 原因: {}", assetId, command, joined);
                return Decision.allow(mode, profile, merge(notes, denialReasons), at);
            }
            log.info("合规闸门拒绝 asset={} command={} mode={} 原因: {}", assetId, command, mode, joined);
            throw BizException.of(BizException.COMPLIANCE_DENIED, "error.drone.permit.denied", joined);
        }
        return Decision.allow(mode, profile, merge(notes, denialReasons), at);
    }

    /** 无人机最新航迹点坐标 [lng, lat]（无记录或坐标缺失则 empty）。 */
    private Optional<double[]> latestPosition(Long assetId) {
        if (assetId == null) {
            return Optional.empty();
        }
        Optional<DroneTrajectory> latest = trajectoryRepository.findTopByAssetIdOrderByTsDesc(assetId);
        if (latest.isEmpty() || latest.get().getLng() == null || latest.get().getLat() == null) {
            return Optional.empty();
        }
        return Optional.of(new double[] {latest.get().getLng(), latest.get().getLat()});
    }

    /** 合并去空列表。 */
    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }
}
