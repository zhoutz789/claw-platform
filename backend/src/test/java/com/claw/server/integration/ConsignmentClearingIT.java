package com.claw.server.integration;

import com.claw.server.common.enums.CustodyOwnerType;
import com.claw.server.common.enums.LocationType;
import com.claw.server.common.enums.PayStatus;
import com.claw.server.domain.clearing.ClearingInstructionService;
import com.claw.server.domain.clearing.ClearingService;
import com.claw.server.domain.funds.FundsLocation;
import com.claw.server.domain.funds.FundsLocationRepository;
import com.claw.server.domain.funds.VirtualSubAccount;
import com.claw.server.domain.funds.VirtualSubAccountAbaRefResolver;
import com.claw.server.domain.funds.VirtualSubAccountRepository;
import com.claw.server.domain.payment.ChannelSplitPlanner;
import com.claw.server.domain.payment.ClearingChannelMock;
import com.claw.server.domain.payment.ConsignmentClearingHandler;
import com.claw.server.domain.payment.PayeeAbaRefResolver;
import com.claw.server.domain.payment.PaymentOrder;
import com.claw.server.domain.payment.PaymentOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 扫码购分账<b>集成测试</b>（真实 PostgreSQL / TimescaleDB，复用 {@link AbstractIntegrationTest}
 * 的 {@code claw_it} 库 —— 与既有 11 个 IT 同一口径；Flyway 全量应用到 V132）。
 *
 * <p>为什么不是 H2：本项目集成测试已在 B2 统一升级为「真实 PostgreSQL」，原因之一是
 * V1–V132 的迁移使用 {@code SET search_path}、{@code jsonb}、{@code TIMESTAMPTZ}、
 * {@code create_hypertable} 等 PG 专有语法，H2 跑不动这些迁移（H2 只用于 {@code local}
 * profile 的 {@code ddl-auto=update} 单机开发）。因此本 IT 与全仓其余 IT 保持一致走真库。
 *
 * <p>两个用例分别覆盖「真实代理 / 真实通道骨架」与「通道可用」两条路径：
 * <ol>
 *   <li>{@link #scanPurchase100_persistsFourLegs_andDegradesWhenRealChannelNotWired()}：
 *       经 Spring 代理的真实 {@link ConsignmentClearingHandler} Bean（默认装配
 *       {@code AbaClearingChannel}）→ 四腿入账 + 四张指令落库；因真实通道 API 尚未接入
 *       （{@code TODO(待通道确认)}，抛错误码 42275），指令落 {@code FAILED} 挂起而<b>账本权益保留</b>；</li>
 *   <li>{@link #scanPurchase100_withMockChannel_settlesAllInstructions()}：装配
 *       {@link ClearingChannelMock}（通道可用）→ 四张指令推进到 {@code SETTLED}。</li>
 * </ol>
 *
 * <p>金额口径：交易 $100 → 平台 5% / 服务站 10% / 物流 5% / 厂家残差 80%（V131 种子 + V132 显式化）。
 */
class ConsignmentClearingIT extends AbstractIntegrationTest {

    private static final String SCENE = "CONSIGNMENT_SCAN";
    private static final String VSA_PREFIX = "VSA-IT-";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PaymentOrderRepository paymentOrderRepository;
    @Autowired
    private FundsLocationRepository fundsLocationRepository;
    @Autowired
    private VirtualSubAccountRepository virtualSubAccountRepository;
    @Autowired
    private ClearingService clearingService;
    @Autowired
    private ClearingInstructionService clearingInstructionService;
    @Autowired
    private ConsignmentClearingHandler consignmentClearingHandler;

    /** 建托管点位 + 三类收款方的 ABA 绑定子户（分账准入门槛）。 */
    private void seedCustodyParties(String tag) {
        FundsLocation location = fundsLocationRepository.save(FundsLocation.builder()
                .locationCode("IT-ABA-" + tag)
                .institution("ABA")
                .locationType(LocationType.CLIENT_CUSTODY)
                .channel("ABA_PAYWAY")
                .currency("USD")
                .status("ACTIVE")
                .build());
        for (CustodyOwnerType ownerType : List.of(CustodyOwnerType.MANUFACTURER,
                CustodyOwnerType.STATION, CustodyOwnerType.LOGISTICS)) {
            virtualSubAccountRepository.save(VirtualSubAccount.builder()
                    .vsaNo(VSA_PREFIX + ownerType.name() + "-" + tag)
                    .ownerType(ownerType)
                    .ownerId(1L)
                    .fundsLocationId(location.getId())
                    .currency("USD")
                    .externalSubNo("ABA-" + ownerType.name() + "-" + tag)
                    .status("ACTIVE")
                    .build());
        }
    }

    /** 建一张已支付成功的扫码购支付单。 */
    private PaymentOrder paidScanPurchase(String orderNo) {
        return paymentOrderRepository.save(PaymentOrder.builder()
                .orderNo(orderNo)
                .bizRef("SCAN_PURCHASE:" + orderNo)
                .amountUsd(new BigDecimal("100.00"))
                .channel("khqr")
                .paymentMethod("SCAN_PURCHASE")
                .status(PayStatus.PAID)
                .build());
    }

    private List<Map<String, Object>> instructionsOf(String basisRef) {
        return jdbcTemplate.queryForList(
                "SELECT status, amount, channel FROM claw.clearing_instruction WHERE basis_ref = ? ORDER BY id",
                basisRef);
    }

    private Long ledgerLegsOf(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claw.account_entries WHERE biz_type = 'CLEARING_SETTLE' AND biz_ref LIKE ?",
                Long.class, orderNo + ":%");
    }

    private BigDecimal ledgerCreditedOf(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM claw.account_entries "
                        + "WHERE biz_type = 'CLEARING_SETTLE' AND biz_ref LIKE ? AND direction = 'C'",
                BigDecimal.class, orderNo + ":%");
    }

    @Test
    void scanPurchase100_persistsFourLegs_andDegradesWhenRealChannelNotWired() {
        String tag = String.valueOf(System.nanoTime());
        String orderNo = "ORD-IT-" + tag;
        seedCustodyParties(tag);
        paidScanPurchase(orderNo);

        // 经 Spring 代理的真实 handler Bean（默认装配 AbaClearingChannel 骨架）
        consignmentClearingHandler.handle(System.nanoTime(), "{\"orderNo\":\"" + orderNo + "\"}");

        // 账本：四腿 CLEARING_SETTLE，贷方合计 = 交易总额（Σ借 = Σ贷 由账本引擎保证）
        assertEquals(4L, ledgerLegsOf(orderNo), "四腿逐笔过账");
        assertEquals(0, ledgerCreditedOf(orderNo).compareTo(new BigDecimal("100.00")));

        // 指令：四张落库；真实通道 API 未接入（42275）→ 落 FAILED 挂起，账本权益保留等人工/批量代付
        List<Map<String, Object>> rows = instructionsOf(orderNo);
        assertEquals(4, rows.size());
        for (Map<String, Object> row : rows) {
            assertEquals("FAILED", row.get("status"));
            assertEquals("ABA_PAYWAY", row.get("channel"));
        }

        // 幂等：重复投递不再新增账本分录与指令
        consignmentClearingHandler.handle(System.nanoTime(), "{\"orderNo\":\"" + orderNo + "\"}");
        assertEquals(4L, ledgerLegsOf(orderNo), "幂等重放不得重复入账");
        assertEquals(4, instructionsOf(orderNo).size(), "幂等重放不得重复落指令");
    }

    @Test
    void scanPurchase100_withMockChannel_settlesAllInstructions() {
        String tag = String.valueOf(System.nanoTime());
        String orderNo = "ORD-IT-" + tag;
        seedCustodyParties(tag);
        paidScanPurchase(orderNo);

        // 装配通道可用的 handler（本地/联调口径：ClearingChannelMock 影子实现）
        ClearingChannelMock gateway = new ClearingChannelMock();
        PayeeAbaRefResolver resolver = new VirtualSubAccountAbaRefResolver(virtualSubAccountRepository);
        ConsignmentClearingHandler handler = new ConsignmentClearingHandler(
                paymentOrderRepository, clearingService, clearingInstructionService,
                new ChannelSplitPlanner(), resolver, List.of(gateway), new ObjectMapper());

        handler.handle(System.nanoTime(), "{\"orderNo\":\"" + orderNo + "\"}");

        assertEquals(4L, ledgerLegsOf(orderNo));
        assertEquals(0, ledgerCreditedOf(orderNo).compareTo(new BigDecimal("100.00")));

        List<Map<String, Object>> rows = instructionsOf(orderNo);
        assertEquals(4, rows.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            assertEquals("SETTLED", row.get("status"), "通道可用时指令须推进到终态 SETTLED");
            sum = sum.add((BigDecimal) row.get("amount"));
        }
        assertEquals(0, sum.compareTo(new BigDecimal("100.0000")), "Σ腿金额 == 交易总额（通道错误码 92 约束）");

        // 通道侧：单次下发、四受益人、无分片、尾差为 0
        assertEquals(1, gateway.invocations().size());
        assertEquals(4, gateway.invocations().get(0).payees().size());

        // 分账规则口径回归：厂家为显式 RESIDUAL 兜底（V132 校正后）
        List<Map<String, Object>> residualRules = jdbcTemplate.queryForList(
                "SELECT payee_type, basis FROM claw.settlement_rule "
                        + "WHERE biz_scene = ? AND basis = 'RESIDUAL' AND deleted = FALSE", SCENE);
        assertTrue(residualRules.stream().anyMatch(r -> "MANUFACTURER".equals(r.get("payee_type"))),
                "V132 后 CONSIGNMENT_SCAN 的兜底项应为显式 RESIDUAL");
    }
}
