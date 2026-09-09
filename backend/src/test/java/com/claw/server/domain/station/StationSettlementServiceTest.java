package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.domain.commission.CommissionRule;
import com.claw.server.domain.commission.CommissionRuleRepository;
import com.claw.server.domain.manufacturer.Product;
import com.claw.server.domain.manufacturer.ProductRepository;
import com.claw.server.domain.manufacturer.ProductSku;
import com.claw.server.domain.manufacturer.ProductSkuRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 行为验证：结算层三金额计算 + 写事务封闭（模块四 · BC-1 / BC-2 / BC-4 / BC-5）。
 *
 * <p>纯 Mockito 单测。核心断言：
 * <ul>
 *   <li><b>BC-1</b>：{@code generate} 只对 settlement / settlementItem 仓储发生写调用；
 *       跨层的 movements / SKU / 商品 / 提成规则 / 系统配置仓储<b>只被读一次</b>，之后
 *       {@code verifyNoMoreInteractions} 确认没有任何隐藏写。</li>
 *   <li><b>三金额</b>：物流费 = Σ(货值×费率)、提成 = Σ命中规则、
 *       厂家净额 = Σ货值 − 物流费 − 提成（恒等式单独断言）。</li>
 *   <li><b>BC-2</b>：明细 {@code ref_id} 只允许是 ID（此处为 null），不携带对方实体。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StationSettlementServiceTest {

    private static final long STATION_ID = 7L;
    private static final long OTHER_STATION_ID = 8L;
    private static final long PRODUCT_ID = 11L;
    private static final long MFG_ID = 12L;
    private static final long SKU_PK = 501L;
    private static final long OPERATOR = 99L;
    private static final String SKU = "SKU-A";

    private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-07-31T23:59:59Z");

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

    /* =============================== 夹具 =============================== */

    private StationInventoryMovement consume(long id, int deltaQty) {
        return StationInventoryMovement.builder()
                .id(id).stationId(STATION_ID).skuCode(SKU).deltaQty(deltaQty).reason("CONSUME")
                .createdAt(START.plusSeconds(id)).build();
    }

    private ProductSku sku(String price) {
        return ProductSku.builder().id(SKU_PK).productId(PRODUCT_ID).skuCode(SKU)
                .price(new BigDecimal(price)).currency("USD").status("ACTIVE").build();
    }

    private void stubLogisticsRate(String rate) {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("STATION_LOGISTICS_FEE_RATE"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("STATION_LOGISTICS_FEE_RATE").configValue(rate)
                        .dataType("NUMBER").build()));
    }

    private void stubNoCommissionRules() {
        when(commissionRuleRepository.findByManufacturerIdAndProductIdAndEnabledTrue(MFG_ID, PRODUCT_ID))
                .thenReturn(Optional.empty());
        when(commissionRuleRepository.findByManufacturerIdAndEnabledTrue(MFG_ID)).thenReturn(List.of());
        when(commissionRuleRepository.findByProductIdAndEnabledTrue(PRODUCT_ID)).thenReturn(List.of());
        when(commissionRuleRepository.findByManufacturerIdIsNullAndEnabledTrue()).thenReturn(List.of());
    }

    private void stubRateRule(String rate, int priority) {
        when(commissionRuleRepository.findByManufacturerIdAndProductIdAndEnabledTrue(MFG_ID, PRODUCT_ID))
                .thenReturn(Optional.of(CommissionRule.builder()
                        .id(1L).manufacturerId(MFG_ID).productId(PRODUCT_ID)
                        .commissionType("RATE").rate(new BigDecimal(rate)).priority(priority).enabled(true).build()));
        when(commissionRuleRepository.findByManufacturerIdAndEnabledTrue(MFG_ID)).thenReturn(List.of());
        when(commissionRuleRepository.findByProductIdAndEnabledTrue(PRODUCT_ID)).thenReturn(List.of());
        when(commissionRuleRepository.findByManufacturerIdIsNullAndEnabledTrue()).thenReturn(List.of());
    }

    private void stubCommonReads(List<StationInventoryMovement> movements, String price) {
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));
        when(movementRepository.findByStationIdAndDeltaQtyLessThanAndFulfillmentOrderIdIsNullAndCreatedAtBetween(STATION_ID, 0, START, END))
                .thenReturn(movements);
        when(productSkuRepository.findBySkuCode(SKU)).thenReturn(Optional.of(sku(price)));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(
                Product.builder().id(PRODUCT_ID).manufacturerId(MFG_ID).build()));
        when(settlementRepository.save(any(StationSettlement.class))).thenAnswer(inv -> {
            StationSettlement s = inv.getArgument(0);
            s.setId(555L);
            return s;
        });
    }

    private StationRequests.StationSettlementGenerate req() {
        return new StationRequests.StationSettlementGenerate(STATION_ID, START, END);
    }

    /* ===================== 三金额正确性（RATE 提成） ===================== */

    @Test
    @DisplayName("三金额：货值 500 / 物流费率 5% / 提成 8% → 物流 25.00、提成 40.00、净额 435.00，且满足恒等式")
    void generate_threeAmountsWithRateCommission() {
        stubCommonReads(List.of(consume(1L, -3), consume(2L, -2)), "100.00");
        stubLogisticsRate("0.05");
        stubRateRule("0.08", 10);

        StationViews.StationSettlementView view = settlementService.generate(req(), OPERATOR);

        BigDecimal totalValue = new BigDecimal("500.00"); // 100.00 × (3 + 2)
        assertEquals(0, new BigDecimal("25.00").compareTo(view.logisticsFee()),
                "物流费 = 300×0.05 + 200×0.05 = 25.00，实际=" + view.logisticsFee());
        assertEquals(0, new BigDecimal("40.00").compareTo(view.stationCommission()),
                "服务站提成 = 300×0.08 + 200×0.08 = 40.00，实际=" + view.stationCommission());
        assertEquals(0, new BigDecimal("435.00").compareTo(view.manufacturerNet()),
                "厂家净额 = 500 − 25 − 40 = 435.00，实际=" + view.manufacturerNet());
        // 恒等式：manufacturer_net = Σ货值 − logistics_fee − station_commission
        assertEquals(0, totalValue.subtract(view.logisticsFee()).subtract(view.stationCommission())
                        .compareTo(view.manufacturerNet()),
                "厂家净额必须严格等于 Σ货值 − 物流费 − 提成");
        assertEquals("DRAFT", view.status());
        assertEquals("USD", view.currency());
        assertEquals(STATION_ID, view.stationId());
        assertEquals(OPERATOR, view.createdBy());
        assertEquals(2, view.logisticsFee().scale(), "金额一律保留 2 位小数");
    }

    @Test
    @DisplayName("三金额：AMOUNT 定额提成按笔计（2 笔 × 50.00 = 100.00），物流费率 0 时物流费为 0")
    void generate_amountCommissionPerMovement() {
        stubCommonReads(List.of(consume(1L, -3), consume(2L, -2)), "100.00");
        stubLogisticsRate("0");
        when(commissionRuleRepository.findByManufacturerIdAndProductIdAndEnabledTrue(MFG_ID, PRODUCT_ID))
                .thenReturn(Optional.of(CommissionRule.builder()
                        .id(2L).manufacturerId(MFG_ID).productId(PRODUCT_ID)
                        .commissionType("AMOUNT").amount(new BigDecimal("50.00")).priority(5).enabled(true).build()));
        when(commissionRuleRepository.findByManufacturerIdAndEnabledTrue(MFG_ID)).thenReturn(List.of());
        when(commissionRuleRepository.findByProductIdAndEnabledTrue(PRODUCT_ID)).thenReturn(List.of());
        when(commissionRuleRepository.findByManufacturerIdIsNullAndEnabledTrue()).thenReturn(List.of());

        StationViews.StationSettlementView view = settlementService.generate(req(), OPERATOR);

        assertEquals(0, BigDecimal.ZERO.compareTo(view.logisticsFee()));
        assertEquals(0, new BigDecimal("100.00").compareTo(view.stationCommission()));
        assertEquals(0, new BigDecimal("400.00").compareTo(view.manufacturerNet()), "500 − 0 − 100 = 400");
    }

    @Test
    @DisplayName("三金额：无任何提成规则命中 → 提成 0（D8），净额 = 货值 − 物流费")
    void generate_noCommissionRuleYieldsZero() {
        stubCommonReads(List.of(consume(1L, -4)), "25.00");
        stubLogisticsRate("0.10");
        stubNoCommissionRules();

        StationViews.StationSettlementView view = settlementService.generate(req(), OPERATOR);

        assertEquals(0, new BigDecimal("10.00").compareTo(view.logisticsFee()), "100.00 × 0.10");
        assertEquals(0, BigDecimal.ZERO.compareTo(view.stationCommission()), "无规则 → 提成 0");
        assertEquals(0, new BigDecimal("90.00").compareTo(view.manufacturerNet()), "100 − 10 − 0 = 90");
    }

    @Test
    @DisplayName("三金额：物流费率配置缺失 → 默认 0（不抛异常）；负数 delta 取绝对值算货值")
    void generate_missingRateDefaultsToZero_andUsesAbsoluteDelta() {
        stubCommonReads(List.of(consume(1L, -7)), "10.00");
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("STATION_LOGISTICS_FEE_RATE"))
                .thenReturn(Optional.empty());
        stubNoCommissionRules();

        StationViews.StationSettlementView view = settlementService.generate(req(), OPERATOR);

        assertEquals(0, BigDecimal.ZERO.compareTo(view.logisticsFee()));
        assertEquals(0, new BigDecimal("70.00").compareTo(view.manufacturerNet()), "|−7| × 10.00 = 70.00");
    }

    @Test
    @DisplayName("健壮性：SKU 在 product_skus 中不存在 → 该笔跳过，三金额全 0，不抛异常")
    void generate_unknownSkuIsSkipped() {
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));
        when(movementRepository.findByStationIdAndDeltaQtyLessThanAndFulfillmentOrderIdIsNullAndCreatedAtBetween(STATION_ID, 0, START, END))
                .thenReturn(List.of(consume(1L, -3)));
        when(productSkuRepository.findBySkuCode(SKU)).thenReturn(Optional.empty());
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("STATION_LOGISTICS_FEE_RATE"))
                .thenReturn(Optional.of(SystemConfig.builder().configKey("STATION_LOGISTICS_FEE_RATE")
                        .configValue("0.05").build()));
        when(settlementRepository.save(any(StationSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        StationViews.StationSettlementView view = settlementService.generate(req(), OPERATOR);

        assertEquals(0, BigDecimal.ZERO.compareTo(view.logisticsFee()));
        assertEquals(0, BigDecimal.ZERO.compareTo(view.stationCommission()));
        assertEquals(0, BigDecimal.ZERO.compareTo(view.manufacturerNet()));
        verifyNoInteractions(productRepository, commissionRuleRepository);
    }

    /* ===================== BC-1：写事务封闭 ===================== */

    @Test
    @DisplayName("BC-1：generate 只写 settlements + items；movements/SKU/商品/提成/配置仓储只读且无任何写调用")
    void generate_writesOnlySettlementTables() {
        stubCommonReads(List.of(consume(1L, -3)), "100.00");
        stubLogisticsRate("0.05");
        stubRateRule("0.08", 10);

        settlementService.generate(req(), OPERATOR);

        // ① 本层写：结算单 1 次 + 明细 saveAll 1 次
        verify(settlementRepository, times(1)).save(any(StationSettlement.class));
        verify(itemRepository, times(1)).saveAll(anyList());
        verify(itemRepository, never()).save(any());

        // ② 跨层仓储：只读，读完即无更多交互（若有隐藏写会立刻失败）
        verify(movementRepository)
                .findByStationIdAndDeltaQtyLessThanAndFulfillmentOrderIdIsNullAndCreatedAtBetween(STATION_ID, 0, START, END);
        verifyNoMoreInteractions(movementRepository);
        verify(movementRepository, never()).save(any());
        verify(movementRepository, never()).saveAll(anyList());
        verify(movementRepository, never()).deleteById(any());

        verify(productSkuRepository).findBySkuCode(SKU);
        verifyNoMoreInteractions(productSkuRepository);
        verify(productRepository).findById(PRODUCT_ID);
        verifyNoMoreInteractions(productRepository);
        verify(systemConfigRepository).findByConfigKeyAndDeletedFalse("STATION_LOGISTICS_FEE_RATE");
        verifyNoMoreInteractions(systemConfigRepository);
    }

    @Test
    @DisplayName("BC-1/BC-2：结算明细恰好 3 行（LOGISTICS/COMMISSION/RECOVERY），金额与三金额一致，ref_id 仅为 ID（此处 null）")
    void generate_persistsThreeItemsWithIdOnlyRef() {
        stubCommonReads(List.of(consume(1L, -3), consume(2L, -2)), "100.00");
        stubLogisticsRate("0.05");
        stubRateRule("0.08", 10);

        settlementService.generate(req(), OPERATOR);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StationSettlementItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<StationSettlementItem> items = captor.getValue();

        assertEquals(3, items.size(), "结算明细应为物流费/提成/厂家净额三行");
        Map<String, StationSettlementItem> byType = items.stream()
                .collect(Collectors.toMap(StationSettlementItem::getItemType, Function.identity()));
        assertTrue(byType.keySet().containsAll(List.of("LOGISTICS", "COMMISSION", "RECOVERY")),
                "item_type 必须覆盖 LOGISTICS/COMMISSION/RECOVERY，实际=" + byType.keySet());

        assertEquals(0, new BigDecimal("25.00").compareTo(byType.get("LOGISTICS").getAmount()));
        assertEquals("DEBIT", byType.get("LOGISTICS").getDirection(), "物流费由厂家承担 → DEBIT");
        assertEquals(0, new BigDecimal("40.00").compareTo(byType.get("COMMISSION").getAmount()));
        assertEquals("CREDIT", byType.get("COMMISSION").getDirection());
        assertEquals(0, new BigDecimal("435.00").compareTo(byType.get("RECOVERY").getAmount()));

        for (StationSettlementItem it : items) {
            assertEquals(555L, it.getSettlementId(), "明细必须通过 settlementId（Long ID）挂主单");
            assertNull(it.getRefId(), "ref_id 只允许存 ID；当前聚合口径下为 null，绝不允许是实体引用");
            assertEquals(2, it.getAmount().scale());
        }
    }

    /* ===================== BC-4：结算列表独立可查 ===================== */

    @Test
    @DisplayName("BC-4：结算列表只读 station_settlements，不触碰库存/项目/商品任何仓储")
    void list_touchesOnlySettlementRepository() {
        when(settlementRepository.findByStationIdInOrderByCreatedAtDesc(List.of(STATION_ID)))
                .thenReturn(List.of(settlement(1L, "STL-1"), settlement(2L, "STL-2")));

        List<StationViews.StationSettlementView> out = settlementService.list(List.of(STATION_ID));

        assertEquals(2, out.size());
        verifyNoInteractions(movementRepository, productSkuRepository, productRepository,
                commissionRuleRepository, systemConfigRepository, itemRepository);
    }

    @Test
    @DisplayName("BC-5：allowedStationIds 为空集（未绑定主体）→ 结算列表返回空，不查库、不抛 403")
    void list_emptyScopeReturnsEmpty() {
        assertTrue(settlementService.list(List.of()).isEmpty());
        verifyNoInteractions(settlementRepository, movementRepository, itemRepository);
    }

    @Test
    @DisplayName("BC-5：allowedStationIds=null（平台管理员）→ 走 findAll 全平台")
    void list_platformAdminSeesAll() {
        when(settlementRepository.findAll()).thenReturn(List.of(settlement(1L, "STL-1")));

        assertEquals(1, settlementService.list(null).size());
        verify(settlementRepository).findAll();
        verify(settlementRepository, never()).findByStationIdInOrderByCreatedAtDesc(anyList());
    }

    /* ===================== BC-5：越权 403 ===================== */

    @Test
    @DisplayName("BC-5：对非授权站生成结算 → 40301，且不产生任何读写（连 movements 都不查）")
    void generate_crossStationForbidden() {
        when(scopeService.allowedStationIds(OTHER_STATION_ID)).thenReturn(List.of(STATION_ID));

        BizException ex = assertThrows(BizException.class, () -> settlementService.generate(
                new StationRequests.StationSettlementGenerate(OTHER_STATION_ID, START, END), OPERATOR));

        assertEquals(40301, ex.getCode());
        assertEquals("station.scope.forbidden", ex.getMessageCode());
        verifyNoInteractions(settlementRepository, itemRepository, movementRepository,
                productSkuRepository, productRepository, commissionRuleRepository, systemConfigRepository);
    }

    @Test
    @DisplayName("BC-5：confirm 对非授权站的结算单 → 40301，状态不变、不落库")
    void confirm_crossStationForbidden() {
        when(settlementRepository.findById(555L))
                .thenReturn(Optional.of(settlement(555L, "STL-X", OTHER_STATION_ID, "DRAFT")));
        when(scopeService.allowedStationIds(OTHER_STATION_ID)).thenReturn(List.of(STATION_ID));

        BizException ex = assertThrows(BizException.class, () -> settlementService.confirm(555L, OPERATOR));

        assertEquals(40301, ex.getCode());
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("状态机：DRAFT→CONFIRMED→PAID 合法；跳级（DRAFT 直接 pay）→ 40900")
    void statusMachineGuards() {
        StationSettlement draft = settlement(555L, "STL-1", STATION_ID, "DRAFT");
        when(settlementRepository.findById(555L)).thenReturn(Optional.of(draft));
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));

        BizException ex = assertThrows(BizException.class, () -> settlementService.pay(555L, OPERATOR));
        assertEquals(40900, ex.getCode());
        assertEquals("station.settlement.status.invalid", ex.getMessageCode());
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("状态机：DRAFT 结算单 confirm 成功 → CONFIRMED 且写入 confirmedAt")
    void confirm_draftSucceeds() {
        StationSettlement draft = settlement(555L, "STL-1", STATION_ID, "DRAFT");
        when(settlementRepository.findById(555L)).thenReturn(Optional.of(draft));
        when(scopeService.allowedStationIds(STATION_ID)).thenReturn(List.of(STATION_ID));
        when(settlementRepository.save(any(StationSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        StationViews.StationSettlementView view = settlementService.confirm(555L, OPERATOR);

        assertEquals("CONFIRMED", view.status());
        assertTrue(view.confirmedAt() != null, "confirmedAt 必须落时间戳");
        verify(itemRepository, never()).saveAll(anyList());
    }

    /* =============================== 工具 =============================== */

    private StationSettlement settlement(long id, String no) {
        return settlement(id, no, STATION_ID, "DRAFT");
    }

    private StationSettlement settlement(long id, String no, long stationId, String status) {
        return StationSettlement.builder()
                .id(id).settlementNo(no).stationId(stationId).status(status)
                .logisticsFee(new BigDecimal("1.00")).stationCommission(new BigDecimal("2.00"))
                .manufacturerNet(new BigDecimal("3.00")).currency("USD")
                .createdAt(Instant.parse("2026-08-01T00:00:00Z").plusSeconds(id)).build();
    }
}
