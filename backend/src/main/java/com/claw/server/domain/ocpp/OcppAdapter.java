package com.claw.server.domain.ocpp;

import com.claw.server.common.dto.ApiViews;
import com.claw.server.domain.asset.AssetService;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.TelemetryLatest;
import com.claw.server.domain.iot.TelemetryLatestRepository;
import com.claw.server.domain.station.ChargeSession;
import com.claw.server.domain.station.ChargeSessionRepository;
import com.claw.server.domain.swap.ElecPriceSnapshot;
import com.claw.server.domain.swap.ElecPriceSnapshotRepository;
import com.claw.server.domain.vpp.VppDispatchService;
import com.claw.server.domain.vpp.VppResource;
import com.claw.server.domain.vpp.VppResourceRepository;
import com.claw.server.domain.vpp.VppResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * OCPP 事件 → 域动作 适配器（对齐 {@code BmsAdapter}/{@code PvAdapter} 风格）。
 *
 * <p>把充电桩经 WS 上送的 OCPP 1.6J CALL 翻译成域内动作：
 * <ul>
 *   <li>{@code BootNotification} → 注册/激活站点（写 ocpp_charging_stations + 经 AssetService
 *       建档 CHARGER 资产/Device 行 + 创建 VppResource(CHARGER) 若缺失）；</li>
 *   <li>{@code Heartbeat} → 更新 last_heartbeat；</li>
 *   <li>{@code StatusNotification} → 写连接器 status → 映射 telemetry_latest（在线/故障/空闲）；</li>
 *   <li>{@code MeterValues}/{@code TransactionMeter} → 累计 Wh → 写 telemetry_latest（功率/电量）
 *       → 触发 ChargeSession 计量；</li>
 *   <li>{@code StartTransaction}/{@code StopTransaction} → 写 ocpp_transactions +
 *       关联合规结算（复用 ElecPriceSnapshot 电价口径）；</li>
 *   <li>{@code Authorize} → id_tag 校验（本期放行）。</li>
 * </ul>
 *
 * <p>本适配器只负责「域动作 + 构造 CALLRESULT 帧」，不下发帧（下发由端点经 WS 会话完成）。
 * 错误一律抛 {@link com.claw.server.common.api.BizException}（由端点转 CALLERROR 或全局异常处理器拦截）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcppAdapter {

    private final ChargingStationRepository stationRepo;
    private final ChargingConnectorRepository connectorRepo;
    private final OcppTransactionRepository txRepo;
    private final DeviceRepository deviceRepo;
    private final AssetService assetService;
    private final VppResourceService vppResourceService;
    private final VppResourceRepository vppResourceRepo;
    private final TelemetryLatestRepository telemetryRepo;
    private final ChargeSessionRepository chargeSessionRepo;
    private final ElecPriceSnapshotRepository priceRepo;
    private final OcppMessageRouter router;

    /** 自动建档充电桩的归属人（平台默认 owner）。 */
    @Value("${claw.ocpp.default-owner-id:1}")
    private Long defaultOwnerId;

    /** 充电桩注册到的虚拟电厂（缺省组合 1）。 */
    @Value("${claw.ocpp.default-portfolio-id:1}")
    private Long defaultPortfolioId;

    /** 处理桩对「我方下发指令」的 CALLRESULT 应答（如 SetChargingProfile 确认）。 */
    public void handleCallResult(String chargePointId, String msgId, JsonNode payload) {
        log.info("[OCPP] CALLRESULT chargePointId={} msgId={} payload={}", chargePointId, msgId, payload);
        // 可扩展：匹配待确认指令、回写 ocpp_transactions / 指令状态
    }

    /** 处理桩返回的 CALLERROR（如下发指令被拒）。 */
    public void handleCallError(String chargePointId, String msgId, String errorCode, String errorDesc) {
        log.warn("[OCPP] CALLERROR chargePointId={} msgId={} code={} desc={}", chargePointId, msgId, errorCode, errorDesc);
    }

    /** 处理一帧 CALL，返回要下发的 CALLRESULT / CALLERROR JSON。 */
    public String handleCall(String chargePointId, String msgId, String action, JsonNode payload) {
        try {
            return switch (action) {
                case "BootNotification" -> boot(chargePointId, msgId, payload);
                case "Heartbeat" -> heartbeat(chargePointId, msgId);
                case "StatusNotification" -> status(chargePointId, msgId, payload);
                case "MeterValues" -> meterValues(chargePointId, msgId, payload);
                case "StartTransaction" -> startTx(chargePointId, msgId, payload);
                case "StopTransaction" -> stopTx(chargePointId, msgId, payload);
                case "Authorize" -> router.buildCallResult(msgId,
                        object("idTagInfo", object("status", "Accepted")));
                default -> {
                    log.warn("[OCPP] 未支持动作 chargePointId={} action={}", chargePointId, action);
                    yield router.buildCallError(msgId, OcppMessageRouter.ERR_NOT_SUPPORTED,
                            "action not supported: " + action);
                }
            };
        } catch (Exception e) {
            log.error("[OCPP] 处理 CALL 异常 chargePointId={} action={}", chargePointId, action, e);
            return router.buildCallError(msgId, OcppMessageRouter.ERR_INTERNAL,
                    "internal error: " + e.getMessage());
        }
    }

    // ===================== BootNotification =====================

    @Transactional
    protected String boot(String chargePointId, String msgId, JsonNode p) {
        String vendor = text(p, "chargePointVendor");
        String model = text(p, "chargePointModel");
        String firmware = text(p, "firmwareVersion");
        String token = text(p, "authToken");

        ChargingStation station = stationRepo.findByChargePointId(chargePointId).orElse(null);
        if (station != null && station.getAuthToken() != null && !station.getAuthToken().isBlank()) {
            if (token == null || !station.getAuthToken().equals(token)) {
                log.warn("[OCPP] 鉴权失败 chargePointId={}", chargePointId);
                return router.buildCallError(msgId, OcppMessageRouter.ERR_SECURITY, "auth token mismatch");
            }
        }

        if (station == null) {
            station = ChargingStation.builder()
                    .chargePointId(chargePointId)
                    .vendor(vendor).model(model).firmware(firmware)
                    .status("ONLINE").lastHeartbeat(Instant.now())
                    .authToken(token)
                    .build();
            station = stationRepo.save(station);
            try {
                ApiViews.AssetView asset = assetService.createChargingPileAsset(
                        defaultOwnerId, chargePointId, vendor, model);
                station.setAssetId(asset.id());
                station = stationRepo.save(station);
                vppResourceService.register(asset.id(), defaultPortfolioId, VppDispatchService.CHARGER);
                log.info("[OCPP] 新站点建档并注册 VPP chargePointId={} assetId={}", chargePointId, asset.id());
            } catch (Exception e) {
                log.warn("[OCPP] 站点建档/VPP 注册失败（不阻断通信）chargePointId={}", chargePointId, e);
            }
        } else {
            station.setStatus("ONLINE");
            station.setLastHeartbeat(Instant.now());
            if (vendor != null) {
                station.setVendor(vendor);
            }
            if (model != null) {
                station.setModel(model);
            }
            if (firmware != null) {
                station.setFirmware(firmware);
            }
            station = stationRepo.save(station);
            if (station.getAssetId() != null
                    && vppResourceRepo.findByAssetId(station.getAssetId()).isEmpty()) {
                try {
                    vppResourceService.register(station.getAssetId(), defaultPortfolioId, VppDispatchService.CHARGER);
                } catch (Exception e) {
                    log.warn("[OCPP] 补登 VPP 资源失败 chargePointId={}", chargePointId, e);
                }
            }
        }
        ObjectNode r = router.emptyPayload();
        r.put("status", "Accepted");
        r.put("currentTime", Instant.now().toString());
        r.put("interval", 300);
        return router.buildCallResult(msgId, r);
    }

    // ===================== Heartbeat =====================

    @Transactional
    protected String heartbeat(String chargePointId, String msgId) {
        stationRepo.findByChargePointId(chargePointId).ifPresent(s -> {
            s.setLastHeartbeat(Instant.now());
            s.setStatus("ONLINE");
            stationRepo.save(s);
        });
        ObjectNode r = router.emptyPayload();
        r.put("currentTime", Instant.now().toString());
        return router.buildCallResult(msgId, r);
    }

    // ===================== StatusNotification =====================

    @Transactional
    protected String status(String chargePointId, String msgId, JsonNode p) {
        int connectorId = p.hasNonNull("connectorId") ? p.get("connectorId").asInt() : 0;
        String ocppStatus = text(p, "status");           // Available / Occupied / Charging / Faulted / Unavailable
        String errorCode = text(p, "errorCode");         // NoError / ...
        boolean faulted = ocppStatus != null && ocppStatus.equalsIgnoreCase("Faulted")
                || (errorCode != null && !errorCode.equalsIgnoreCase("NoError"));

        ChargingConnector c = connectorRepo.findByStationIdAndConnectorId(chargePointId, connectorId).orElse(null);
        if (c == null) {
            c = ChargingConnector.builder().stationId(chargePointId).connectorId(connectorId).build();
        }
        c.setStatus(ocppStatus != null ? ocppStatus : "Unavailable");
        connectorRepo.save(c);

        // 映射 telemetry_latest（在线/故障/空闲）：复用 bmsState 承载连接器状态，故障写 faults
        upsertTelemetry(chargePointId, t -> {
            t.setBmsState(ocppStatus);
            if (faulted) {
                t.setFaults("[\"" + (errorCode != null ? errorCode : "FAULTED") + "\"]");
            } else {
                t.setFaults(null);
            }
        });
        return router.buildCallResult(msgId, router.emptyPayload());
    }

    // ===================== MeterValues =====================

    @Transactional
    protected String meterValues(String chargePointId, String msgId, JsonNode p) {
        int connectorId = p.hasNonNull("connectorId") ? p.get("connectorId").asInt() : 0;
        BigDecimal powerW = null;     // 瞬时功率 W（Power.Active.Import / Power.Offered）
        BigDecimal energyWh = null;   // 累计电量 Wh（Energy.Active.Import.Register）

        JsonNode meterValue = p.get("meterValue");
        if (meterValue != null && meterValue.isArray()) {
            for (JsonNode mv : meterValue) {
                JsonNode sampled = mv.get("sampledValue");
                if (sampled == null || !sampled.isArray()) {
                    continue;
                }
                for (JsonNode sv : sampled) {
                    String measurand = text(sv, "measurand");
                    BigDecimal value = decimal(sv, "value");
                    if (value == null) {
                        continue;
                    }
                    if (isPower(measurand)) {
                        powerW = value;
                    } else if (isEnergy(measurand)) {
                        energyWh = value;
                    }
                }
            }
        }

        final BigDecimal fEnergyWh = energyWh;
        final BigDecimal fPowerW = powerW;
        if (fEnergyWh != null) {
            connectorRepo.findByStationIdAndConnectorId(chargePointId, connectorId).ifPresent(c -> {
                c.setLastMeterWh(fEnergyWh);
                connectorRepo.save(c);
            });
        }
        // 写 telemetry_latest（功率/电量）
        if (fPowerW != null) {
            upsertTelemetry(chargePointId, t -> {
                t.setPowerW(fPowerW);
                t.setMeterActivePowerW(fPowerW); // 充电为电网下网（进口）
            });
        }
        // 触发活跃 ChargeSession 计量
        if (powerW != null || energyWh != null) {
            updateActiveSession(chargePointId, connectorId, powerW, energyWh);
        }
        return router.buildCallResult(msgId, router.emptyPayload());
    }

    // ===================== StartTransaction =====================

    @Transactional
    protected String startTx(String chargePointId, String msgId, JsonNode p) {
        int connectorId = p.hasNonNull("connectorId") ? p.get("connectorId").asInt() : 0;
        String idTag = text(p, "idTag");
        BigDecimal meterStart = decimal(p, "meterStart");
        int txId = nextTxId(chargePointId);

        ChargingStation station = stationRepo.findByChargePointId(chargePointId).orElse(null);
        Long assetId = station != null ? station.getAssetId() : null;

        OcppTransaction tx = OcppTransaction.builder()
                .stationId(chargePointId)
                .connectorId(connectorId)
                .idTag(idTag)
                .startWh(meterStart)
                .startAt(Instant.now())
                .status("INPROGRESS")
                .transactionId(txId)
                .assetId(assetId)
                .build();
        txRepo.save(tx);

        // 关联合规结算会话（ChargeSession V103）：以 deviceNo=chargePointId 关联，station 维度留空
        ChargeSession session = ChargeSession.builder()
                .stationId(null)
                .assetId(assetId)
                .deviceNo(chargePointId)
                .status("ACTIVE")
                .startedAt(Instant.now())
                .startEnergyWh(meterStart)
                .build();
        chargeSessionRepo.save(session);

        ObjectNode r = router.emptyPayload();
        r.set("idTagInfo", object("status", "Accepted"));
        r.put("transactionId", txId);
        log.info("[OCPP] 开始充电 chargePointId={} connector={} txId={} idTag={}", chargePointId, connectorId, txId, idTag);
        return router.buildCallResult(msgId, r);
    }

    // ===================== StopTransaction =====================

    @Transactional
    protected String stopTx(String chargePointId, String msgId, JsonNode p) {
        Integer txId = p.hasNonNull("transactionId") ? p.get("transactionId").asInt() : null;
        String idTag = text(p, "idTag");
        BigDecimal meterStop = decimal(p, "meterStop");

        OcppTransaction tx = (txId != null ? txRepo.findByTransactionId(txId) : Optional.<OcppTransaction>empty())
                .orElse(null);
        if (tx == null && txId != null) {
            // 兜底：取该桩最近一笔 INPROGRESS
            List<OcppTransaction> open = txRepo.findByStationIdAndStatus(chargePointId, "INPROGRESS");
            if (!open.isEmpty()) {
                tx = open.get(open.size() - 1);
            }
        }
        if (tx == null) {
            log.warn("[OCPP] StopTransaction 找不到对应事务 chargePointId={} txId={}", chargePointId, txId);
            return router.buildCallResult(msgId, object("idTagInfo", object("status", "Accepted")));
        }

        tx.setStopWh(meterStop);
        tx.setMeterWh(meterStop != null && tx.getStartWh() != null
                ? meterStop.subtract(tx.getStartWh()).max(BigDecimal.ZERO) : null);
        tx.setStopAt(Instant.now());
        tx.setStatus("COMPLETED");
        tx.setIdTag(idTag);
        txRepo.save(tx);

        // 结算会话
        BigDecimal meterWh = tx.getMeterWh();
        BigDecimal pricePerWh = resolvePricePerWh();
        chargeSessionRepo.findByDeviceNoAndStatus(chargePointId, "ACTIVE").stream()
                .findFirst().ifPresent(s -> {
                    s.setEndedAt(Instant.now());
                    s.setEndEnergyWh(meterStop);
                    s.setEnergyDeliveredWh(meterWh);
                    s.setStatus("ENDED");
                    if (pricePerWh != null) {
                        s.setPricePerWh(pricePerWh);
                        BigDecimal energy = meterWh != null ? meterWh : BigDecimal.ZERO;
                        BigDecimal elec = energy.multiply(pricePerWh)
                                .setScale(4, RoundingMode.HALF_UP);
                        s.setElectricityFee(elec);
                        s.setServiceFee(BigDecimal.ZERO);
                        s.setTotalFee(elec);
                    }
                    chargeSessionRepo.save(s);
                });

        log.info("[OCPP] 结束充电 chargePointId={} txId={} meterWh={}", chargePointId, tx.getTransactionId(), meterWh);
        return router.buildCallResult(msgId, object("idTagInfo", object("status", "Accepted")));
    }

    // ===================== 工具 =====================

    /** 更新活跃 ChargeSession 的峰值功率与实时累计电量（计量铁律：以电表 Wh 为准）。 */
    private void updateActiveSession(String chargePointId, int connectorId, BigDecimal powerW, BigDecimal energyWh) {
        List<ChargeSession> active = chargeSessionRepo.findByDeviceNoAndStatus(chargePointId, "ACTIVE");
        for (ChargeSession s : active) {
            boolean changed = false;
            if (powerW != null) {
                BigDecimal cur = s.getPeakPowerW() != null ? s.getPeakPowerW() : BigDecimal.ZERO;
                if (powerW.compareTo(cur) > 0) {
                    s.setPeakPowerW(powerW);
                    changed = true;
                }
            }
            if (energyWh != null) {
                s.setEndEnergyWh(energyWh);
                changed = true;
            }
            if (changed) {
                chargeSessionRepo.save(s);
            }
        }
    }

    /** 取最新电价快照的单价（USD/Wh）：gridPrice 优先，回落 pvPrice；均缺返回 null。 */
    private BigDecimal resolvePricePerWh() {
        List<ElecPriceSnapshot> snaps = priceRepo.findAll();
        if (snaps.isEmpty()) {
            return null;
        }
        ElecPriceSnapshot latest = snaps.stream()
                .max(Comparator.comparing(s -> s.getEffectiveDate() != null ? s.getEffectiveDate() : LocalDate.MIN))
                .orElse(snaps.get(0));
        BigDecimal rate = latest.getGridPrice() != null ? latest.getGridPrice() : latest.getPvPrice();
        if (rate == null) {
            return null;
        }
        return rate.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP); // USD/kWh → USD/Wh
    }

    /** 加载/创建站点对应资产的 telemetry_latest 并应用变更（best-effort）。 */
    private void upsertTelemetry(String chargePointId, Consumer<TelemetryLatest> mutate) {
        Optional<Long> deviceId = resolveDeviceId(chargePointId);
        if (deviceId.isEmpty()) {
            return;
        }
        TelemetryLatest t = telemetryRepo.findByDeviceId(deviceId.get()).orElse(null);
        if (t == null) {
            Long assetId = stationRepo.findByChargePointId(chargePointId)
                    .map(ChargingStation::getAssetId).orElse(null);
            t = TelemetryLatest.builder()
                    .deviceId(deviceId.get())
                    .assetId(assetId)
                    .build();
        }
        mutate.accept(t);
        if (t.getReportedAt() == null) {
            t.setReportedAt(Instant.now());
        } else {
            t.setReportedAt(Instant.now());
        }
        telemetryRepo.save(t);
    }

    /** 站点 → 资产 → CHARGER 设备 id（用于 telemetry_latest 主键）。 */
    private Optional<Long> resolveDeviceId(String chargePointId) {
        return stationRepo.findByChargePointId(chargePointId)
                .map(ChargingStation::getAssetId)
                .flatMap(assetId -> deviceRepo.findByAssetId(assetId).stream()
                        .filter(d -> "CHARGER".equals(d.getDeviceType()))
                        .map(Device::getId)
                        .findFirst());
    }

    private static boolean isPower(String measurand) {
        if (measurand == null) {
            return false; // 缺省 measurand 在 OCPP 1.6 视为 Power.Active.Import，但稳妥起见不假设
        }
        return measurand.equalsIgnoreCase("Power.Active.Import")
                || measurand.equalsIgnoreCase("Power.Offered")
                || measurand.equalsIgnoreCase("Current.Import");
    }

    private static boolean isEnergy(String measurand) {
        if (measurand == null) {
            return false;
        }
        return measurand.equalsIgnoreCase("Energy.Active.Import.Register")
                || measurand.equalsIgnoreCase("Energy.Active.Import")
                || measurand.equalsIgnoreCase("Energy.Active.Export.Register");
    }

    /** 生成事务号：取该桩当前最大 transaction_id + 1（无则自 1 起），避免全局序列依赖。 */
    private int nextTxId(String chargePointId) {
        List<OcppTransaction> all = txRepo.findByStationId(chargePointId);
        int max = 0;
        for (OcppTransaction t : all) {
            if (t.getTransactionId() != null && t.getTransactionId() > max) {
                max = t.getTransactionId();
            }
        }
        return max + 1;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static ObjectNode object(String k, ObjectNode v) {
        ObjectNode n = JSON.createObjectNode();
        n.set(k, v);
        return n;
    }

    private static ObjectNode object(String k, String v) {
        ObjectNode n = JSON.createObjectNode();
        n.put(k, v);
        return n;
    }

    private static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private static BigDecimal decimal(JsonNode n, String f) {
        if (n == null || !n.hasNonNull(f)) {
            return null;
        }
        try {
            return new BigDecimal(n.get(f).asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
