package com.claw.server.domain.ocpp;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.TelemetryLatest;
import com.claw.server.domain.iot.TelemetryLatestRepository;
import com.claw.server.domain.vpp.VppDispatchService;
import com.claw.server.domain.vpp.VppResource;
import com.claw.server.domain.vpp.VppResourceRepository;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OCPP 指令下发服务（充电桩唯一协议抓手）。
 *
 * <p>把「语义指令」翻译成 OCPP 1.6J 帧经 WS 下发，并落 {@code ocpp_message_log}（OUT）。
 * 本期核心杠杆是 {@link #sendChargingProfile}（= VPP 对充电桩的功率控制手段）：
 * 下发 {@code SetChargingProfile} 把充电桩功率钳制到目标 W（复用 VPP「安全优先」思路——
 * 以绝对功率设定封顶，而非绕过任何动态限值）。
 *
 * <p>VPP 接缝：{@link #apply(VppDispatchService.CommandDraft)} 把 VPP 产出的
 * {@code CMD_SET_CHARGER_POWER} / {@code CMD_CURTAIL_CHARGER} 翻译成绝对功率的
 * {@code SetChargingProfile} 并真实下发（仅充电桩有真实协议，其它资源保持影子）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcppCommandService {

    private final OcppSessionRegistry registry;
    private final OcppMessageRouter router;
    private final OcppMessageLogRepository logRepo;
    private final VppResourceRepository vppResourceRepo;
    private final DeviceRepository deviceRepo;
    private final TelemetryLatestRepository telemetryRepo;

    private final AtomicInteger profileSeq = new AtomicInteger(1);

    // ===================== VPP 执行器接缝 =====================

    /**
     * 把 VPP 指令草稿翻译成 OCPP 帧并真实下发（执行路径，非影子）。
     *
     * @param draft VPP 产出的充电桩指令（resourceId = VppResource.id，targetW = 绝对/削减功率 W）
     * @return 执行结果（含下发的 chargePointId 与 msgId）
     * @throws BizException 充电桩离线 / 资源非 CHARGER / 解析失败时抛出
     */
    public ExecutionResult apply(VppDispatchService.CommandDraft draft) {
        if (draft == null || !isChargerCommand(draft.commandType())) {
            throw BizException.invalidParam("error.vpp.command.type.invalid", String.valueOf(draft));
        }
        VppResource resource = vppResourceRepo.findById(draft.resourceId())
                .orElseThrow(() -> BizException.notFound("error.vpp.resource.not.found"));
        if (!VppDispatchService.CHARGER.equals(resource.getResourceType())) {
            throw BizException.invalidParam("error.ocpp.not.charger.resource", resource.getResourceType());
        }
        Long assetId = resource.getAssetId();
        String chargePointId = deviceRepo.findByAssetId(assetId).stream()
                .filter(d -> "CHARGER".equals(d.getDeviceType()))
                .map(Device::getDeviceNo)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElseThrow(() -> BizException.notFound("error.ocpp.station.not.found"));

        // 解析绝对目标功率 W
        BigDecimal absoluteW = resolveAbsoluteWatts(draft, assetId);
        if (absoluteW == null) {
            absoluteW = BigDecimal.ZERO;
        }
        boolean sent = sendChargingProfile(chargePointId, absoluteW);
        return new ExecutionResult(draft.resourceId(), draft.commandType(), chargePointId, sent,
                sent ? null : "charge point offline");
    }

    /** CMD_SET_CHARGER_POWER = 绝对功率；CMD_CURTAIL_CHARGER = 相对削减量（需叠加当前功率）。 */
    private BigDecimal resolveAbsoluteWatts(VppDispatchService.CommandDraft draft, Long assetId) {
        BigDecimal target = draft.targetW() != null ? draft.targetW() : BigDecimal.ZERO;
        if (VppDispatchService.CMD_SET_CHARGER_POWER.equals(draft.commandType())) {
            return target.max(BigDecimal.ZERO);
        }
        // CURTAIL：绝对目标 = 当前功率 − 削减量（无当前功率时按 0 兜底，即全削减）
        BigDecimal current = currentChargerPower(assetId);
        if (current == null) {
            return BigDecimal.ZERO;
        }
        return current.subtract(target).max(BigDecimal.ZERO);
    }

    private BigDecimal currentChargerPower(Long assetId) {
        TelemetryLatest t = telemetryRepo.findTopByAssetIdOrderByReportedAtDescIdDesc(assetId).orElse(null);
        if (t == null) {
            return null;
        }
        return t.getPowerW() != null ? t.getPowerW() : t.getMeterActivePowerW();
    }

    private static boolean isChargerCommand(String commandType) {
        return VppDispatchService.CMD_SET_CHARGER_POWER.equals(commandType)
                || VppDispatchService.CMD_CURTAIL_CHARGER.equals(commandType);
    }

    /** VPP 执行结果。 */
    public record ExecutionResult(Long resourceId, String commandType, String chargePointId,
                                  boolean sent, String error) {
    }

    // ===================== 下发原语 =====================

    /** 下发 SetChargingProfile：把充电桩功率钳制到 watts（绝对功率 W）。 */
    public boolean sendChargingProfile(String chargePointId, BigDecimal watts) {
        int profileId = profileSeq.getAndIncrement();
        ObjectNode payload = router.emptyPayload();
        payload.put("connectorId", 0);
        ObjectNode profile = payload.putObject("csChargingProfiles");
        profile.put("chargingProfileId", profileId);
        profile.put("stackLevel", 1);
        profile.put("chargingProfilePurpose", "TxDefaultProfile");
        profile.put("chargingProfileKind", "Absolute");
        profile.put("recurrencyKind", "Daily");
        ObjectNode schedule = profile.putObject("chargingSchedule");
        schedule.put("duration", 86400);
        schedule.put("startSchedule", Instant.now().toString());
        schedule.put("chargingRateUnit", "W");
        ArrayNode periods = schedule.putArray("chargingSchedulePeriod");
        ObjectNode period = periods.addObject();
        period.put("startPeriod", 0);
        period.put("limit", watts != null ? watts.max(BigDecimal.ZERO) : BigDecimal.ZERO);
        return dispatch(chargePointId, "SetChargingProfile", payload);
    }

    /** 远程启动充电。 */
    public boolean remoteStart(String chargePointId, int connectorId, String idTag) {
        ObjectNode p = router.emptyPayload();
        p.put("connectorId", connectorId);
        p.put("idTag", idTag != null ? idTag : "");
        return dispatch(chargePointId, "RemoteStartTransaction", p);
    }

    /** 远程停止充电。 */
    public boolean remoteStop(String chargePointId, int transactionId) {
        ObjectNode p = router.emptyPayload();
        p.put("transactionId", transactionId);
        return dispatch(chargePointId, "RemoteStopTransaction", p);
    }

    /** 复位（soft / hard）。 */
    public boolean reset(String chargePointId, String type) {
        ObjectNode p = router.emptyPayload();
        p.put("type", "hard".equalsIgnoreCase(type) ? "Hard" : "Soft");
        return dispatch(chargePointId, "Reset", p);
    }

    /** 切换可用性（Operable / Inoperative）。 */
    public boolean changeAvailability(String chargePointId, int connectorId, String type) {
        ObjectNode p = router.emptyPayload();
        p.put("connectorId", connectorId);
        p.put("type", "inoperative".equalsIgnoreCase(type) ? "Inoperative" : "Operable");
        return dispatch(chargePointId, "ChangeAvailability", p);
    }

    // ===================== 内部：组帧 + 下发 + 日志 =====================

    private boolean dispatch(String chargePointId, String action, ObjectNode payload) {
        String msgId = router.nextMessageId();
        String frame = router.buildCall(msgId, action, payload);
        boolean ok = registry.send(chargePointId, frame);
        logOut(chargePointId, msgId, action, frame);
        if (!ok) {
            log.warn("[OCPP] 指令下发失败（离线或无连接）chargePointId={} action={}", chargePointId, action);
        } else {
            log.info("[OCPP] 已下发 {} chargePointId={} watts/args={}", action, chargePointId, payload);
        }
        return ok;
    }

    private void logOut(String chargePointId, String msgId, String action, String frame) {
        try {
            logRepo.save(OcppMessageLog.builder()
                    .stationId(chargePointId)
                    .direction("OUT")
                    .msgType("CALL")
                    .msgId(msgId)
                    .payloadJson(frame)
                    .build());
        } catch (Exception e) {
            log.warn("[OCPP] 报文日志(OUT)落库失败 chargePointId={}：{}", chargePointId, e.getMessage());
        }
    }
}
