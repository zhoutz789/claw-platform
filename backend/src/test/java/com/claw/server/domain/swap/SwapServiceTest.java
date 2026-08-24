package com.claw.server.domain.swap;

import com.claw.server.common.dto.SwapRequests;
import com.claw.server.common.dto.SwapViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 换电状态机单元测试：创建冻结 / 双向押金流转 / 结算多退少补 / 取消解冻。
 */
@ExtendWith(MockitoExtension.class)
class SwapServiceTest {

    @Mock private SwapOrderRepository swapOrderRepository;
    @Mock private OrderEventRepository eventRepository;
    @Mock private StationRepository stationRepository;
    @Mock private StationBatteryRepository stationBatteryRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private BatteryDetailRepository batteryDetailRepository;
    @Mock private AccountService accountService;
    @Mock private LedgerService ledgerService;
    @Mock private SwapBillingService billingService;
    @InjectMocks private SwapService service;

    private Station activeStation() {
        Station s = mock(Station.class);
        lenient().when(s.getId()).thenReturn(1L);
        lenient().when(s.getStatus()).thenReturn("ACTIVE");
        lenient().when(s.getName()).thenReturn("中央市场站");
        return s;
    }

    private StationBattery readySlot(Long batteryId, int slot) {
        return StationBattery.builder().id((long) slot).stationId(1L)
                .batteryId(batteryId).slotNo(slot).status("READY")
                .soc(new BigDecimal("100.00")).build();
    }

    private Asset batteryAsset(Long id, String no) {
        return Asset.builder().id(id).assetNo(no).assetType(AssetType.BATTERY)
                .status(AssetStatus.IN_STOCK).build();
    }

    private Account account(Long id, String balance) {
        return Account.builder().id(id).balance(new BigDecimal(balance)).build();
    }

    private SwapViews.QuoteView quote() {
        return new SwapViews.QuoteView(new BigDecimal("0.12"), new BigDecimal("0.32"),
                new BigDecimal("0.44"), new BigDecimal("2.00"),
                new BigDecimal("0.24"), new BigDecimal("0.64"), new BigDecimal("0.88"));
    }

    private void stubCreateBasics() {
        Station station = activeStation();
        lenient().when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
        lenient().when(stationBatteryRepository.findByStationIdOrderBySlotNoAsc(1L))
                .thenReturn(List.of(readySlot(10L, 1), readySlot(11L, 2)));
        lenient().when(assetRepository.findById(10L)).thenReturn(Optional.of(batteryAsset(10L, "BAT-PP-001")));
        lenient().when(assetRepository.findById(20L)).thenReturn(Optional.of(batteryAsset(20L, "BAT-PP-007")));

        BatteryDetail out = new BatteryDetail();
        out.setAssetId(10L); out.setProtocolVer("P1"); out.setDepositValue(new BigDecimal("40.00"));
        lenient().when(batteryDetailRepository.findByAssetId(10L)).thenReturn(Optional.of(out));
        BatteryDetail in = new BatteryDetail();
        in.setAssetId(20L); in.setProtocolVer("P1"); in.setDepositValue(new BigDecimal("36.00"));
        lenient().when(batteryDetailRepository.findByAssetId(20L)).thenReturn(Optional.of(in));

        lenient().when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(1L, "100.00"));
        lenient().when(accountService.getOrCreateSubAccount(100L, AccountType.DEPOSIT_LOCKED)).thenReturn(account(2L, "0.00"));
        lenient().when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(3L, "0.00"));
        lenient().when(billingService.quote(any(BigDecimal.class))).thenReturn(quote());
        lenient().when(billingService.snapshotJson()).thenReturn("{\"elecRate\":0.12,\"serviceRate\":0.32}");
        lenient().when(swapOrderRepository.save(any(SwapOrder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------
    @Test
    void create_freezes_deposit_and_preauth_fee() {
        stubCreateBasics();

        SwapRequests.Create req = new SwapRequests.Create(1L, null, 20L, "P1", new BigDecimal("2.00"));
        SwapViews.SwapOrderView v = service.create(req, 100L);

        assertEquals(SwapStatus.FROZEN, v.status());
        assertEquals("SW-", v.orderNo().substring(0, 3));
        assertEquals(0, v.batteryDeposit().compareTo(new BigDecimal("40.00")));   // B_new 押金（动态残值）
        assertEquals(0, v.oldBatteryDeposit().compareTo(new BigDecimal("36.00"))); // B_old 押金待退
        assertEquals(0, v.estTotal().compareTo(new BigDecimal("0.88")));
        // 押金冻结 + 预扣两笔复式记账
        verify(ledgerService, times(2)).postEntries(any(), anyString(), anyList());
        verify(swapOrderRepository).save(any(SwapOrder.class));
        verify(eventRepository).save(any(OrderEvent.class));
    }

    @Test
    void create_rejects_when_balance_insufficient() {
        stubCreateBasics();
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(1L, "1.00")); // 押金+预扣 > 余额

        SwapRequests.Create req = new SwapRequests.Create(1L, null, null, null, new BigDecimal("2.00"));
        assertThrows(Exception.class, () -> service.create(req, 100L));
        verify(ledgerService, never()).postEntries(any(), anyString(), anyList());
    }

    @Test
    void create_rejects_when_no_ready_battery() {
        Station station = activeStation();
        when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
        StationBattery charging = StationBattery.builder().id(1L).stationId(1L)
                .batteryId(10L).slotNo(1).status("CHARGING").build();
        when(stationBatteryRepository.findByStationIdOrderBySlotNoAsc(1L))
                .thenReturn(List.of(charging));

        assertThrows(Exception.class,
                () -> service.create(new SwapRequests.Create(1L, null, null, null, null), 100L));
    }

    @Test
    void create_rejects_protocol_mismatch() {
        Station station = activeStation();
        when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
        when(stationBatteryRepository.findByStationIdOrderBySlotNoAsc(1L))
                .thenReturn(List.of(readySlot(10L, 1)));
        when(assetRepository.findById(10L)).thenReturn(Optional.of(batteryAsset(10L, "BAT-PP-001")));
        BatteryDetail out = new BatteryDetail();
        out.setAssetId(10L); out.setProtocolVer("P2"); out.setDepositValue(new BigDecimal("40.00"));
        when(batteryDetailRepository.findByAssetId(10L)).thenReturn(Optional.of(out));

        assertThrows(Exception.class,
                () -> service.create(new SwapRequests.Create(1L, null, null, "P1", null), 100L));
    }

    // ------------------------------------------------------------------
    // confirm 双向押金流转
    // ------------------------------------------------------------------
    @Test
    void confirm_refunds_old_deposit_and_swaps_management() {
        SwapOrder order = SwapOrder.builder()
                .orderNo("SW-C1").userId(100L).stationId(1L)
                .batteryOutId(10L).batteryInId(20L)
                .status(SwapStatus.FROZEN)
                .batteryDeposit(new BigDecimal("40.00"))
                .oldBatteryDeposit(new BigDecimal("36.00"))
                .estTotal(new BigDecimal("0.88"))
                .build();
        when(swapOrderRepository.findByOrderNo("SW-C1")).thenReturn(Optional.of(order));
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(1L, "50.00"));
        when(accountService.getOrCreateSubAccount(100L, AccountType.DEPOSIT_LOCKED)).thenReturn(account(2L, "40.00"));

        Asset out = batteryAsset(10L, "BAT-PP-001");
        Asset in = batteryAsset(20L, "BAT-PP-007");
        when(assetRepository.findById(10L)).thenReturn(Optional.of(out));
        when(assetRepository.findById(20L)).thenReturn(Optional.of(in));

        StationBattery outSlot = readySlot(10L, 1);
        StationBattery inSlot = StationBattery.builder().id(9L).stationId(1L)
                .batteryId(20L).slotNo(3).status("OUT").build();
        when(stationBatteryRepository.findByBatteryId(10L)).thenReturn(Optional.of(outSlot));
        when(stationBatteryRepository.findByBatteryId(20L)).thenReturn(Optional.of(inSlot));

        SwapViews.SwapOrderView v = service.confirm("SW-C1", 200L);

        assertEquals(SwapStatus.SWAPPING, v.status());
        // 旧电池押金退还分录（locked → master）
        verify(ledgerService).postEntries(eq(com.claw.server.common.enums.BizType.DEPOSIT_HOLD),
                eq("SW-C1:SWAP"), anyList());
        // 管理权切换
        assertEquals(100L, out.getUserId());
        assertEquals(AssetStatus.IN_USE, out.getStatus());
        assertEquals(200L, in.getUserId());
        // 电池位
        assertEquals("OUT", outSlot.getStatus());
        assertEquals("CHARGING", inSlot.getStatus());
    }

    // ------------------------------------------------------------------
    // settle 多退少补
    // ------------------------------------------------------------------
    private SwapOrder swappingOrder(String no, String estTotal) {
        return SwapOrder.builder()
                .orderNo(no).userId(100L).stationId(1L)
                .batteryOutId(10L).batteryInId(20L)
                .status(SwapStatus.SWAPPING)
                .batteryDeposit(new BigDecimal("40.00"))
                .oldBatteryDeposit(new BigDecimal("36.00"))
                .estTotal(new BigDecimal(estTotal))
                .priceSnapshot("{\"elecRate\":0.12,\"serviceRate\":0.32}")
                .build();
    }

    @Test
    void settle_refunds_when_actual_less_than_est() {
        SwapOrder order = swappingOrder("SW-S1", "1.00");   // 预估 1.00，实际 0.88
        when(swapOrderRepository.findByOrderNo("SW-S1")).thenReturn(Optional.of(order));
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(1L, "50.00"));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(3L, "10.00"));
        when(billingService.calcBySnapshot(anyString(), any(BigDecimal.class))).thenReturn(quote());

        SwapViews.SwapOrderView v = service.settle("SW-S1",
                new SwapRequests.Settle(new BigDecimal("2.00"), new BigDecimal("30.00")), 200L);

        assertEquals(SwapStatus.SETTLED, v.status());
        assertEquals("SETTLED", v.settleStatus());
        assertEquals(0, v.actualTotal().compareTo(new BigDecimal("0.88")));
        // 退差 0.12（平台 → 用户）
        verify(ledgerService).postEntries(eq(com.claw.server.common.enums.BizType.SWAP_PAY),
                eq("SW-S1:SETTLE"), anyList());
    }

    @Test
    void settle_charges_more_when_actual_exceeds_est() {
        SwapOrder order = swappingOrder("SW-S2", "0.50");
        when(swapOrderRepository.findByOrderNo("SW-S2")).thenReturn(Optional.of(order));
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(1L, "50.00"));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(3L, "10.00"));
        when(billingService.calcBySnapshot(anyString(), any(BigDecimal.class))).thenReturn(quote());

        service.settle("SW-S2", new SwapRequests.Settle(new BigDecimal("2.00"), null), 200L);

        // 补差 0.38（用户 → 平台）
        verify(ledgerService).postEntries(eq(com.claw.server.common.enums.BizType.SWAP_PAY),
                eq("SW-S2:SETTLE"), anyList());
    }

    // ------------------------------------------------------------------
    // cancel 解冻
    // ------------------------------------------------------------------
    @Test
    void cancel_unfreezes_deposit_and_refunds_preauth() {
        SwapOrder order = swappingOrder("SW-X", "0.88");
        order.setStatus(SwapStatus.FROZEN);
        when(swapOrderRepository.findByOrderNo("SW-X")).thenReturn(Optional.of(order));
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(1L, "50.00"));
        when(accountService.getOrCreateSubAccount(100L, AccountType.DEPOSIT_LOCKED)).thenReturn(account(2L, "40.00"));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(3L, "10.00"));

        SwapViews.SwapOrderView v = service.cancel("SW-X", "改了主意", 100L);

        assertEquals(SwapStatus.CANCELLED, v.status());
        assertEquals("REFUNDED", v.settleStatus());
        // 押金解冻 + 预扣退回
        verify(ledgerService).postEntries(eq(com.claw.server.common.enums.BizType.DEPOSIT_HOLD),
                eq("SW-X:CANCEL"), anyList());
        verify(ledgerService).postEntries(eq(com.claw.server.common.enums.BizType.SWAP_PAY),
                eq("SW-X:CANCEL"), anyList());
    }

    @Test
    void cancel_rejects_when_not_frozen() {
        SwapOrder order = swappingOrder("SW-Y", "0.88");   // status=SWAPPING
        when(swapOrderRepository.findByOrderNo("SW-Y")).thenReturn(Optional.of(order));

        assertThrows(Exception.class, () -> service.cancel("SW-Y", null, 100L));
        verify(ledgerService, never()).postEntries(any(), anyString(), anyList());
    }
}
