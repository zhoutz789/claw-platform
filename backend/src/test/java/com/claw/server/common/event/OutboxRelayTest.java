package com.claw.server.common.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OutboxRelay} 纯逻辑单元测试（不依赖 Spring 上下文 / 真实 PG）。
 *
 * <h2>为什么要有这个测试</h2>
 * 改造前的 relay 是「{@code @Transactional} + 全量捞取 + publishEvent + catch 只 log.warn」，
 * 配合 {@code @TransactionalEventListener} 会<b>静默丢事件</b>：事务已提交、
 * {@code published=true} 已落库，监听器抛异常只被 Spring 记一行日志，资金链路查不到任何失败痕迹。
 * 本测试锁定 V86 改造后的核心不变量：
 * <ul>
 *   <li>认领必须带 {@code FOR UPDATE SKIP LOCKED}，且加锁与打租约在同一事务内；</li>
 *   <li>技术异常 → 重试计数 + 退避，<b>事件绝不丢失</b>；</li>
 *   <li>重试耗尽 / 不可重试 → 进 DEAD 且 {@code last_error} 有线索；</li>
 *   <li>业务挂起（handler 正常返回）→ 直接成功，不消耗重试；</li>
 *   <li>无匹配 handler → 直接 DEAD，不在活跃队列里空转。</li>
 * </ul>
 */
class OutboxRelayTest {

    private static final String TYPE = "PICKUP_COMPLETED";

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private ObjectProvider<OutboxHandler> handlerProvider;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private OutboxHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // TransactionTemplate 需要一个事务；这里给它一个「假事务」即可，因为本测试只验证 relay 的
        // 分支逻辑与 SQL 语义，不验证真实事务行为（真实 PG 行为由集成测试覆盖）。
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(handler.eventType()).thenReturn(TYPE);
    }

    // ------------------------------------------------------------------
    // 1. 认领的原子性 / 并发安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("认领 SQL 必须带 FOR UPDATE SKIP LOCKED + 限量 + 租约/到期三重过滤")
    void claimSqlMustBeSkipLockedLimitedAndLeaseAware() {
        String sql = OutboxEventRepository.CLAIM_IDS_SQL;

        assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"),
                "没有 FOR UPDATE SKIP LOCKED，多实例会并发重复投递同一行（这是多实例互斥的唯一手段）");
        assertTrue(sql.contains("LIMIT :limit"),
                "没有 LIMIT，一次会拖垮内存（改造前的 D2 缺陷）");
        assertTrue(sql.contains("status IN ('NEW', 'FAILED')"),
                "只应认领活跃状态；PUBLISHED/DEAD 不该再被捞出来");
        assertTrue(sql.contains("next_attempt_at <= :now"),
                "必须按退避落点过滤，否则退避形同虚设、失败事件会被疯狂重试");
        assertTrue(sql.contains("locked_until IS NULL OR locked_until < :now"),
                "必须跳过仍持有有效租约的行，否则进程崩溃后事件会被并发重投");
        assertTrue(sql.contains("ORDER BY created_at ASC"),
                "应当先进先出，避免老事件被新事件饿死");

        // 两条语句必须配套：加锁在 SELECT，占用在 UPDATE，缺一不可
        assertTrue(OutboxEventRepository.MARK_CLAIMED_SQL.contains("retry_count  = retry_count + 1"),
                "retry_count 必须在认领时 +1（不是失败时 +1），否则崩溃循环会导致无限重投");
        assertTrue(OutboxEventRepository.MARK_CLAIMED_SQL.contains("locked_until = :leaseUntil"),
                "认领必须打上租约，进程崩溃后行才能被自动回收");
    }

    @Test
    @DisplayName("加锁与打租约必须在同一个事务内（否则锁提前释放，认领不具原子性）")
    void claimLocksAndUpdatesWithinSingleTransaction() {
        OutboxEvent event = event(1L, 1, 5);
        stubClaim(List.of(event));
        OutboxRelay relay = newRelayWith(handler);

        relay.claimBatch();

        // 整个认领过程只开一个事务 → SELECT ... FOR UPDATE 的锁一直持有到 UPDATE 提交
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager, times(1)).commit(any());
        verify(outboxRepository).findClaimableIds(any(), eq(OutboxRelay.BATCH_SIZE));
        verify(outboxRepository).markClaimed(eq(List.of(1L)), any(), any());
    }

    @Test
    @DisplayName("认领打上的租约必须落在未来（否则租约等于没打）")
    void claimSetsLeaseInTheFuture() {
        OutboxEvent event = event(1L, 1, 5);
        stubClaim(List.of(event));

        Instant before = Instant.now();
        newRelayWith(handler).claimBatch();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Instant> leaseCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).markClaimed(any(), any(), leaseCaptor.capture());
        assertTrue(leaseCaptor.getValue().isAfter(before),
                "租约到期时间必须在当前时间之后");
        assertTrue(leaseCaptor.getValue().isAfter(before.plus(OutboxRelay.LEASE.minusSeconds(1))),
                "租约时长应约为 " + OutboxRelay.LEASE);
    }

    // ------------------------------------------------------------------
    // 2. 技术异常 → 重试 + 退避，事件不丢
    // ------------------------------------------------------------------

    @Test
    @DisplayName("handler 抛技术异常 → 计数+1、next_attempt_at 退避后移、保留 last_error，事件未丢失")
    void technicalExceptionSchedulesRetryAndKeepsEvent() {
        OutboxEvent event = event(7L, 1, 5);
        Instant originalNext = Instant.now().minusSeconds(10);
        event.setNextAttemptAt(originalNext);
        stubClaim(List.of(event));
        stubReload(event);
        doAnswer(invocation -> {
            throw new RuntimeException("connection reset by peer");
        }).when(handler).handle(anyLong(), any());

        newRelayWith(handler).relay();

        assertEquals(OutboxEvent.STATUS_FAILED, event.getStatus(),
                "技术异常应回到 FAILED 等待重试，而不是被静默标记成功或直接丢弃");
        assertEquals(1, event.getRetryCount(), "retry_count 在认领时已 +1，终态化不应重复递增");
        assertTrue(event.getNextAttemptAt().isAfter(originalNext),
                "退避后 next_attempt_at 必须后移，否则会立刻被重新捞取形成重试风暴");
        assertNotNull(event.getLastError(), "必须留下失败线索，否则资金事故无法排查");
        assertTrue(event.getLastError().contains("connection reset by peer"));
        assertTrue(event.getLastError().contains("RuntimeException"));
        assertNull(event.getLockedBy(), "终态化必须释放租约");
        assertNull(event.getLockedUntil(), "终态化必须释放租约");
        verify(outboxRepository).save(event);
    }

    @Test
    @DisplayName("退避时长随尝试次数指数增长并叠加抖动")
    void backoffGrowsExponentiallyWithJitter() {
        assertBackoffInRange(1, 1, 2, "第 1 次退避应约 1s");
        assertBackoffInRange(2, 4, 6, "第 2 次退避应约 5s");
        assertBackoffInRange(3, 22, 28, "第 3 次退避应约 25s");
        assertBackoffInRange(4, 112, 138, "第 4 次退避应约 125s");
        assertBackoffInRange(5, 562, 688, "第 5 次退避应约 625s");

        // 极端尝试次数不得溢出、不得超过上限
        for (int attempt : new int[]{10, 50, 1000, Integer.MAX_VALUE}) {
            long seconds = OutboxRelay.backoffSeconds(attempt);
            assertTrue(seconds >= 1 && seconds <= OutboxRelay.MAX_BACKOFF_SECONDS * 11 / 10,
                    "退避必须被上限夹住，attempt=" + attempt + " 得到 " + seconds);
        }
    }

    // ------------------------------------------------------------------
    // 3. 重试耗尽 → 死信
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重试耗尽 → DEAD 且 last_error 有内容（不再被认领）")
    void retryExhaustedGoesDeadWithLastError() {
        OutboxEvent event = event(9L, 5, 5);
        stubClaim(List.of(event));
        stubReload(event);
        doAnswer(invocation -> {
            throw new RuntimeException("deadlock detected");
        }).when(handler).handle(anyLong(), any());

        newRelayWith(handler).relay();

        assertEquals(OutboxEvent.STATUS_DEAD, event.getStatus(),
                "retry_count 已达 max_attempts，应进死信而不是无限重试");
        assertNotNull(event.getLastError());
        assertTrue(event.getLastError().contains("deadlock detected"));
        assertEquals(5, event.getRetryCount(), "进 DEAD 时不应再递增计数");
        assertNull(event.getProcessedAt(), "未成功处理不应写 processed_at");
    }

    // ------------------------------------------------------------------
    // 4. 业务跳过 → 直接成功，不重试
    // ------------------------------------------------------------------

    @Test
    @DisplayName("handler 正常返回（业务挂起）→ 直接 PUBLISHED，不重试、不计错误")
    void businessSkipIsTreatedAsSuccess() {
        OutboxEvent event = event(11L, 1, 5);
        Instant originalNext = Instant.now().minusSeconds(3);
        event.setNextAttemptAt(originalNext);
        stubClaim(List.of(event));
        stubReload(event);
        // handler 什么都不做 = 业务决定挂起（如提成规则缺失，需人工补配置后另行处理）

        newRelayWith(handler).relay();

        assertEquals(OutboxEvent.STATUS_PUBLISHED, event.getStatus(),
                "业务挂起是业务终局，不是事件失败；重试毫无意义，还会把 outbox 打成重试风暴");
        assertTrue(event.isPublished(), "需向后兼容双写 published");
        assertNotNull(event.getProcessedAt());
        assertNotNull(event.getPublishedAt());
        assertNull(event.getLastError(), "成功不应残留错误");
        assertEquals(originalNext, event.getNextAttemptAt(), "成功不应改写 next_attempt_at");
        assertNull(event.getLockedUntil(), "成功必须释放租约");
    }

    // ------------------------------------------------------------------
    // 5. 无匹配 handler → DEAD，不空转
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无匹配 handler → 直接 DEAD + NO_HANDLER 线索，不在活跃队列里空转")
    void noHandlerGoesDeadImmediately() {
        OutboxEvent event = event(13L, 1, 5);
        Instant originalNext = Instant.now().minusSeconds(3);
        event.setNextAttemptAt(originalNext);
        stubClaim(List.of(event));
        stubReload(event);
        // handler 注册的是别的事件类型
        OutboxHandler other = mock(OutboxHandler.class);
        when(other.eventType()).thenReturn("SOMETHING_ELSE");

        newRelayWith(other).relay();

        assertEquals(OutboxEvent.STATUS_DEAD, event.getStatus(),
                "零消费者是部署缺口，不是瞬时故障；留在 NEW/FAILED 只会每 2 秒空转一次并刷爆日志");
        assertNotNull(event.getLastError());
        assertTrue(event.getLastError().startsWith(OutboxRelay.NO_HANDLER_ERROR),
                "错误应以 NO_HANDLER 开头，便于死信台筛选：");
        assertTrue(event.getLastError().contains(TYPE));
        assertEquals(originalNext, event.getNextAttemptAt(), "不应退避重试（重试也不会凭空冒出 handler）");
        verify(other, never()).handle(anyLong(), any());
        // 关键：DEAD 不在认领条件 status IN ('NEW','FAILED') 内 → 后续轮次不会再捞到它
        assertFalse(event.getStatus().equals(OutboxEvent.STATUS_NEW)
                || event.getStatus().equals(OutboxEvent.STATUS_FAILED));
    }

    @Test
    @DisplayName("一个 handler 都不存在时也能安全运行（不抛异常、事件进 DEAD）")
    void survivesWithNoHandlerBeansAtAll() {
        OutboxEvent event = event(15L, 1, 5);
        stubClaim(List.of(event));
        stubReload(event);
        when(handlerProvider.iterator()).thenReturn(List.<OutboxHandler>of().iterator());

        OutboxRelay relay = new OutboxRelay(outboxRepository, handlerProvider, transactionManager);

        relay.relay();
        assertEquals(OutboxEvent.STATUS_DEAD, event.getStatus());
    }

    // ------------------------------------------------------------------
    // 6. 不可重试异常 / 异常隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不可重试异常（IllegalArgumentException）→ 立即 DEAD，不浪费重试预算")
    void nonRetryableErrorGoesDeadImmediately() {
        OutboxEvent event = event(17L, 1, 5);
        stubClaim(List.of(event));
        stubReload(event);
        doAnswer(invocation -> {
            throw new IllegalArgumentException("bad payload json");
        }).when(handler).handle(anyLong(), any());

        newRelayWith(handler).relay();

        assertEquals(OutboxEvent.STATUS_DEAD, event.getStatus(),
                "编程/数据错误重来一次也不会变好，应立刻进死信等人工介入");
        assertTrue(event.getLastError().contains("bad payload json"));
    }

    @Test
    @DisplayName("Error（非 Exception）也必须被 catch(Throwable) 兜住，不得冲掉调度线程")
    void errorIsCapturedAndGoesDead() {
        OutboxEvent event = event(19L, 1, 5);
        stubClaim(List.of(event));
        stubReload(event);
        doAnswer(invocation -> {
            throw new StackOverflowError("boom");
        }).when(handler).handle(anyLong(), any());

        newRelayWith(handler).relay();

        // 未逃逸出 relay()（否则 @Scheduled 线程被打断，outbox 整体停摆）
        assertEquals(OutboxEvent.STATUS_DEAD, event.getStatus());
        assertTrue(event.getLastError().contains("StackOverflowError"));
    }

    @Test
    @DisplayName("单条失败不影响同批其它事件（逐条独立事务）")
    void oneFailureDoesNotAffectOtherEventsInSameBatch() {
        OutboxEvent failing = event(21L, 1, 5);
        OutboxEvent succeeding = event(22L, 1, 5);
        stubClaim(List.of(failing, succeeding));
        stubReload(failing);
        stubReload(succeeding);
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            if (id == 21L) {
                throw new RuntimeException("transient db error");
            }
            return null;
        }).when(handler).handle(anyLong(), any());

        newRelayWith(handler).relay();

        assertEquals(OutboxEvent.STATUS_FAILED, failing.getStatus(), "失败的一条应安排重试");
        assertEquals(OutboxEvent.STATUS_PUBLISHED, succeeding.getStatus(),
                "同批其它事件不应受牵连（改造前整批一个事务，这里会一起回滚）");
    }

    // ------------------------------------------------------------------
    // 7. 工具方法
    // ------------------------------------------------------------------

    @Test
    @DisplayName("异常摘要含类名与堆栈首行，并截断到上限")
    void describeIncludesClassAndFirstStackTraceLineAndTruncates() {
        RuntimeException ex = new RuntimeException("x".repeat(5000));
        String text = OutboxRelay.describe(ex);

        assertTrue(text.startsWith("java.lang.RuntimeException: "));
        assertTrue(text.contains("| at "), "应带堆栈首行，否则死信台无法定位抛出点");
        assertEquals(OutboxRelay.MAX_ERROR_LENGTH, text.length());
    }

    @Test
    @DisplayName("实例标识非空且不超过 locked_by 列宽 64")
    void instanceIdIsBounded() {
        String id = OutboxRelay.resolveInstanceId();
        assertTrue(id.length() > 0 && id.length() <= 64, "locked_by 列宽 VARCHAR(64)，超长会写库失败");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private OutboxRelay newRelayWith(OutboxHandler... handlers) {
        when(handlerProvider.iterator()).thenReturn(List.<OutboxHandler>of(handlers).iterator());
        return new OutboxRelay(outboxRepository, handlerProvider, transactionManager);
    }

    private static OutboxEvent event(long id, int retryCount, int maxAttempts) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType("FULFILLMENT_ORDER")
                .aggregateId(id)
                .eventType(TYPE)
                .payloadJson("{}")
                .status(retryCount <= 1 ? OutboxEvent.STATUS_NEW : OutboxEvent.STATUS_FAILED)
                .retryCount(retryCount)
                .maxAttempts(maxAttempts)
                .nextAttemptAt(Instant.now())
                .build();
    }

    /** 让 entity 变更可被 {@code save} 捕获：终态化时先 findById 再改同一实例。 */
    private void stubReload(OutboxEvent event) {
        when(outboxRepository.findById(event.getId())).thenReturn(Optional.of(event));
    }

    private void stubClaim(List<OutboxEvent> events) {
        List<Long> ids = events.stream().map(OutboxEvent::getId).toList();
        when(outboxRepository.findClaimableIds(any(), anyInt())).thenReturn(ids);
        when(outboxRepository.markClaimed(any(), any(), any())).thenReturn(ids.size());
        when(outboxRepository.findAllById(any())).thenReturn(events);
    }

    private static void assertBackoffInRange(int attempt, long minInclusive, long maxInclusive, String message) {
        long seconds = OutboxRelay.backoffSeconds(attempt);
        assertTrue(seconds >= minInclusive && seconds <= maxInclusive,
                message + "，实际 " + seconds + "s（含 ±10% 抖动）");
    }
}
