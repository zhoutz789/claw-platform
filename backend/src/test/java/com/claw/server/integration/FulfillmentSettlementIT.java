package com.claw.server.integration;

import com.claw.server.domain.fulfillment.FulfillmentSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履约异步结算真库回归（V87/V88/V89 + FulfillmentSettlementService）。
 *
 * <p>跑在真实 PostgreSQL（见 {@link AbstractIntegrationTest}），Flyway 全量应用 V1..V89。
 * 重点守住<b>资金准确</b>：释放冻结、两笔出账（服务站提成 / 厂家货款）、恒等式
 * {@code commission + balance_to_mfg = total_amount} 一分不差，以及幂等、四类业务挂起。
 *
 * <p>8 个场景（断言在错误实现下必失败）：
 * <ol>
 *   <li>正常路径：全链路记账 + 恒等式 + 各户余额变动正确；</li>
 *   <li>幂等重投：handle 调两次，第二次必须零副作用；</li>
 *   <li>厂家收款户缺失 → MANUAL(PAYEE_ACCOUNT_MISSING)，不释放冻结、不过账；</li>
 *   <li>服务站收款户缺失 → MANUAL(PAYEE_ACCOUNT_MISSING)；</li>
 *   <li>提成规则缺失 → MANUAL(COMMISSION_RULE_MISSING)；</li>
 *   <li>物流费率非法（负数）→ 按 0 处理并告警，仍正常结算，恒等式成立；</li>
 *   <li>订单非 PICKED_UP → 直接消费、不建结算单、不过账；</li>
 *   <li>订单金额为 0 → MANUAL(INVALID_AMOUNT)，不动冻结。</li>
 * </ol>
 *
 * <p>余额断言一律用<b>前后差值</b>（平台内部户在真库上跨测试累积，绝对值不可知），保证用例互斥、稳定可重跑。
 */
class FulfillmentSettlementIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FulfillmentSettlementService settlementService;

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /* ============================ 1. 正常路径 ============================ */

    @Test
    void happyPath_settlesWithExactIdentityAndCorrectBalances() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        long stationUser = insertUser(t + "S");
        bind(mfgUser, "MANUFACTURER", mfg);
        bind(stationUser, "STATION", station);
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "200.00"); // 模拟付款冻结后：balance=frozen=200

        long platform = platformAccountId();
        BigDecimal beforeCustBal = bal(custSub);
        BigDecimal beforeCustFrozen = frozen(custSub);
        BigDecimal beforePlatform = bal(platform);

        settlementService.handle(1L, payload(order));

        // 结算单：终态 + 恒等式
        assertEquals("SETTLED", settleStatus(order), "结算单须置 SETTLED");
        BigDecimal total = settleTotal(order);
        BigDecimal commission = settleCommission(order);
        BigDecimal balance = settleBalance(order);
        assertEquals(0, new BigDecimal("200.00").compareTo(total), "total_amount 须为 200.00");
        assertEquals(0, new BigDecimal("16.00").compareTo(commission), "提成 = 200 × 8% = 16.00");
        assertEquals(0, new BigDecimal("184.00").compareTo(balance), "厂家货款 = 200 − 16 = 184.00");
        assertEquals(0, commission.add(balance).compareTo(total), "恒等式 commission + balance = total 必须成立");

        // 用户 SUB：冻结释放（frozen −200），模拟充值回冲（balance −200）
        assertEquals(0, beforeCustBal.subtract(bal(custSub)).compareTo(new BigDecimal("200.00")),
                "用户 SUB 余额应回冲 200（释放模拟充值）");
        assertEquals(0, beforeCustFrozen.subtract(frozen(custSub)).compareTo(new BigDecimal("200.00")),
                "用户 SUB 冻结应释放 200");

        // 平台内部户：释放 +200 − 提成 − 货款 = 0
        assertEquals(0, bal(platform).subtract(beforePlatform).compareTo(ZERO), "平台内部户净变动须为 0");

        // 服务站 / 厂家 主账户收到各自出账
        long stationMaster = accountId(stationUser, "MASTER");
        long mfgMaster = accountId(mfgUser, "MASTER");
        assertEquals(0, new BigDecimal("16.00").compareTo(bal(stationMaster)), "服务站主账户应收到 16.00 提成");
        assertEquals(0, new BigDecimal("184.00").compareTo(bal(mfgMaster)), "厂家主账户应收到 184.00 货款");

        // 订单推进状态机
        assertEquals("SETTLED", orderStatus(order), "订单须置 SETTLED");
        // 全链路 3 笔过账 × 2 分录 = 6 条 ledger 记录
        assertEquals(6, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"),
                "须产生 3 笔过账（释放/提成/货款）共 6 条分录");
    }

    /* ============================ 2. 幂等重投 ============================ */

    @Test
    void idempotentRedelivery_hasNoSideEffect() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        long stationUser = insertUser(t + "S");
        bind(mfgUser, "MANUFACTURER", mfg);
        bind(stationUser, "STATION", station);
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        insertCustomerSub(customer, "200.00");

        long platform = platformAccountId();
        settlementService.handle(10L, payload(order));
        BigDecimal platformAfterFirst = bal(platform);
        BigDecimal custFrozenAfterFirst = frozen(accountId(customer, "SUB"));
        int entriesAfterFirst = entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%");
        int settleRows = jdbc.queryForObject(
                "SELECT count(*) FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?",
                Integer.class, order);

        // 模拟 OutboxRelay at-least-once 重投（同一事件再投一次）
        settlementService.handle(10L, payload(order));

        assertEquals(0, bal(platform).compareTo(platformAfterFirst), "重投后平台余额不得变化");
        assertEquals(0, frozen(accountId(customer, "SUB")).compareTo(custFrozenAfterFirst), "重投后用户冻结不得变化");
        assertEquals(entriesAfterFirst, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"),
                "重投不得产生新 ledger 分录");
        assertEquals(settleRows, jdbc.queryForObject(
                "SELECT count(*) FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?",
                Integer.class, order), "重投不得产生新结算单");
    }

    /* ===================== 3/4/5/8. 业务挂起（MANUAL） ===================== */

    @Test
    void missingManufacturerPayee_suspendsManual_noRelease() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long stationUser = insertUser(t + "S");
        bind(stationUser, "STATION", station); // 厂家未绑定 principal_binding
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "200.00");

        settlementService.handle(20L, payload(order));

        assertSuspended(order, "PAYEE_ACCOUNT_MISSING");
        assertEquals(0, frozen(custSub).compareTo(new BigDecimal("200.00")), "挂起时冻结不得释放");
        assertEquals(0, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"),
                "挂起时不得产生任何 ledger 分录");
        assertEquals("PICKED_UP", orderStatus(order), "挂起时订单状态保持 PICKED_UP");
    }

    @Test
    void missingStationPayee_suspendsManual_noRelease() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        bind(mfgUser, "MANUFACTURER", mfg); // 服务站未绑定
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "200.00");

        settlementService.handle(21L, payload(order));

        assertSuspended(order, "PAYEE_ACCOUNT_MISSING");
        assertEquals(0, frozen(custSub).compareTo(new BigDecimal("200.00")), "挂起时冻结不得释放");
        assertEquals(0, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"),
                "挂起时不得产生任何 ledger 分录");
    }

    @Test
    void missingCommissionRule_suspendsManual_noRelease() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        long stationUser = insertUser(t + "S");
        bind(mfgUser, "MANUFACTURER", mfg);
        bind(stationUser, "STATION", station);
        long product = insertProduct(t, mfg);
        // 故意不插提成规则

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "200.00");

        settlementService.handle(22L, payload(order));

        assertSuspended(order, "COMMISSION_RULE_MISSING");
        assertEquals(0, frozen(custSub).compareTo(new BigDecimal("200.00")), "挂起时冻结不得释放");
        assertEquals(0, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"),
                "挂起时不得产生任何 ledger 分录");
    }

    @Test
    void zeroOrNegativeTotal_suspendsManual_noRelease() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        long stationUser = insertUser(t + "S");
        bind(mfgUser, "MANUFACTURER", mfg);
        bind(stationUser, "STATION", station);
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");

        long order = insertOrder(customer, mfg, station, "0.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "0.00");

        settlementService.handle(23L, payload(order));

        assertSuspended(order, "INVALID_AMOUNT");
        assertEquals(0, frozen(custSub).compareTo(ZERO), "金额为 0 时冻结保持 0、不得变动");
        assertEquals(0, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"),
                "金额为 0 时不得过账");
    }

    /* ============================ 6. 非法物流费率 ============================ */

    @Test
    void illegalLogisticsRate_treatedAsZero_stillSettles() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        long stationUser = insertUser(t + "S");
        bind(mfgUser, "MANUFACTURER", mfg);
        bind(stationUser, "STATION", station);
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");
        insertLogisticsRate("-0.05"); // 负数 → 按 0 处理 + 告警

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "200.00");
        long platform = platformAccountId();
        BigDecimal beforePlatform = bal(platform);

        settlementService.handle(24L, payload(order));

        assertEquals("SETTLED", settleStatus(order), "非法费率不得阻断结算");
        assertEquals(0, settleLogistics(order).compareTo(ZERO), "物流费须按 0 处理");
        BigDecimal commission = settleCommission(order);
        BigDecimal balance = settleBalance(order);
        assertEquals(0, new BigDecimal("16.00").compareTo(commission), "提成按全额 200 计算 = 16.00");
        assertEquals(0, commission.add(balance).compareTo(new BigDecimal("200.00")), "恒等式仍成立");
        assertEquals(0, bal(platform).subtract(beforePlatform).compareTo(ZERO), "平台净变动为 0");
        assertEquals(0, frozen(custSub).compareTo(ZERO), "冻结须被释放");
        assertEquals(0, new BigDecimal("184.00").compareTo(bal(accountId(mfgUser, "MASTER"))),
                "厂家主账户收到 184.00 货款");
    }

    /* ============================ 7. 订单状态不符 ============================ */

    @Test
    void orderNotPickedUp_isConsumedWithoutSettlement() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        long stationUser = insertUser(t + "S");
        bind(mfgUser, "MANUFACTURER", mfg);
        bind(stationUser, "STATION", station);
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");

        // 订单停在 RECEIVED（非 PICKED_UP）
        long order = insertOrderStatus(customer, mfg, station, "200.00", "RECEIVED");
        insertOrderItem(order, product);
        insertCustomerSub(customer, "200.00");

        settlementService.handle(25L, payload(order));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?",
                Integer.class, order);
        assertEquals(0, rows, "非 PICKED_UP 订单不得建结算单");
        assertEquals(0, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"),
                "非 PICKED_UP 订单不得过账");
        assertEquals("RECEIVED", orderStatus(order));
    }

    /* ------------------------------------------------------------------ */
    /* 断言辅助                                                            */
    /* ------------------------------------------------------------------ */

    private void assertSuspended(long order, String reasonCode) {
        assertEquals("MANUAL", settleStatus(order), "业务挂起须置 MANUAL");
        assertEquals(reasonCode, settleReason(order), "须记录挂起原因码 " + reasonCode);
        assertNull(settleLedgerTxn(order), "挂起未过账，ledger_txn_id 应为空");
    }

    private String settleStatus(long order) {
        return jdbc.queryForObject(
                "SELECT status FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", String.class, order);
    }

    private String settleReason(long order) {
        return jdbc.queryForObject(
                "SELECT reason_code FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", String.class, order);
    }

    private String settleLedgerTxn(long order) {
        return jdbc.queryForObject(
                "SELECT ledger_txn_id FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", String.class, order);
    }

    private BigDecimal settleTotal(long order) {
        return jdbc.queryForObject(
                "SELECT total_amount FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", BigDecimal.class, order);
    }

    private BigDecimal settleCommission(long order) {
        return jdbc.queryForObject(
                "SELECT commission_amount FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", BigDecimal.class, order);
    }

    private BigDecimal settleBalance(long order) {
        return jdbc.queryForObject(
                "SELECT balance_to_mfg FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", BigDecimal.class, order);
    }

    private BigDecimal settleLogistics(long order) {
        return jdbc.queryForObject(
                "SELECT logistics_fee FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", BigDecimal.class, order);
    }

    private String orderStatus(long order) {
        return jdbc.queryForObject(
                "SELECT status FROM claw.fulfillment_orders WHERE id = ?", String.class, order);
    }

    private BigDecimal bal(long accountId) {
        BigDecimal v = jdbc.queryForObject("SELECT balance FROM claw.accounts WHERE id = ?", BigDecimal.class, accountId);
        return v == null ? ZERO : v;
    }

    private BigDecimal frozen(long accountId) {
        BigDecimal v = jdbc.queryForObject("SELECT frozen FROM claw.accounts WHERE id = ?", BigDecimal.class, accountId);
        return v == null ? ZERO : v;
    }

    private int entryCount(String bizType, String bizRefLike) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM claw.account_entries WHERE biz_type = ? AND biz_ref LIKE ?",
                Integer.class, bizType, bizRefLike);
        return n == null ? 0 : n;
    }

    private long platformAccountId() {
        return jdbc.queryForObject(
                "SELECT id FROM claw.accounts WHERE user_id IS NULL AND account_type = 'MASTER' "
                        + "AND currency = 'USD' ORDER BY id ASC LIMIT 1", Long.class);
    }

    private long accountId(long userId, String type) {
        return jdbc.queryForObject(
                "SELECT id FROM claw.accounts WHERE user_id = ? AND account_type = ? AND currency = 'USD' "
                        + "ORDER BY id ASC LIMIT 1", Long.class, userId, type);
    }

    /* ------------------------------------------------------------------ */
    /* 数据准备：直接写 SQL，避免依赖尚未打通的上游域接口                    */
    /* ------------------------------------------------------------------ */

    private String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String payload(long orderId) {
        return "{\"orderId\":" + orderId + "}";
    }

    private long insertUser(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.users (phone, full_name, status, tenant_id, deleted) "
                        + "VALUES (?, ?, 'ACTIVE', 1, false) RETURNING id",
                Long.class, "PHONE-" + tag, "user-" + tag);
    }

    private long insertManufacturer(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.manufacturers (code, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, "MFG-" + tag, "厂家-" + tag);
    }

    private long insertStation(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.stations (code, name, country_code, status, open_hours, onboarding_status) "
                        + "VALUES (?, ?, 'KHM', 'ACTIVE', '24H', ?) RETURNING id",
                Long.class, "ST-" + tag, "服务站-" + tag, "ACTIVATED");
    }

    private long insertProduct(String tag, long mfgId) {
        return jdbc.queryForObject(
                "INSERT INTO claw.products (manufacturer_id, name, asset_type, status) "
                        + "VALUES (?, ?, 'BATTERY', 'ON_SALE') RETURNING id",
                Long.class, mfgId, "产品-" + tag);
    }

    private void bind(long userId, String principalType, long principalId) {
        jdbc.update(
                "INSERT INTO claw.principal_bindings (user_id, principal_type, principal_id) VALUES (?, ?, ?)",
                userId, principalType, principalId);
    }

    private void insertCommissionRule(long mfgId, long productId, String rate) {
        jdbc.update(
                "INSERT INTO claw.device_sales_commission_rules "
                        + "(manufacturer_id, product_id, rule_name, commission_type, rate, priority, enabled, created_at, updated_at) "
                        + "VALUES (?, ?, 'RULE', 'RATE', ?, 10, true, now(), now())",
                mfgId, productId, new BigDecimal(rate));
    }

    private void insertLogisticsRate(String value) {
        jdbc.update(
                "INSERT INTO claw.system_config (config_key, config_value, category, description, data_type, editable, tenant_id, deleted) "
                        + "VALUES ('STATION_LOGISTICS_FEE_RATE', ?, '费率', '物流费率', 'NUMBER', true, 1, false)",
                value);
    }

    private long insertOrder(long customer, long mfg, long station, String total) {
        return insertOrderStatus(customer, mfg, station, total, "PICKED_UP");
    }

    private long insertOrderStatus(long customer, long mfg, long station, String total, String status) {
        return jdbc.queryForObject(
                "INSERT INTO claw.fulfillment_orders "
                        + "(order_no, customer_user_id, manufacturer_id, station_id, status, total_amount, frozen_amount, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), now()) RETURNING id",
                Long.class, "FO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                customer, mfg, station, status, new BigDecimal(total), new BigDecimal(total));
    }

    private void insertOrderItem(long orderId, long productId) {
        jdbc.update(
                "INSERT INTO claw.fulfillment_order_items (fulfillment_order_id, product_id, qty, price, created_at) "
                        + "VALUES (?, ?, 1, 0, now())",
                orderId, productId);
    }

    /** 模拟付款冻结后的用户 SUB 账户：balance=frozen=amount（冻结语义由 freezeFunds 以「模拟充值」实现）。 */
    private long insertCustomerSub(long userId, String amount) {
        return jdbc.queryForObject(
                "INSERT INTO claw.accounts (user_id, account_type, currency, balance, frozen, tenant_id, deleted) "
                        + "VALUES (?, 'SUB', 'USD', ?, ?, 1, false) RETURNING id",
                Long.class, userId, new BigDecimal(amount), new BigDecimal(amount));
    }
}
