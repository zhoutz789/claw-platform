package com.claw.server.integration;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.fulfillment.FulfillmentSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履约结算人工介入真库回归（老板「失败挂起转人工」承诺落地）。
 *
 * <p>跑在真实 PostgreSQL（见 {@link AbstractIntegrationTest}），与自动结算共用
 * {@code FulfillmentSettlementService.computeAndMove} 单一实现，重点守住<b>人工路径资金口径与
 * 自动路径完全一致、且绝不双结 / 绝不虚付</b>。
 *
 * <p>4 个场景：
 * <ol>
 *   <li>重结算成功：MANUAL(PAYEE_ACCOUNT_MISSING) 绑定收款户后 retry → SETTLED，恒等式 + 各户余额正确，冻结释放；</li>
 *   <li>重结算仍挂起：COMMISSION_RULE_MISSING 未配规则直接 retry → 仍 MANUAL，retry_count+1，不过账；</li>
 *   <li>行政关闭：resolve 释放用户冻结、置 DONE，不结算给服务站/厂家，无 FULFILLMENT_SETTLEMENT 过账；</li>
 *   <li>非挂起单重结算拦截：对已 SETTLED 单 retry → 抛 40951，不重复过账。</li>
 * </ol>
 */
class FulfillmentSettlementManualIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FulfillmentSettlementService settlementService;

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /* ===================== 1. 重结算成功（修复根因后续结算） ===================== */

    @Test
    void retryAfterBindingPayee_settlesWithExactIdentity() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        long stationUser = insertUser(t + "S");
        bind(mfgUser, "MANUFACTURER", mfg);
        // 故意只绑厂家、不绑服务站 → 触发 PAYEE_ACCOUNT_MISSING
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "200.00");

        long platform = platformAccountId();
        settlementService.handle(101L, payload(order));
        // 断言已挂起
        assertEquals("MANUAL", settleStatus(order), "未绑服务站须挂起");
        assertEquals("PAYEE_ACCOUNT_MISSING", settleReason(order));
        assertEquals(0, frozen(custSub).compareTo(new BigDecimal("200.00")), "挂起时冻结不得释放");
        assertEquals(0, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"), "挂起不得过账");

        // —— 修复根因：补绑服务站收款户 ——
        bind(stationUser, "STATION", station);
        long sid = settleId(order);
        BigDecimal beforePlatform = bal(platform);

        settlementService.retry(sid, 999L, "bind station payee");

        // 终态 + 恒等式
        assertEquals("SETTLED", settleStatus(order), "重结算须置 SETTLED");
        assertEquals(0, new BigDecimal("200.00").compareTo(settleTotal(order)), "total 须为 200");
        assertEquals(0, new BigDecimal("16.00").compareTo(settleCommission(order)), "提成 = 200×8% = 16");
        assertEquals(0, new BigDecimal("184.00").compareTo(settleBalance(order)), "货款 = 200−16 = 184");
        assertEquals(0, settleCommission(order).add(settleBalance(order)).compareTo(settleTotal(order)), "恒等式成立");
        assertEquals(1, settleRetryCount(order), "retry_count 须 +1");

        // 资金变动：冻结释放、服务站 +16、厂家 +184、平台净 0
        // （MASTER 收款户由 retry 内部的 getOrCreateUserAccount 懒建，故在 retry 之后查询）
        long stationMaster = accountId(stationUser, "MASTER");
        long mfgMaster = accountId(mfgUser, "MASTER");
        assertEquals(0, frozen(custSub).compareTo(ZERO), "重结算后冻结须释放");
        assertEquals(0, bal(stationMaster).compareTo(new BigDecimal("16.00")), "服务站 MASTER 须收 16.00 提成");
        assertEquals(0, bal(mfgMaster).compareTo(new BigDecimal("184.00")), "厂家 MASTER 须收 184.00 货款");
        assertEquals(0, bal(platform).subtract(beforePlatform).compareTo(ZERO), "平台净 0");
        assertEquals("SETTLED", orderStatus(order), "订单须置 SETTLED");
        assertEquals(6, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"), "重结算须产生 3 笔过账共 6 分录");
    }

    /* ===================== 2. 重结算仍挂起（根因未修复） ===================== */

    @Test
    void retryWithoutRule_staysManual_noMoneyMoved() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        long stationUser = insertUser(t + "S");
        bind(mfgUser, "MANUFACTURER", mfg);
        bind(stationUser, "STATION", station);
        long product = insertProduct(t, mfg);
        // 故意不插提成规则 → COMMISSION_RULE_MISSING

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "200.00");

        settlementService.handle(102L, payload(order));
        assertEquals("MANUAL", settleStatus(order));
        assertEquals("COMMISSION_RULE_MISSING", settleReason(order));

        long sid = settleId(order);
        BigDecimal beforeCustFrozen = frozen(custSub);

        settlementService.retry(sid, 999L, "still no rule");

        // 仍挂起、retry_count +1、金额未动（无过账 = 服务站/厂家均未被给付）
        assertEquals("MANUAL", settleStatus(order), "未配规则重试仍须挂起");
        assertEquals("COMMISSION_RULE_MISSING", settleReason(order));
        assertEquals(1, settleRetryCount(order), "retry_count 须 +1");
        assertEquals(0, frozen(custSub).compareTo(beforeCustFrozen), "重试不得释放冻结");
        assertEquals(0, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"), "重试不得过账（服务站/厂家均未被给付）");
    }

    /* ===================== 3. 行政关闭（resolve） ===================== */

    @Test
    void resolve_releasesFrozenAndCloses_withoutSettling() {
        String t = tag();
        long mfg = insertManufacturer(t);
        long station = insertStation(t);
        long customer = insertUser(t + "C");
        long mfgUser = insertUser(t + "M");
        // 都不绑 → PAYEE_ACCOUNT_MISSING
        long product = insertProduct(t, mfg);
        insertCommissionRule(mfg, product, "0.08");

        long order = insertOrder(customer, mfg, station, "200.00");
        insertOrderItem(order, product);
        long custSub = insertCustomerSub(customer, "200.00");

        settlementService.handle(103L, payload(order));
        assertEquals("MANUAL", settleStatus(order));

        long sid = settleId(order);
        BigDecimal beforeCustBal = bal(custSub);
        BigDecimal beforeCustFrozen = frozen(custSub);

        settlementService.resolve(sid, 999L, "refund to customer, close");

        // 结算单 DONE、冻结释放、用户余额回冲（return to platform，客户两清）
        assertEquals("DONE", settleStatus(order), "resolve 须置 DONE");
        assertEquals(0, frozen(custSub).compareTo(ZERO), "resolve 须释放冻结");
        assertEquals(0, bal(custSub).compareTo(ZERO), "resolve 后用户余额须回冲为 0（两清）");
        assertEquals(0, beforeCustBal.subtract(bal(custSub)).compareTo(beforeCustFrozen),
                "释放金额 = 原冻结额，不多不少");
        // 不产生任何履约结算过账（服务站/厂家均未被给付；仅一笔 REFUND 释放）
        assertEquals(0, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"), "resolve 不得产生履约结算过账");
        assertEquals("PICKED_UP", orderStatus(order), "resolve 不改变订单状态（cancel 守卫禁止 PICKED_UP→CANCELLED）");
    }

    /* ===================== 4. 非挂起单重结算拦截 ===================== */

    @Test
    void retryOnSettled_throws40951_noDoubleSettle() {
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

        settlementService.handle(104L, payload(order));
        assertEquals("SETTLED", settleStatus(order));
        int entriesBefore = entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%");
        assertEquals(6, entriesBefore, "正常结算 6 分录");

        long sid = settleId(order);
        BizException ex = assertThrows(BizException.class,
                () -> settlementService.retry(sid, 999L, "should reject"));
        assertEquals(40951, ex.getCode(), "已结算单重试须被 40951 拦截");

        // 不得重复过账
        assertEquals(entriesBefore, entryCount("FULFILLMENT_SETTLEMENT", "FS-" + order + "-%"),
                "拦截后不得新增任何分录");
        assertEquals("SETTLED", settleStatus(order));
    }

    /* ------------------------------------------------------------------ */
    /* 断言辅助                                                            */
    /* ------------------------------------------------------------------ */

    private long settleId(long order) {
        return jdbc.queryForObject(
                "SELECT id FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", Long.class, order);
    }

    private int settleRetryCount(long order) {
        return jdbc.queryForObject(
                "SELECT retry_count FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", Integer.class, order);
    }

    private String settleStatus(long order) {
        return jdbc.queryForObject(
                "SELECT status FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", String.class, order);
    }

    private String settleReason(long order) {
        return jdbc.queryForObject(
                "SELECT reason_code FROM claw.fulfillment_settlements WHERE fulfillment_order_id = ?", String.class, order);
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
    /* 数据准备                                                            */
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
                        + "VALUES ('STATION_LOGISTICS_FEE_RATE', ?, '费率', '物流费率', 'NUMBER', true, 1, false) "
                        + "ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value, deleted = false",
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

    private long insertCustomerSub(long userId, String amount) {
        return jdbc.queryForObject(
                "INSERT INTO claw.accounts (user_id, account_type, currency, balance, frozen, tenant_id, deleted) "
                        + "VALUES (?, 'SUB', 'USD', ?, ?, 1, false) RETURNING id",
                Long.class, userId, new BigDecimal(amount), new BigDecimal(amount));
    }
}
