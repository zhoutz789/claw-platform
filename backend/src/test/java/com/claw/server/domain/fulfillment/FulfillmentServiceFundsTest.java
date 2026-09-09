package com.claw.server.domain.fulfillment;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.FulfillmentStatus;
import com.claw.server.common.event.OutboxPublisher;
import com.claw.server.domain.consignment.ConsignmentCustodyRepository;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.inventory.InventoryRepository;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.lifecycle.LifecycleEventRepository;
import com.claw.server.domain.org.OrgWritableGuard;
import com.claw.server.domain.project.DeviceAuthorizationRepository;
import com.claw.server.domain.role.PrincipalBindingRepository;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FulfillmentService} 履约资金（冻结 / 解冻）纯逻辑单元测试（不依赖 Spring / 真实 PG）。
 *
 * <h2>为什么要这个测试</h2>
 * 历史上 {@code freezeFunds / releaseFreeze} 直接改 {@code Account.balance}：
 * <ul>
 *   <li>① 完全绕过复式记账引擎，不产生任何 {@code account_entries} 流水，资金不可审计；</li>
 *   <li>② 不对称——冻结时 balance 加了钱，解冻时只减 {@code frozen} 不减 {@code balance}，
 *       订单取消/过期后用户余额<b>永久虚高</b>，凭空多出一笔钱。</li>
 * </ul>
 * 本测试把这两点钉死：freeze → release 之后账户 balance 与 frozen 必须回到初始状态，
 * 且两段动作各产生一借一贷两条分录。
 *
 * <h2>账本模拟</h2>
 * {@link LedgerService#postEntries} 被桩成<b>真实行为</b>（而非空返回）：校验借贷平衡、
 * 校验出账户余额充足（平台内部户豁免，与 {@code LedgerService} 的 V85 判定一致）、
 * 校验 (bizType, bizRef) 幂等，然后真实加减余额。这样才能验证「balance 是否回到初始值」。
 *
 * <p>资金域隔离：所有余额/流水写入经 {@link AccountService} / {@link LedgerService}，
 * 与 ArchUnit 铁律一致（履约域不得持有 ledger 仓储）。
 */
class FulfillmentServiceFundsTest {

    private static final long CUSTOMER_USER_ID = 200L;
    private static final long MANUFACTURER_ID = 900L;
    private static final long STATION_ID = 7L;

    /** 用户 SUB 户 id。 */
    private static final long SUB_ACCOUNT_ID = 1000L;
    /** 平台内部户 id（MASTER + userId == NULL，豁免余额校验）。 */
    private static final long PLATFORM_ACCOUNT_ID = 777L;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("500.00");
    private static final BigDecimal INITIAL_FROZEN = new BigDecimal("20.00");
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("120.00");

    @Mock
    private FulfillmentOrderRepository orderRepository;
    @Mock
    private FulfillmentOrderItemRepository orderItemRepository;
    @Mock
    private ConsignmentCustodyRepository custodyRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private AccountService accountService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private LifecycleEventRepository lifecycleRepository;
    @Mock
    private DeviceAuthorizationRepository deviceAuthorizationRepository;
    @Mock
    private PrincipalBindingRepository bindingRepository;
    @Mock
    private SystemConfigRepository systemConfigRepository;
    @Mock
    private OutboxPublisher outboxPublisher;
    @Mock
    private OrgWritableGuard orgWritableGuard;
    @Mock
    private CreditLimitService creditLimitService;

    private FulfillmentService service;

    /** 内存订单表：key = orderId。 */
    private final Map<Long, FulfillmentOrder> orderDb = new HashMap<>();
    /** 内存账户表：key = accountId，所有桩都返回同一实例，余额变动才会累积。 */
    private final Map<Long, Account> accountDb = new HashMap<>();
    /** 已入账的 (bizType|bizRef)，用于模拟账本幂等键。 */
    private final Map<String, UUID> postedTxnKeys = new HashMap<>();
    /** 全部已落账的分录（相当于 account_entries 表）。 */
    private final List<PostedEntry> postedEntries = new ArrayList<>();

    private final AtomicLong orderIdSeq = new AtomicLong(1);

    /** 一条已落账的分录（对应 claw.account_entries 的一行）。 */
    private record PostedEntry(BizType bizType, String bizRef, LedgerRequests.Direction direction,
                               Long accountId, BigDecimal amount, String memo) {
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orderDb.clear();
        accountDb.clear();
        postedTxnKeys.clear();
        postedEntries.clear();
        orderIdSeq.set(1);

        service = new FulfillmentService(orderRepository, orderItemRepository, custodyRepository,
                inventoryRepository, deviceRepository, accountService, ledgerService, lifecycleRepository,
                deviceAuthorizationRepository, bindingRepository, systemConfigRepository, outboxPublisher,
                new ObjectMapper(), orgWritableGuard, creditLimitService);

        // 未配置 FULFILL_TIMEOUT_DAYS → 走默认 90 天
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(anyString())).thenReturn(Optional.empty());

        // 订单仓储：save 注入自增 id 并存进内存表；findById 从内存表取同一实例
        when(orderRepository.save(any(FulfillmentOrder.class))).thenAnswer(inv -> {
            FulfillmentOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(orderIdSeq.getAndIncrement());
            }
            orderDb.put(o.getId(), o);
            return o;
        });
        when(orderRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(orderDb.get(inv.getArgument(0))));
        when(orderItemRepository.save(any(FulfillmentOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));

        // 账户：用户 SUB 户 + 平台内部户（都是同一实例，balance/frozen 变动会累积）
        accountDb.put(SUB_ACCOUNT_ID, Account.builder().id(SUB_ACCOUNT_ID).userId(CUSTOMER_USER_ID)
                .accountType(AccountType.SUB).balance(INITIAL_BALANCE).frozen(INITIAL_FROZEN).build());
        accountDb.put(PLATFORM_ACCOUNT_ID, Account.builder().id(PLATFORM_ACCOUNT_ID).userId(null)
                .accountType(AccountType.MASTER).balance(BigDecimal.ZERO).frozen(BigDecimal.ZERO).build());

        when(accountService.getOrCreateSubAccount(eq(CUSTOMER_USER_ID), eq(AccountType.SUB)))
                .thenReturn(accountDb.get(SUB_ACCOUNT_ID));
        when(accountService.getOrCreatePlatformAccount(eq(AccountType.MASTER)))
                .thenReturn(accountDb.get(PLATFORM_ACCOUNT_ID));
        when(accountService.saveAccount(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        // 账本：按 LedgerService 的真实语义模拟（平衡校验 / 余额校验 / 平台户豁免 / 幂等）
        when(ledgerService.postEntries(any(), any(), anyList())).thenAnswer(inv -> {
            BizType bizType = inv.getArgument(0);
            String bizRef = inv.getArgument(1);
            @SuppressWarnings("unchecked")
            List<LedgerRequests.Entry> entries = inv.getArgument(2);

            String txnKey = bizType + "|" + bizRef;
            if (postedTxnKeys.containsKey(txnKey)) {
                throw BizException.of(40950, "error.ledger.duplicate");
            }
            BigDecimal debit = BigDecimal.ZERO;
            BigDecimal credit = BigDecimal.ZERO;
            for (LedgerRequests.Entry e : entries) {
                if (e.direction() == LedgerRequests.Direction.D) {
                    debit = debit.add(e.amount());
                } else {
                    credit = credit.add(e.amount());
                }
            }
            if (debit.compareTo(credit) != 0) {
                throw BizException.of(42250, "error.ledger.unbalanced");
            }

            UUID txnId = UUID.randomUUID();
            postedTxnKeys.put(txnKey, txnId);
            for (LedgerRequests.Entry e : entries) {
                Account acc = accountDb.get(e.accountId());
                if (acc == null) {
                    throw BizException.notFound("error.account.not.found");
                }
                if (e.direction() == LedgerRequests.Direction.D) {
                    // 豁免余额校验的只有「平台内部户」：MASTER 且 userId == NULL（V85 口径）
                    boolean isPlatformOffset = acc.getAccountType() == AccountType.MASTER && acc.getUserId() == null;
                    if (!isPlatformOffset && acc.getBalance().compareTo(e.amount()) < 0) {
                        throw BizException.of(42251, "error.ledger.insufficient");
                    }
                    acc.setBalance(acc.getBalance().subtract(e.amount()));
                } else {
                    acc.setBalance(acc.getBalance().add(e.amount()));
                }
                postedEntries.add(new PostedEntry(bizType, bizRef, e.direction(), e.accountId(), e.amount(), e.memo()));
            }
            return new LedgerViews.TxnResult(txnId, bizType, bizRef, entries.size(), debit, List.of());
        });
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 建一单（PENDING_PAYMENT），金额取 ORDER_AMOUNT。 */
    private FulfillmentOrder newOrder() {
        return service.createOrder(CUSTOMER_USER_ID, MANUFACTURER_ID, STATION_ID, false,
                List.of(FulfillmentOrderItem.builder().productId(1L).qty(1).price(ORDER_AMOUNT).build()),
                ORDER_AMOUNT);
    }

    private Account sub() {
        return accountDb.get(SUB_ACCOUNT_ID);
    }

    private Account platform() {
        return accountDb.get(PLATFORM_ACCOUNT_ID);
    }

    private List<PostedEntry> entriesOf(BizType bizType) {
        return postedEntries.stream().filter(e -> e.bizType() == bizType).toList();
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("付款冻结：balance/frozen 各 +amount，且走账本产生 1 借 1 贷（借平台内部户 / 贷用户 SUB 户）")
    void payOrder_freezesViaLedger() {
        FulfillmentOrder o = newOrder();
        service.payOrder(o.getId(), "PAY-REF-1", CUSTOMER_USER_ID);

        // balance 不再由服务直接改，而是账本贷记的结果：500.00 + 120.00
        assertEquals(0, sub().getBalance().compareTo(new BigDecimal("620.00")), "balance 应等于初始 + 订单金额");
        assertEquals(0, sub().getFrozen().compareTo(new BigDecimal("140.00")), "frozen 应等于初始 + 订单金额");

        // 恰好两笔分录：平台内部户借 120.00 / 用户 SUB 户贷 120.00
        List<PostedEntry> entries = entriesOf(BizType.RECHARGE);
        assertEquals(2, entries.size(), "冻结必须产生一借一贷两条分录");
        PostedEntry debit = entries.stream().filter(e -> e.direction() == LedgerRequests.Direction.D)
                .findFirst().orElseThrow();
        PostedEntry credit = entries.stream().filter(e -> e.direction() == LedgerRequests.Direction.C)
                .findFirst().orElseThrow();
        assertEquals(PLATFORM_ACCOUNT_ID, debit.accountId());
        assertEquals(SUB_ACCOUNT_ID, credit.accountId());
        assertEquals(0, debit.amount().compareTo(ORDER_AMOUNT));
        assertEquals(0, credit.amount().compareTo(ORDER_AMOUNT));
        assertEquals(o.getOrderNo(), debit.bizRef(), "幂等键应为订单号");
        assertEquals(o.getOrderNo(), credit.bizRef());

        // 平台内部户豁免余额校验，允许为负：-120.00
        assertEquals(0, platform().getBalance().compareTo(new BigDecimal("-120.00")));
    }

    @Test
    @DisplayName("取消：解冻与冻结严格对称 —— balance 与 frozen 都回到初始状态（回归：旧代码只减 frozen 导致余额永久虚高）")
    void cancel_releasesSymmetrically() {
        FulfillmentOrder o = newOrder();
        service.payOrder(o.getId(), "PAY-REF-1", CUSTOMER_USER_ID);
        service.cancel(o.getId());

        // 核心断言：一圈下来余额与冻结额必须原样复原，一分钱都不许多
        assertEquals(0, sub().getBalance().compareTo(INITIAL_BALANCE), "取消后 balance 必须回到初始值");
        assertEquals(0, sub().getFrozen().compareTo(INITIAL_FROZEN), "取消后 frozen 必须回到初始值");
        assertEquals(FulfillmentStatus.CANCELLED, orderDb.get(o.getId()).getStatus());

        // 解冻分录：借 用户 SUB 户 / 贷 平台内部户，金额同为 120.00
        List<PostedEntry> entries = entriesOf(BizType.REFUND);
        assertEquals(2, entries.size(), "解冻必须产生一借一贷两条分录");
        PostedEntry debit = entries.stream().filter(e -> e.direction() == LedgerRequests.Direction.D)
                .findFirst().orElseThrow();
        PostedEntry credit = entries.stream().filter(e -> e.direction() == LedgerRequests.Direction.C)
                .findFirst().orElseThrow();
        assertEquals(SUB_ACCOUNT_ID, debit.accountId());
        assertEquals(PLATFORM_ACCOUNT_ID, credit.accountId());
        assertEquals(0, debit.amount().compareTo(ORDER_AMOUNT));
        assertEquals(0, credit.amount().compareTo(ORDER_AMOUNT));
        assertEquals(o.getOrderNo() + ":RELEASE", debit.bizRef(), "解冻幂等键须与冻结那笔区分开");

        // 平台内部户一进一出，回到 0
        assertEquals(0, platform().getBalance().compareTo(BigDecimal.ZERO));
        // 全部分录借贷合计相等
        assertBalanced();
    }

    @Test
    @DisplayName("过期：同样原路退回，balance 与 frozen 回到初始状态")
    void expire_releasesSymmetrically() {
        FulfillmentOrder o = newOrder();
        service.payOrder(o.getId(), "PAY-REF-1", CUSTOMER_USER_ID);
        service.expire(o.getId());

        assertEquals(0, sub().getBalance().compareTo(INITIAL_BALANCE), "过期后 balance 必须回到初始值");
        assertEquals(0, sub().getFrozen().compareTo(INITIAL_FROZEN), "过期后 frozen 必须回到初始值");
        assertEquals(FulfillmentStatus.EXPIRED, orderDb.get(o.getId()).getStatus());
        assertEquals(2, entriesOf(BizType.REFUND).size());
        assertBalanced();
    }

    @Test
    @DisplayName("未付款订单取消/过期：不产生任何分录，冻结额不变（早退路径不能被空分录打挂）")
    void unpaidOrder_cancelOrExpire_noPosting() {
        FulfillmentOrder unpaid = newOrder();
        service.cancel(unpaid.getId());
        assertTrue(postedEntries.isEmpty(), "未付款订单不应产生任何账本分录");
        assertEquals(0, sub().getBalance().compareTo(INITIAL_BALANCE));
        assertEquals(0, sub().getFrozen().compareTo(INITIAL_FROZEN));

        FulfillmentOrder pending = newOrder();
        service.expire(pending.getId());
        assertTrue(postedEntries.isEmpty(), "未付款订单过期不应产生任何账本分录");
        assertEquals(FulfillmentStatus.EXPIRED, orderDb.get(pending.getId()).getStatus());
    }

    @Test
    @DisplayName("重复取消被 40919 拦住：解冻是一次性记账动作，重放会被账本判重复过账")
    void cancel_twice_rejected() {
        FulfillmentOrder o = newOrder();
        service.payOrder(o.getId(), "PAY-REF-1", CUSTOMER_USER_ID);
        service.cancel(o.getId());

        BizException ex = assertThrows(BizException.class, () -> service.cancel(o.getId()));
        assertEquals(40919, ex.getCode());

        // 只记过一次解冻
        assertEquals(2, entriesOf(BizType.REFUND).size());
        assertEquals(0, sub().getBalance().compareTo(INITIAL_BALANCE));
    }

    @Test
    @DisplayName("冻结金额为零/空时不记账（防御：账本拒绝空分录与非正数金额）")
    void zeroAmount_noPosting() {
        FulfillmentOrder o = service.createOrder(CUSTOMER_USER_ID, MANUFACTURER_ID, STATION_ID, false,
                List.of(FulfillmentOrderItem.builder().productId(1L).qty(1).price(BigDecimal.ZERO).build()),
                BigDecimal.ZERO);
        service.payOrder(o.getId(), "PAY-REF-0", CUSTOMER_USER_ID);

        assertTrue(postedEntries.isEmpty(), "金额为 0 不应过账");
        assertEquals(0, sub().getBalance().compareTo(INITIAL_BALANCE));
        assertEquals(0, sub().getFrozen().compareTo(INITIAL_FROZEN));
    }

    @Test
    @DisplayName("资金写入只走 LedgerService：服务不得直接改 Account.balance（AccountService.saveAccount 仅维护 frozen）")
    void balanceOnlyWrittenThroughLedger() {
        FulfillmentOrder o = newOrder();
        service.payOrder(o.getId(), "PAY-REF-1", CUSTOMER_USER_ID);

        // 过账被真实调用（balance 由它改）
        ArgumentCaptor<List<LedgerRequests.Entry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntries(eq(BizType.RECHARGE), eq(o.getOrderNo()), cap.capture());
        assertEquals(2, cap.getValue().size());

        // saveAccount 落的是 frozen，落库时 balance 尚未被账本改动——证明 balance 不是服务直接写的
        ArgumentCaptor<Account> accCap = ArgumentCaptor.forClass(Account.class);
        verify(accountService, times(1)).saveAccount(accCap.capture());
        assertEquals(0, accCap.getValue().getFrozen().compareTo(new BigDecimal("140.00")));
    }

    /** 全部分录借贷合计必须相等（复式记账铁律）。 */
    private void assertBalanced() {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (PostedEntry e : postedEntries) {
            if (e.direction() == LedgerRequests.Direction.D) {
                debit = debit.add(e.amount());
            } else {
                credit = credit.add(e.amount());
            }
        }
        assertEquals(0, debit.compareTo(credit), "借贷必须平衡");
    }
}
