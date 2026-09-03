package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 行为验证：库存层写事务封闭 + 作用域过滤（模块四 · BC-1 / BC-4 / BC-5）。
 *
 * <p>库存层结构上只持有 {@code StationStockRepository} + {@code StationInventoryMovementRepository}
 * （见 {@link StationLayerDecouplingTest}），本测试补齐行为面：
 * 入站/调整在同一事务里维护 {@code stock_qty} 并写流水，且列表查询严格按 allowedStationIds 过滤。
 */
@ExtendWith(MockitoExtension.class)
class StationInventoryServiceTest {

    private static final long STATION_A = 7L;
    private static final long STATION_B = 8L;

    @Mock
    private StationStockRepository stockRepository;
    @Mock
    private StationInventoryMovementRepository movementRepository;

    @InjectMocks
    private StationInventoryService inventoryService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private StationStock stock(long id, long stationId, String sku, int qty) {
        return StationStock.builder().id(id).stationId(stationId).skuCode(sku).stockQty(qty).build();
    }

    /* ===================== BC-1：库存写只落本层两张表 ===================== */

    @Test
    @DisplayName("BC-1：入站 +10 → stock_qty 由 5 变 15，同时写 1 条 INBOUND 流水，无其它仓储交互")
    void inbound_updatesStockAndWritesMovement() {
        when(stockRepository.findByStationIdAndSkuCode(STATION_A, "SKU-A"))
                .thenReturn(Optional.of(stock(1L, STATION_A, "SKU-A", 5)));
        when(stockRepository.save(any(StationStock.class))).thenAnswer(inv -> inv.getArgument(0));

        StationViews.StationInventoryStockView view = inventoryService.inbound(STATION_A, "SKU-A", 10);

        assertEquals(15, view.stockQty(), "5 + 10 = 15");
        verify(stockRepository, times(1)).save(any(StationStock.class));

        ArgumentCaptor<StationInventoryMovement> captor = ArgumentCaptor.forClass(StationInventoryMovement.class);
        verify(movementRepository, times(1)).save(captor.capture());
        StationInventoryMovement m = captor.getValue();
        assertEquals(STATION_A, m.getStationId());
        assertEquals("SKU-A", m.getSkuCode());
        assertEquals(10, m.getDeltaQty());
        assertEquals("INBOUND", m.getReason());
    }

    @Test
    @DisplayName("BC-1：库存行不存在时入站自动建行（0 + 20 = 20），仍只写本层两张表")
    void inbound_createsStockRowWhenAbsent() {
        when(stockRepository.findByStationIdAndSkuCode(STATION_A, "SKU-NEW")).thenReturn(Optional.empty());
        when(stockRepository.save(any(StationStock.class))).thenAnswer(inv -> inv.getArgument(0));

        StationViews.StationInventoryStockView view = inventoryService.inbound(STATION_A, "SKU-NEW", 20);

        assertEquals(20, view.stockQty());
        verify(movementRepository).save(any(StationInventoryMovement.class));
    }

    @Test
    @DisplayName("BC-1：盘点调整 −3（CONSUME）→ stock_qty 由 5 变 2，流水 reason 归一化为大写")
    void adjust_negativeDeltaWritesConsumeMovement() {
        when(stockRepository.findByStationIdAndSkuCode(STATION_A, "SKU-A"))
                .thenReturn(Optional.of(stock(1L, STATION_A, "SKU-A", 5)));
        when(stockRepository.save(any(StationStock.class))).thenAnswer(inv -> inv.getArgument(0));

        StationViews.StationInventoryStockView view = inventoryService.adjust(STATION_A, "SKU-A", -3, "consume");

        assertEquals(2, view.stockQty());
        ArgumentCaptor<StationInventoryMovement> captor = ArgumentCaptor.forClass(StationInventoryMovement.class);
        verify(movementRepository).save(captor.capture());
        assertEquals(-3, captor.getValue().getDeltaQty(), "负 delta 即消耗，是结算层的数据源");
        assertEquals("CONSUME", captor.getValue().getReason());
    }

    @Test
    @DisplayName("参数校验：调整量为 0 → 10001，且不写 stock、不写流水")
    void adjust_zeroDeltaRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> inventoryService.adjust(STATION_A, "SKU-A", 0, null));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("station.inventory.adjust.zero", ex.getMessageCode());
        verifyNoInteractions(stockRepository, movementRepository);
    }

    /* ===================== BC-4 / BC-5：列表独立 + 作用域过滤 ===================== */

    @Test
    @DisplayName("BC-5：库存列表只返回 allowedStationIds 内的行（站 A 用户看不到站 B 的库存）")
    void listStock_filtersByAllowedStationIds() {
        when(stockRepository.findAll()).thenReturn(List.of(
                stock(1L, STATION_A, "SKU-A", 5),
                stock(2L, STATION_B, "SKU-A", 99),
                stock(3L, STATION_A, "SKU-B", 7)));

        List<StationViews.StationInventoryStockView> out = inventoryService.listStock(List.of(STATION_A), null);

        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(v -> v.stationId() == STATION_A), "不得泄漏站 B 的库存行");
    }

    @Test
    @DisplayName("BC-5：allowedStationIds=null（平台管理员）→ 返回全平台库存；空集（未绑定）→ 返回空且不查库")
    void listStock_platformAdminAndEmptyScope() {
        when(stockRepository.findAll()).thenReturn(List.of(
                stock(1L, STATION_A, "SKU-A", 5), stock(2L, STATION_B, "SKU-A", 99)));
        assertEquals(2, inventoryService.listStock(null, null).size());

        assertTrue(inventoryService.listStock(List.of(), null).isEmpty());
        verify(stockRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("BC-4：库存列表与流水查询完全不依赖项目/结算层（屏蔽这两层数据仍正常返回）")
    void listStock_isIndependentOfProjectAndSettlementLayers() {
        when(stockRepository.findAll()).thenReturn(List.of(stock(1L, STATION_A, "SKU-A", 5)));

        // 库存层结构上根本拿不到项目/结算仓储：注入字段里只有本层两个（见 StationLayerDecouplingTest）
        assertEquals(1, inventoryService.listStock(List.of(STATION_A), null).size());
        verify(movementRepository, never()).findAll();
    }

    @Test
    @DisplayName("BC-4/BC-5：流水查询按站过滤 + skuCode 精确过滤，按时间倒序")
    void listMovements_filtersByStationAndSku() {
        when(movementRepository.findByStationIdInOrderByCreatedAtDesc(List.of(STATION_A)))
                .thenReturn(List.of(
                        movement(1L, STATION_A, "SKU-A", -3, "2026-07-01T00:00:00Z"),
                        movement(2L, STATION_A, "SKU-B", 10, "2026-07-02T00:00:00Z"),
                        movement(3L, STATION_A, "SKU-A", 20, "2026-07-03T00:00:00Z")));

        List<StationViews.StationInventoryMovementView> out =
                inventoryService.listMovements(List.of(STATION_A), "SKU-A");

        assertEquals(2, out.size(), "只保留 SKU-A 的两条");
        assertEquals(3L, out.get(0).id(), "时间倒序：07-03 在前");
        assertEquals(1L, out.get(1).id());
        verifyNoInteractions(stockRepository);
    }

    @Test
    @DisplayName("BC-5：流水查询 allowedStationIds 为空集 → 空结果，不查库")
    void listMovements_emptyScopeReturnsEmpty() {
        assertTrue(inventoryService.listMovements(List.of(), null).isEmpty());
        verifyNoInteractions(stockRepository, movementRepository);
    }

    private StationInventoryMovement movement(long id, long stationId, String sku, int delta, String at) {
        return StationInventoryMovement.builder()
                .id(id).stationId(stationId).skuCode(sku).deltaQty(delta).reason(delta < 0 ? "CONSUME" : "INBOUND")
                .createdAt(java.time.Instant.parse(at)).build();
    }
}
