package com.claw.server.domain.swap;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.SwapRequests;
import com.claw.server.common.dto.SwapViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.SwapStatus;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.station.Station;
import com.claw.server.domain.station.StationBattery;
import com.claw.server.domain.station.StationBatteryRepository;
import com.claw.server.domain.station.StationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 换电服务：换电单状态机（技术文档 2.3/2.4）。
 *
 * <p>流程（复式记账原子）：
 * <ol>
 *   <li><b>create</b>：选站 + FIFO 派发满电电池 → 校验协议/余额 →
 *       押金冻结（MASTER → DEPOSIT_LOCKED）+ 预扣电费（MASTER → 平台 MASTER）→ FROZEN；</li>
 *   <li><b>confirm</b>：双向押金流转（旧电池押金退还用户 DEPOSIT_LOCKED → MASTER）+
 *       管理权切换（B_new→用户，B_old→站方）+ 电池位更新（B_new 出库、B_old 进充电位）→ SWAPPING；</li>
 *   <li><b>settle</b>：按锁定快照实际用量计价 → 多退少补 → SETTLED（押金继续冻结，随电池持有流转）；</li>
 *   <li><b>cancel</b>：押金解冻 + 预扣退回 → CANCELLED。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SwapService {

    private final SwapOrderRepository swapOrderRepository;
    private final OrderEventRepository eventRepository;
    private final StationRepository stationRepository;
    private final StationBatteryRepository stationBatteryRepository;
    private final AssetRepository assetRepository;
    private final BatteryDetailRepository batteryDetailRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final SwapBillingService billingService;

    // ------------------------------------------------------------------
    // 1. 创建换电单：押金冻结 + 预扣
    // ------------------------------------------------------------------
    @Transactional
    public SwapViews.SwapOrderView create(SwapRequests.Create req, Long userId) {
        Station station = stationRepository.findById(req.stationId())
                .orElseThrow(() -> BizException.notFound("error.station.not.found"));
        if (!"ACTIVE".equals(station.getStatus())) {
            throw BizException.of(40960, "error.swap.station.inactive");
        }

        // FIFO 派发满电电池（按槽位顺序取第一块 READY）
        StationBattery outSlot = stationBatteryRepository.findByStationIdOrderBySlotNoAsc(req.stationId()).stream()
                .filter(s -> "READY".equals(s.getStatus()))
                .findFirst()
                .orElseThrow(() -> BizException.of(40961, "error.swap.no.battery"));
        Long batteryOutId = outSlot.getBatteryId();
        requireBattery(batteryOutId);
        BatteryDetail outDetail = batteryDetailRepository.findByAssetId(batteryOutId)
                .orElseThrow(() -> BizException.of(40461, "error.swap.battery.detail"));

        // 协议匹配（B_new.protocol_ver 与请求协议一致，均提供时校验）
        if (req.protocolVer() != null && !req.protocolVer().isBlank()
                && outDetail.getProtocolVer() != null
                && !req.protocolVer().equals(outDetail.getProtocolVer())) {
            throw BizException.of(40962, "error.swap.protocol.mismatch");
        }

        // 旧电池（首次换电可为空）：读取旧押金供 confirm 双向流转退还
        BigDecimal oldDeposit = BigDecimal.ZERO;
        if (req.batteryInId() != null) {
            requireBattery(req.batteryInId());
            oldDeposit = batteryDetailRepository.findByAssetId(req.batteryInId())
                    .orElseThrow(() -> BizException.of(40461, "error.swap.battery.detail"))
                    .getDepositValue();
        }

        // 计价 + 押金
        BigDecimal estKwh = req.estKwh() != null ? req.estKwh() : new BigDecimal("2.00");
        SwapViews.QuoteView quote = billingService.quote(estKwh);
        BigDecimal deposit = outDetail.getDepositValue();
        BigDecimal needTotal = quote.total().add(deposit);

        Account master = accountService.getOrCreateUserAccount(userId);
        if (master.getBalance().compareTo(needTotal) < 0) {
            throw BizException.of(42260, "error.swap.balance.insufficient");
        }

        String orderNo = "SW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Account locked = accountService.getOrCreateSubAccount(userId, AccountType.DEPOSIT_LOCKED);
        Account platform = accountService.getOrCreatePlatformAccount(AccountType.MASTER);

        // 押金冻结（用户 MASTER → 用户押金冻结户）
        if (deposit.signum() > 0) {
            ledgerService.postEntries(BizType.DEPOSIT_HOLD, orderNo, List.of(
                    new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D, deposit, "换电押金冻结 B_new"),
                    new LedgerRequests.Entry(locked.getId(), LedgerRequests.Direction.C, deposit, "换电押金冻结 B_new")));
        }
        // 预扣电费+服务费（用户 MASTER → 平台 MASTER，实缴实结多退少补）
        ledgerService.postEntries(BizType.SWAP_PAY, orderNo + ":PREAUTH", List.of(
                new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D, quote.total(), "换电预扣"),
                new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.C, quote.total(), "换电预扣")));

        SwapOrder order = swapOrderRepository.save(SwapOrder.builder()
                .orderNo(orderNo)
                .userId(userId)
                .stationId(req.stationId())
                .vehicleId(req.vehicleId())
                .batteryOutId(batteryOutId)
                .batteryInId(req.batteryInId())
                .status(SwapStatus.FROZEN)
                .protocolVer(outDetail.getProtocolVer())
                .batteryDeposit(deposit)
                .oldBatteryDeposit(oldDeposit)
                .estKwh(estKwh)
                .estElecFee(quote.elecFee())
                .estServiceFee(quote.serviceFee())
                .estTotal(quote.total())
                .socStart(new BigDecimal("100.00"))
                .priceSnapshot(billingService.snapshotJson())
                .settleStatus("PREAUTHED")
                .build());
        event(orderNo, "FROZEN", userId, null);

        log.info("换电单 {} 创建并冻结：押金 ${} 预扣 ${} 站={}", orderNo, deposit, quote.total(), req.stationId());
        return toView(order);
    }

    // ------------------------------------------------------------------
    // 2. 确认换电：双向押金流转 + 管理权切换 + 电池位更新
    // ------------------------------------------------------------------
    @Transactional
    public SwapViews.SwapOrderView confirm(String orderNo, Long operatorId) {
        SwapOrder order = load(orderNo);
        requireStatus(order, SwapStatus.FROZEN);

        // 双向押金流转：旧电池押金退还给用户（用户继续持有新电池押金冻结）
        if (order.getBatteryInId() != null && order.getOldBatteryDeposit().signum() > 0) {
            Account master = accountService.getOrCreateUserAccount(order.getUserId());
            Account locked = accountService.getOrCreateSubAccount(order.getUserId(), AccountType.DEPOSIT_LOCKED);
            ledgerService.postEntries(BizType.DEPOSIT_HOLD, orderNo + ":SWAP", List.of(
                    new LedgerRequests.Entry(locked.getId(), LedgerRequests.Direction.D,
                            order.getOldBatteryDeposit(), "旧电池押金退还"),
                    new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.C,
                            order.getOldBatteryDeposit(), "旧电池押金退还")));
        }

        // 管理权切换：B_new → 用户；B_old → 站方（operator）
        Asset out = assetRepository.findById(order.getBatteryOutId())
                .orElseThrow(() -> BizException.notFound("error.asset.not.found"));
        out.setUserId(order.getUserId());
        out.setStatus(AssetStatus.IN_USE);
        out.setUpdatedAt(Instant.now());
        assetRepository.save(out);

        if (order.getBatteryInId() != null) {
            Asset in = assetRepository.findById(order.getBatteryInId())
                    .orElseThrow(() -> BizException.notFound("error.asset.not.found"));
            in.setUserId(operatorId);
            in.setStatus(AssetStatus.IN_STOCK);
            in.setUpdatedAt(Instant.now());
            assetRepository.save(in);
        }

        // 电池位更新：B_new 槽位出库；B_old 进充电位（跨站归还不在此站电池位时跳过）
        stationBatteryRepository.findByBatteryId(order.getBatteryOutId()).ifPresent(slot -> {
            slot.setStatus("OUT");
            slot.setUpdatedAt(Instant.now());
            stationBatteryRepository.save(slot);
        });
        if (order.getBatteryInId() != null) {
            stationBatteryRepository.findByBatteryId(order.getBatteryInId()).ifPresent(slot -> {
                slot.setStatus("CHARGING");
                slot.setSoc(order.getSocEnd() != null ? order.getSocEnd() : new BigDecimal("0.00"));
                slot.setUpdatedAt(Instant.now());
                stationBatteryRepository.save(slot);
            });
        }

        order.setStatus(SwapStatus.SWAPPING);
        order.setUpdatedAt(Instant.now());
        swapOrderRepository.save(order);
        event(orderNo, "SWAPPING", operatorId, null);

        log.info("换电单 {} 确认双向流转：B_out={} B_in={} operator={}",
                orderNo, order.getBatteryOutId(), order.getBatteryInId(), operatorId);
        return toView(order);
    }

    // ------------------------------------------------------------------
    // 3. 结算：按锁定快照实际用量计价，多退少补
    // ------------------------------------------------------------------
    @Transactional
    public SwapViews.SwapOrderView settle(String orderNo, SwapRequests.Settle req, Long operatorId) {
        SwapOrder order = load(orderNo);
        requireStatus(order, SwapStatus.SWAPPING);

        BigDecimal actualKwh = req.actualKwh() != null ? req.actualKwh() : order.getEstKwh();
        SwapViews.QuoteView actual = billingService.calcBySnapshot(order.getPriceSnapshot(), actualKwh);

        Account master = accountService.getOrCreateUserAccount(order.getUserId());
        Account platform = accountService.getOrCreatePlatformAccount(AccountType.MASTER);
        BigDecimal diff = actual.total().subtract(order.getEstTotal());

        if (diff.compareTo(BigDecimal.ZERO) > 0) {
            if (master.getBalance().compareTo(diff) < 0) {
                throw BizException.of(42260, "error.swap.balance.insufficient");
            }
            ledgerService.postEntries(BizType.SWAP_PAY, orderNo + ":SETTLE", List.of(
                    new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D, diff, "换电结算补差"),
                    new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.C, diff, "换电结算补差")));
        } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal refund = diff.abs();
            ledgerService.postEntries(BizType.SWAP_PAY, orderNo + ":SETTLE", List.of(
                    new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.D, refund, "换电结算退差"),
                    new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.C, refund, "换电结算退差")));
        }

        order.setActualKwh(actualKwh);
        order.setActualElecFee(actual.elecFee());
        order.setActualServiceFee(actual.serviceFee());
        order.setActualTotal(actual.total());
        order.setSocEnd(req.socEnd());
        order.setSettleStatus("SETTLED");
        order.setStatus(SwapStatus.SETTLED);
        order.setUpdatedAt(Instant.now());
        swapOrderRepository.save(order);
        event(orderNo, "SETTLED", operatorId, null);

        log.info("换电单 {} 结算：{} 度 ${}（预估 ${}，{}）", orderNo, actualKwh, actual.total(),
                order.getEstTotal(), diff.compareTo(BigDecimal.ZERO) >= 0 ? "补差" : "退差");
        return toView(order);
    }

    // ------------------------------------------------------------------
    // 4. 取消：押金解冻 + 预扣退回
    // ------------------------------------------------------------------
    @Transactional
    public SwapViews.SwapOrderView cancel(String orderNo, String reason, Long operatorId) {
        SwapOrder order = load(orderNo);
        requireStatus(order, SwapStatus.FROZEN);

        Account master = accountService.getOrCreateUserAccount(order.getUserId());
        Account locked = accountService.getOrCreateSubAccount(order.getUserId(), AccountType.DEPOSIT_LOCKED);
        Account platform = accountService.getOrCreatePlatformAccount(AccountType.MASTER);

        if (order.getBatteryDeposit().signum() > 0) {
            ledgerService.postEntries(BizType.DEPOSIT_HOLD, orderNo + ":CANCEL", List.of(
                    new LedgerRequests.Entry(locked.getId(), LedgerRequests.Direction.D,
                            order.getBatteryDeposit(), "取消换电押金解冻"),
                    new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.C,
                            order.getBatteryDeposit(), "取消换电押金解冻")));
        }
        ledgerService.postEntries(BizType.SWAP_PAY, orderNo + ":CANCEL", List.of(
                new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.D,
                        order.getEstTotal(), "取消换电预扣退回"),
                new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.C,
                        order.getEstTotal(), "取消换电预扣退回")));

        order.setStatus(SwapStatus.CANCELLED);
        order.setCancelReason(reason);
        order.setSettleStatus("REFUNDED");
        order.setUpdatedAt(Instant.now());
        swapOrderRepository.save(order);
        event(orderNo, "CANCELLED", operatorId, null);

        log.info("换电单 {} 取消：押金 ${} 预扣 ${} 退回，原因={}", orderNo, order.getBatteryDeposit(),
                order.getEstTotal(), reason);
        return toView(order);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public SwapViews.SwapOrderView get(String orderNo) {
        return toView(load(orderNo));
    }

    @Transactional(readOnly = true)
    public List<SwapViews.SwapOrderView> listByUser(Long userId) {
        return swapOrderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<SwapViews.EventView> events(String orderNo) {
        load(orderNo);
        return eventRepository.findByOrderNoOrderByIdAsc(orderNo).stream()
                .map(e -> new SwapViews.EventView(e.getOrderNo(), e.getEvent(),
                        e.getOperatorId(), e.getPayload(), e.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------
    private SwapOrder load(String orderNo) {
        return swapOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("error.swap.order.not.found"));
    }

    private void requireStatus(SwapOrder order, SwapStatus expected) {
        if (order.getStatus() != expected) {
            throw BizException.of(40963, "error.swap.status");
        }
    }

    private void requireBattery(Long assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> BizException.notFound("error.asset.not.found"));
        if (asset.getAssetType() != AssetType.BATTERY) {
            throw BizException.of(42261, "error.swap.not.battery");
        }
    }

    private void event(String orderNo, String event, Long operatorId, String payload) {
        eventRepository.save(OrderEvent.builder()
                .orderNo(orderNo).event(event).operatorId(operatorId).payload(payload).build());
    }

    private SwapViews.SwapOrderView toView(SwapOrder o) {
        String stationName = stationRepository.findById(o.getStationId())
                .map(Station::getName).orElse(null);
        return new SwapViews.SwapOrderView(
                o.getOrderNo(), o.getUserId(), o.getStationId(), stationName,
                o.getVehicleId(), o.getBatteryOutId(), o.getBatteryInId(),
                o.getStatus(), o.getProtocolVer(),
                o.getBatteryDeposit(), o.getOldBatteryDeposit(),
                o.getEstKwh(), o.getEstElecFee(), o.getEstServiceFee(), o.getEstTotal(),
                o.getActualKwh(), o.getActualElecFee(), o.getActualServiceFee(), o.getActualTotal(),
                o.getSocStart(), o.getSocEnd(), o.getSettleStatus(), o.getCancelReason(),
                o.getCreatedAt(), o.getUpdatedAt());
    }
}
