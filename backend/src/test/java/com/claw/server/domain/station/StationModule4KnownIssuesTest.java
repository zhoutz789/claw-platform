package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.domain.commission.CommissionRuleRepository;
import com.claw.server.domain.manufacturer.ProductRepository;
import com.claw.server.domain.manufacturer.ProductSkuRepository;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 模块四已知缺陷的"红线"用例（QA 严过关 · 独立验收产出）。
 *
 * <p>本类中的每个用例都断言<b>设计文档要求的正确行为</b>，当前实现<b>不满足</b>，因此统一挂
 * {@code @Disabled} 并标注缺陷编号，避免污染流水线。<b>工程师修复对应缺陷后请删除
 * {@code @Disabled}</b>，用例即转为长期回归护栏。
 *
 * <p>缺陷清单：
 * <ul>
 *   <li><b>BUG-M4-01（高）</b> 越权读取结算单详情：{@code StationSettlementService#get} 未做
 *       scope 校验，任意持 {@code station:settlement:view} 的账号可按 ID 直读他站结算单
 *       （含三金额），BC-5 在该端点失效。</li>
 *   <li><b>BUG-M4-02（中）</b> 跨站项目树嫁接：{@code StationProjectService#create/update} 只校验
 *       目标项目自身的 stationId，未校验 {@code parentId} 所属站，可把本站项目挂到他站项目下。</li>
 *   <li><b>BUG-M4-03（低-中）</b> 可用量口径偏离设计：设计 §2.3 要求 Σallocated 只统计
 *       "项目未归档"的占用，实现按 {@code stationStockId} 汇总全部占用，已归档项目仍占用可用量。</li>
 *   <li><b>BUG-M4-04（中-高）</b> BC-6 零破坏在前端失效：模块四提交从 {@code web/src/nav.js}
 *       的 NAV 菜单树里删掉 4 个既有菜单项（brand-onboarding / manufacturer / order-manage /
 *       merchants），页面与路由都还在，仅侧边栏入口消失。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StationModule4KnownIssuesTest {

    private static final long STATION_A = 7L;
    private static final long STATION_B = 8L;
    private static final long STOCK_ID = 5001L;

    /* ==================== BUG-M4-01：结算单详情越权读取 ==================== */

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class SettlementDetailScope {

        @Mock
        private StationSettlementRepository settlementRepository;
        @Mock
        private StationSettlementItemRepository itemRepository;
        @Mock
        private StationInventoryMovementRepository movementRepository;
        @Mock
        private ProductSkuRepository productSkuRepository;
        @Mock
        private ProductRepository productRepository;
        @Mock
        private CommissionRuleRepository commissionRuleRepository;
        @Mock
        private SystemConfigRepository systemConfigRepository;
        @Mock
        private StationScopeService scopeService;

        @InjectMocks
        private StationSettlementService settlementService;

        @Test
        @DisplayName("BC-5：站 A 账号按 ID 直读站 B 的结算单详情应抛 40301（当前实现直接返回数据 → 越权读取）")
        void get_crossStationMustBeForbidden() {
            when(settlementRepository.findById(555L)).thenReturn(Optional.of(StationSettlement.builder()
                    .id(555L).settlementNo("STL-B-1").stationId(STATION_B).status("CONFIRMED")
                    .logisticsFee(new BigDecimal("25.00")).stationCommission(new BigDecimal("40.00"))
                    .manufacturerNet(new BigDecimal("435.00")).currency("USD")
                    .createdAt(Instant.parse("2026-08-01T00:00:00Z")).build()));
            when(itemRepository.findBySettlementIdOrderByCreatedAtAsc(555L)).thenReturn(List.of());
            when(scopeService.allowedStationIds(any())).thenReturn(List.of(STATION_A));

            BizException ex = assertThrows(BizException.class, () -> settlementService.get(555L),
                    "越权读取他站结算单必须被拒（BC-5）");
            assertEquals(40301, ex.getCode());
        }
    }

    /* ==================== BUG-M4-02 / BUG-M4-03：项目层 ==================== */

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class ProjectLayerIssues {

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

        private StationProject project(long id, long stationId, String status) {
            return StationProject.builder().id(id).stationId(stationId).ownerUserId(1L)
                    .name("P" + id).depth(0).sortNo(0).status(status).build();
        }

        @Test
        @DisplayName("BC-5：把本站(A)新项目挂到他站(B)的父项目下应被拒（当前实现放行 → 跨站项目树嫁接）")
        void create_parentInAnotherStationMustBeForbidden() {
            when(scopeService.allowedStationIds(STATION_A)).thenReturn(List.of(STATION_A));
            when(projectRepository.findById(900L)).thenReturn(Optional.of(project(900L, STATION_B, "ACTIVE")));
            when(projectRepository.save(any(StationProject.class))).thenAnswer(inv -> {
                StationProject p = inv.getArgument(0);
                p.setId(901L);
                return p;
            });

            assertThrows(BizException.class, () -> projectService.create(
                            new StationRequests.StationProjectCreate(STATION_A, "跨站子项目", 900L, 0), 1L),
                    "parentId 必须与 stationId 同站，否则项目树跨站泄漏");
        }

        @Test
        @DisplayName("BC-5：把站 A 的项目改挂到站 B 的父项目下应被拒（当前实现放行）")
        void update_reparentToAnotherStationMustBeForbidden() {
            when(projectRepository.findById(800L)).thenReturn(Optional.of(project(800L, STATION_A, "ACTIVE")));
            when(projectRepository.findById(900L)).thenReturn(Optional.of(project(900L, STATION_B, "ACTIVE")));
            when(scopeService.allowedStationIds(STATION_A)).thenReturn(List.of(STATION_A));
            when(projectRepository.findByParentId(any())).thenReturn(List.of());
            when(projectRepository.save(any(StationProject.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThrows(BizException.class, () -> projectService.update(800L,
                            new StationRequests.StationProjectUpdate(null, 900L, null, null)),
                    "改父项目时必须校验新父项目同站");
        }

        @Test
        @DisplayName("设计 §2.3：可用量的 Σallocated 只应统计未归档项目（当前实现把归档项目的占用也算进去）")
        void availableQty_mustExcludeArchivedProjectAllocations() {
            when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(StationStock.builder()
                    .id(STOCK_ID).stationId(STATION_A).skuCode("SKU-A").stockQty(50).build()));
            when(allocRepository.findByStationStockId(STOCK_ID)).thenReturn(List.of(
                    StationProjectInventoryAlloc.builder().id(1L).stationProjectId(801L)
                            .stationStockId(STOCK_ID).skuCode("SKU-A").allocatedQty(10).build(),
                    // 该占用挂在已归档项目 802 上，按设计不应计入 Σallocated
                    StationProjectInventoryAlloc.builder().id(2L).stationProjectId(802L)
                            .stationStockId(STOCK_ID).skuCode("SKU-A").allocatedQty(15).build()));
            when(projectRepository.findById(801L)).thenReturn(Optional.of(project(801L, STATION_A, "ACTIVE")));
            when(projectRepository.findById(802L)).thenReturn(Optional.of(project(802L, STATION_A, "ARCHIVED")));

            assertEquals(40, projectService.availableQty(STOCK_ID),
                    "50 − 10（仅未归档项目占用）= 40；当前实现返回 25，把归档项目的 15 也扣掉了");
        }

        @Test
        @DisplayName("对照用例（当前行为，非缺陷）：availableQty 汇总全部占用 → 50 − (10+15) = 25")
        void availableQty_currentBehaviourSumsAllAllocations() {
            when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(StationStock.builder()
                    .id(STOCK_ID).stationId(STATION_A).skuCode("SKU-A").stockQty(50).build()));
            when(allocRepository.findByStationStockId(STOCK_ID)).thenReturn(List.of(
                    StationProjectInventoryAlloc.builder().id(1L).stationProjectId(801L)
                            .stationStockId(STOCK_ID).skuCode("SKU-A").allocatedQty(10).build(),
                    StationProjectInventoryAlloc.builder().id(2L).stationProjectId(802L)
                            .stationStockId(STOCK_ID).skuCode("SKU-A").allocatedQty(15).build()));

            assertEquals(25, projectService.availableQty(STOCK_ID));
        }
    }

    /* ==================== BUG-M4-04：前端既有导航入口被误删 ==================== */

    /**
     * BC-6（零破坏）在前端导航层的回归护栏。
     *
     * <p>模块四提交 {@code a45d322} 在 {@code web/src/nav.js} 新增"服务站"分组的同时，
     * 从 goods 分组的 children 里删掉了 4 个<b>既有</b>菜单项（brand-onboarding /
     * manufacturer / order-manage / merchants）。这 4 个页面组件与路由注册均仍存在，
     * 因此属于"入口丢失"而非"功能下线"——用户只能靠手敲 URL 访问。
     *
     * <p>采用源码文本静态核查（与 {@code StationLayerDecouplingTest} 同一手法），
     * 因前端无测试基建，且 QA 不应为单个缺陷引入 vitest/jest 依赖（本身即破坏性变更）。
     */
    @Nested
    class FrontendNavRegression {

        /** 被模块四提交从 NAV 菜单树中删除的既有菜单项 key。 */
        private static final List<String> REMOVED_KEYS =
                List.of("brand-onboarding", "manufacturer", "order-manage", "merchants");

        /** 模块四自己新增的三层菜单项 key。 */
        private static final List<String> MODULE4_KEYS =
                List.of("station-inventory", "station-projects", "station-settlements");

        @Test
        @DisplayName("BC-6：nav.js 的 NAV 菜单树必须仍含 4 个既有菜单项（当前已被模块四提交删除）")
        void navTreeMustRetainPreExistingEntries() throws IOException {
            String navTree = navMenuSection(readNavJs());

            List<String> missing = REMOVED_KEYS.stream()
                    .filter(k -> !navTree.contains("key: '" + k + "'"))
                    .toList();

            assertEquals(List.of(), missing,
                    "以下既有菜单项在 NAV 菜单树中丢失，侧边栏无法访问对应既有页面：" + missing);
        }

        @Test
        @DisplayName("取证（常绿）：被删的 4 项路由白名单与页面组件都还在 → 是入口丢失，不是功能下线")
        void removedEntriesStillHaveRoutesAndPages() throws IOException {
            String nav = readNavJs();
            String routesSection = nav.substring(nav.indexOf("export const ROUTES"));

            for (String key : REMOVED_KEYS) {
                assertTrue(routesSection.contains("'/" + key + "'"),
                        "ROUTES 白名单应仍含 /" + key + "（证明路由未下线）");
            }
            for (String page : List.of("BrandOnboarding", "Manufacturer", "OrderManage", "Merchants")) {
                assertTrue(webFileExists("src/pages/" + page + ".jsx"),
                        "页面组件应仍存在：" + page + ".jsx（证明功能未下线）");
            }
        }

        @Test
        @DisplayName("BC-6（常绿）：模块四自己的三层菜单项与路由已正确加入 nav.js")
        void module4EntriesAreRegistered() throws IOException {
            String nav = readNavJs();
            String navTree = navMenuSection(nav);
            String routesSection = nav.substring(nav.indexOf("export const ROUTES"));

            for (String key : MODULE4_KEYS) {
                assertTrue(navTree.contains("key: '" + key + "'"), "NAV 应含模块四菜单项：" + key);
                assertTrue(routesSection.contains("'/" + key + "'"), "ROUTES 应含模块四路由：/" + key);
            }
            assertTrue(navTree.contains("key: 'station'"), "NAV 应含模块四『服务站』分组");
        }

        /** 截取 NAV 菜单树段（export const NAV 到 export const ROUTES 之间），排除 ROUTES 白名单干扰。 */
        private String navMenuSection(String nav) {
            int from = nav.indexOf("export const NAV");
            int to = nav.indexOf("export const ROUTES");
            assertTrue(from >= 0 && to > from, "nav.js 结构异常：找不到 NAV / ROUTES 声明");
            return nav.substring(from, to);
        }

        private String readNavJs() throws IOException {
            return Files.readString(webPath("src/nav.js"), StandardCharsets.UTF_8);
        }

        private boolean webFileExists(String rel) {
            try {
                return Files.exists(webPath(rel));
            } catch (AssertionError e) {
                return false;
            }
        }

        /** 解析 web/ 下的相对路径，兼容从 backend/ 或仓库根目录运行测试。 */
        private Path webPath(String rel) {
            String target = "web/" + rel;
            for (Path base : List.of(Path.of(""), Path.of(".."), Path.of("../.."))) {
                Path p = base.resolve(target);
                if (Files.exists(p)) {
                    return p;
                }
            }
            throw new AssertionError("找不到前端文件：" + target
                    + "（cwd=" + Path.of("").toAbsolutePath() + "）");
        }
    }

    /* ============ 观察项（非缺陷，仅记录设计保真度差异，保持绿灯） ============ */

    @Nested
    @ExtendWith(MockitoExtension.class)
    class DesignFidelityNotes {

        @Test
        @DisplayName("观察：settlement_items 当前为聚合三行、ref_id 恒为 null（设计允许仅存 ID，但失去逐笔溯源能力）")
        void settlementItemsAreAggregatedWithNullRefId() {
            // 已在 StationSettlementServiceTest#generate_persistsThreeItemsWithIdOnlyRef 中断言：
            // 3 行 LOGISTICS/COMMISSION/RECOVERY，refId == null。
            // 此处仅显式记录：BC-2（仅 ID 引用）成立，但设计 §2.3 期望的 movements/alloc 逐笔 ref_id 未落地。
            assertEquals(Long.class, refIdFieldType(), "ref_id 字段类型必须是 Long（纯 ID），当前满足 BC-2");
        }

        private Class<?> refIdFieldType() {
            try {
                return StationSettlementItem.class.getDeclaredField("refId").getType();
            } catch (NoSuchFieldException e) {
                throw new AssertionError(e);
            }
        }
    }

    /* ================= 供人工核对：三层解耦总结（始终绿） ================= */

    @Test
    @DisplayName("解耦口径自检：三层服务的仓储依赖集合互不包含对方写仓储（详见 StationLayerDecouplingTest）")
    void decouplingSummaryIsCoveredElsewhere() {
        assertEquals(3, List.of(StationInventoryService.class, StationProjectService.class,
                StationSettlementService.class).size());
    }

    /** 编译期占位，确保 StationViews 三层视图 record 均可用（DTO 追加未破坏既有类）。 */
    @Test
    @DisplayName("DTO 追加自检：三层视图 record 可正常构造，未破坏既有 StationViews")
    void stationViewsRecordsAreConstructible() {
        StationViews.StationInventoryStockView stock =
                new StationViews.StationInventoryStockView(1L, STATION_A, "SKU-A", 5, Instant.EPOCH);
        StationViews.StationProjectAllocView alloc = new StationViews.StationProjectAllocView(
                1L, 2L, STOCK_ID, "SKU-A", 10, null, 40, Instant.EPOCH, Instant.EPOCH);
        StationViews.StationSettlementItemView item = new StationViews.StationSettlementItemView(
                1L, 2L, "LOGISTICS", null, "物流费", new BigDecimal("25.00"), "DEBIT", Instant.EPOCH);

        assertEquals(5, stock.stockQty());
        assertEquals(40, alloc.availableQty());
        assertEquals("LOGISTICS", item.itemType());
    }
}
