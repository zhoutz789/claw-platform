package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 行为验证：项目层"分配不扣库存"（模块四 · BC-3），以及跨层只 ID 引用（BC-2）、越权 403（BC-5）。
 *
 * <p>纯 Mockito 单测，不依赖数据库：mock 三个仓储 + {@link StationScopeService}，
 * 直接对 {@link StationProjectService#alloc} / {@link StationProjectService#availableQty} 施压，
 * 用 {@code verify(stockRepository, never()).save(any())} 把"绝不回写 station_stock"钉死。
 */
@ExtendWith(MockitoExtension.class)
class StationProjectAllocDecouplingTest {

    private static final long STATION_ID = 7L;
    private static final long OTHER_STATION_ID = 8L;
    private static final long PROJECT_ID = 100L;
    private static final long STOCK_ID = 5001L;
    private static final int STOCK_QTY = 50;

    @Mock
    private StationProjectRepository projectRepository;
    @Mock
    private StationProjectInventoryAllocRepository allocRepository;
    @Mock
    private StationStockRepository stockRepository;
    @Mock
    private StationScopeService scopeService;

    @InjectMocks
    private StationProjectService projectService;

    private StationProject project(long stationId) {
        return StationProject.builder().id(PROJECT_ID).stationId(stationId).ownerUserId(1L)
                .name("清迈站-Q3 投放").parentId(null).depth(0).sortNo(0).status("ACTIVE").build();
    }

    private StationStock stock(int qty) {
        return StationStock.builder().id(STOCK_ID).stationId(STATION_ID).skuCode("SKU-A").stockQty(qty).build();
    }

    /* ============================ BC-3 核心 ============================ */

    @Test
    @DisplayName("BC-3：alloc 后 StationStockRepository.save 从未被调用，且 alloc 仓储 save 恰好一次")
    void alloc_neverSavesStock_andSavesAllocOnce() {
        StationStock stock = stock(STOCK_QTY);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(STATION_ID)));
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock));
        when(allocRepository.findByStationProjectIdAndStationStockId(PROJECT_ID, STOCK_ID))
                .thenReturn(Optional.empty());
        when(allocRepository.save(any(StationProjectInventoryAlloc.class)))
                .thenAnswer(inv -> {
                    StationProjectInventoryAlloc a = inv.getArgument(0);
                    a.setId(9001L);
                    return a;
                });
        when(allocRepository.findByStationStockId(STOCK_ID))
                .thenReturn(List.of(alloc(9001L, 12)));

        StationViews.StationProjectAllocView view =
                projectService.alloc(PROJECT_ID, new StationRequests.StationProjectAlloc(STOCK_ID, 12, "首批占用"));

        // ① 绝不回写库存
        verify(stockRepository, never()).save(any());
        verify(stockRepository, never()).saveAll(any());
        verify(stockRepository, never()).delete(any());
        // ② 库存仓储只被"读"了（alloc 校验 + availableQty 各一次 findById）
        verify(stockRepository, times(2)).findById(STOCK_ID);
        verifyNoMoreInteractions(stockRepository);
        // ③ 只写 alloc 表，且恰好一次
        verify(allocRepository, times(1)).save(any(StationProjectInventoryAlloc.class));
        // ④ 内存中的 stock 实体数量也没被改（防止"改实体不 save 但同事务 dirty checking 落库"）
        assertEquals(STOCK_QTY, stock.getStockQty(), "station_stock.stock_qty 必须保持 50 不变");

        assertEquals(12, view.allocatedQty());
        assertEquals(STOCK_QTY - 12, view.availableQty(), "可用量 = stock_qty - Σallocated = 50 - 12");
    }

    @Test
    @DisplayName("BC-3：可用量 = stock_qty − Σallocated（多项目占用累加），只读不写")
    void availableQty_isStockMinusSumOfAllocations() {
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock(STOCK_QTY)));
        when(allocRepository.findByStationStockId(STOCK_ID))
                .thenReturn(List.of(alloc(1L, 10), alloc(2L, 15), alloc(3L, 5)));

        int available = projectService.availableQty(STOCK_ID);

        assertEquals(50 - (10 + 15 + 5), available, "50 - 30 = 20");
        verify(stockRepository, never()).save(any());
        verify(allocRepository, never()).save(any());
        // 修复 M4-03 后 availableQty 需按项目状态判归档，故会只读 projectRepository（设计 §2.3 必需）；
        // 三个占用均为孤儿(项目记录缺失) → 按"未归档"保守计入，故仍为 50 - 30 = 20。
        // BC-3 核心契约（alloc 不写库存、可用量读时算）不变：
        verify(projectRepository, atLeastOnce()).findById(any());
        verifyNoInteractions(scopeService);
    }

    @Test
    @DisplayName("BC-3：重复占用同一库存行只累加 allocatedQty，仍不触碰 station_stock")
    void alloc_repeatAccumulatesQty_stillNoStockWrite() {
        StationProjectInventoryAlloc existing = alloc(9001L, 12);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(STATION_ID)));
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock(STOCK_QTY)));
        when(allocRepository.findByStationProjectIdAndStationStockId(PROJECT_ID, STOCK_ID))
                .thenReturn(Optional.of(existing));
        when(allocRepository.save(any(StationProjectInventoryAlloc.class))).thenAnswer(inv -> inv.getArgument(0));
        when(allocRepository.findByStationStockId(STOCK_ID)).thenReturn(List.of(existing));

        StationViews.StationProjectAllocView view =
                projectService.alloc(PROJECT_ID, new StationRequests.StationProjectAlloc(STOCK_ID, 8, null));

        ArgumentCaptor<StationProjectInventoryAlloc> captor =
                ArgumentCaptor.forClass(StationProjectInventoryAlloc.class);
        verify(allocRepository).save(captor.capture());
        assertEquals(20, captor.getValue().getAllocatedQty(), "12 + 8 = 20");
        assertEquals(20, view.allocatedQty());
        assertEquals(30, view.availableQty(), "50 - 20 = 30");
        verify(stockRepository, never()).save(any());
    }

    @Test
    @DisplayName("BC-3：解除占用只删 alloc 行，完全不触碰 StationStockRepository")
    void dealloc_onlyDeletesAllocRow() {
        StationProjectInventoryAlloc existing = alloc(9001L, 12);
        when(allocRepository.findById(9001L)).thenReturn(Optional.of(existing));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(STATION_ID)));
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));

        projectService.dealloc(9001L);

        verify(allocRepository).deleteById(9001L);
        verifyNoInteractions(stockRepository);
    }

    /* ============================ BC-2：跨层只 ID 引用 ============================ */

    @Test
    @DisplayName("BC-2：写入 alloc 的实体只携带 stationStockId（Long ID），不含任何 StationStock 实体引用")
    void alloc_persistsOnlyStockIdReference() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(STATION_ID)));
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock(STOCK_QTY)));
        when(allocRepository.findByStationProjectIdAndStationStockId(PROJECT_ID, STOCK_ID))
                .thenReturn(Optional.empty());
        when(allocRepository.save(any(StationProjectInventoryAlloc.class))).thenAnswer(inv -> inv.getArgument(0));
        when(allocRepository.findByStationStockId(STOCK_ID)).thenReturn(List.of());

        projectService.alloc(PROJECT_ID, new StationRequests.StationProjectAlloc(STOCK_ID, 3, null));

        ArgumentCaptor<StationProjectInventoryAlloc> captor =
                ArgumentCaptor.forClass(StationProjectInventoryAlloc.class);
        verify(allocRepository).save(captor.capture());
        StationProjectInventoryAlloc saved = captor.getValue();
        assertEquals(STOCK_ID, saved.getStationStockId());
        assertEquals(PROJECT_ID, saved.getStationProjectId());
        assertEquals("SKU-A", saved.getSkuCode());
        // 实体上不存在任何 StationStock / StationProject 类型的字段（只有 Long ID）
        assertTrue(java.util.Arrays.stream(StationProjectInventoryAlloc.class.getDeclaredFields())
                        .noneMatch(f -> f.getType() == StationStock.class || f.getType() == StationProject.class),
                "alloc 实体不得持有对方实体引用，只能是 Long ID");
    }

    /* ============================ BC-5：越权 403 ============================ */

    @Test
    @DisplayName("BC-5：服务站 A 用户对属于站 B 的项目分配库存 → BizException 40301，且不产生任何写入")
    void alloc_crossStationForbidden() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(OTHER_STATION_ID)));
        when(scopeService.allowedStationIds(OTHER_STATION_ID)).thenReturn(List.of(STATION_ID));

        BizException ex = assertThrows(BizException.class,
                () -> projectService.alloc(PROJECT_ID, new StationRequests.StationProjectAlloc(STOCK_ID, 5, null)));

        assertEquals(40301, ex.getCode());
        assertEquals("station.scope.forbidden", ex.getMessageCode());
        verifyNoInteractions(allocRepository, stockRepository);
    }

    @Test
    @DisplayName("BC-5：scope 解析层抛出的 40301（越权覆盖）原样透传，不被吞掉")
    void alloc_scopeServiceForbiddenPropagates() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(OTHER_STATION_ID)));
        when(scopeService.allowedStationIds(OTHER_STATION_ID))
                .thenThrow(BizException.of(40301, "inventory.scope.forbidden"));

        BizException ex = assertThrows(BizException.class,
                () -> projectService.alloc(PROJECT_ID, new StationRequests.StationProjectAlloc(STOCK_ID, 5, null)));

        assertEquals(40301, ex.getCode());
        verifyNoInteractions(allocRepository, stockRepository);
    }

    @Test
    @DisplayName("参数校验：占用的库存行不属于本项目所在站 → 10001 invalid param，且不写 alloc")
    void alloc_stockStationMismatchRejected() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(STATION_ID)));
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(
                StationStock.builder().id(STOCK_ID).stationId(OTHER_STATION_ID).skuCode("SKU-A").stockQty(9).build()));

        BizException ex = assertThrows(BizException.class,
                () -> projectService.alloc(PROJECT_ID, new StationRequests.StationProjectAlloc(STOCK_ID, 5, null)));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("station.project.alloc.station.mismatch", ex.getMessageCode());
        verify(allocRepository, never()).save(any());
        verify(stockRepository, never()).save(any());
    }

    /* ============================ BC-4：项目层列表独立 ============================ */

    @Test
    @DisplayName("BC-4：项目树查询只读 station_projects，不 JOIN 库存/占用基表（库存层挂了也能查）")
    void listTree_touchesOnlyProjectRepository() {
        when(scopeService.allowedStationIds(null)).thenReturn(List.of(STATION_ID));
        when(projectRepository.findByStationIdIn(List.of(STATION_ID))).thenReturn(List.of(
                project(STATION_ID),
                StationProject.builder().id(101L).stationId(STATION_ID).ownerUserId(1L).name("子项目")
                        .parentId(PROJECT_ID).depth(1).sortNo(0).status("ACTIVE").build()));

        List<StationViews.StationProjectTreeNode> tree =
                projectService.listTree(scopeService.allowedStationIds(null));

        assertEquals(1, tree.size(), "根节点 1 个");
        assertEquals(1, tree.get(0).children().size(), "子节点 1 个");
        verifyNoInteractions(stockRepository, allocRepository);
    }

    @Test
    @DisplayName("BC-5：allowedStationIds 为空集（未绑定主体）→ 项目树返回空，不查库")
    void listTree_emptyScopeReturnsEmpty() {
        assertTrue(projectService.listTree(List.of()).isEmpty());
        verifyNoInteractions(projectRepository, stockRepository, allocRepository);
    }

    private StationProjectInventoryAlloc alloc(long id, int qty) {
        return StationProjectInventoryAlloc.builder()
                .id(id).stationProjectId(PROJECT_ID).stationStockId(STOCK_ID)
                .skuCode("SKU-A").allocatedQty(qty).build();
    }
}
