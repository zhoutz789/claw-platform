package com.claw.server.common.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Outbox 转发器（V86 重写）：可靠地把 {@code outbox_events} 投递给 {@link OutboxHandler}。
 *
 * <h2>处理流程（三段独立事务）</h2>
 * <ol>
 *   <li><b>认领（TX-A，REQUIRES_NEW 短事务）</b>：{@code SELECT id ... FOR UPDATE SKIP LOCKED LIMIT 100}
 *       → {@code UPDATE ... SET locked_by, locked_until, retry_count+1}。锁与更新在同一事务内，
 *       因此同一行不可能被两个实例同时认领；租约保证进程崩溃后行能被自动回收。</li>
 *   <li><b>投递（TX-B，handler 自开 REQUIRES_NEW）</b>：按 {@code event_type} 精确匹配 handler 并同步调用。
 *       handler 自己开事务，绝不与 relay 同事务——否则批次中第 N 条失败会回滚前 N−1 条已提交的资金事务。</li>
 *   <li><b>终态化（TX-C，REQUIRES_NEW 短事务）</b>：成功 → {@code PUBLISHED}；技术异常且有余量 →
 *       {@code FAILED} + 退避；不可重试或耗尽 → {@code DEAD} 并保留 {@code last_error}。</li>
 * </ol>
 *
 * <h2>为什么不再用 {@code ApplicationEventPublisher}</h2>
 * 消费者若用 {@code @TransactionalEventListener}（默认 AFTER_COMMIT），relay 事务已提交、
 * {@code published=true} 已落库，监听器抛异常只被 Spring 记一行日志 → <b>事件永久丢失、无任何痕迹</b>。
 * 资金链路上这是事故。改为同步调用显式 handler 后，异常能冒泡回 relay 决定终态。
 *
 * <h2>不丢事件的保证</h2>
 * 状态以 DB 为唯一真相源，且<b>绝不「先标记后处理」</b>（那正是丢事件的事故形态）。
 * 唯一无法消除的是「TX-B 已提交、TX-C 未执行」的重复投递窗口 —— 这是 transactional outbox 的
 * 标准 at-least-once 语义，必须由 handler 侧幂等吸收。
 */
@Slf4j
@Component
public class OutboxRelay {

    /** 单轮最多认领条数：避免长事务与内存膨胀；{@code fixedDelay=2s} 下理论吞吐 50 事件/秒。 */
    static final int BATCH_SIZE = 100;

    /**
     * 处理租约时长：必须远大于单条处理的最坏耗时（资金事务通常 &lt; 1s，留 600× 余量）。
     * 到期仍未终态化 → 行可被重新认领，进程崩溃因此能自愈，无需 reaper 定时任务。
     */
    static final Duration LEASE = Duration.ofMinutes(10);

    /** 退避上限（秒）：10 分钟，避免长尾事件把重试拖到几小时后。 */
    static final long MAX_BACKOFF_SECONDS = 3600L;

    /** {@code last_error} 最大长度，与 VARCHAR/TEXT 容量及可读性权衡后的取值。 */
    static final int MAX_ERROR_LENGTH = 2000;

    /** 无匹配 handler 时的错误前缀（死信台据此筛部署缺口）。 */
    static final String NO_HANDLER_ERROR = "NO_HANDLER";

    private final OutboxEventRepository outboxRepository;
    private final Map<String, OutboxHandler> handlers;
    private final TransactionTemplate newTransaction;
    private final String instanceId;

    public OutboxRelay(OutboxEventRepository outboxRepository,
                       ObjectProvider<OutboxHandler> handlerProvider,
                       PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.handlers = indexByEventType(handlerProvider);
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.instanceId = resolveInstanceId();
        log.info("OutboxRelay 已启动 instance={} handlers={} batchSize={} lease={}",
                this.instanceId, this.handlers.keySet(), BATCH_SIZE, LEASE);
    }

    /**
     * 一轮投递。方法上<b>刻意没有</b> {@code @Transactional}：每个阶段自己开短事务，
     * 避免「整批一个事务，任一行失败导致整批回滚、下游已处理过的事件被重投」。
     */
    @Scheduled(fixedDelay = 2000)
    public void relay() {
        List<OutboxEvent> claimed;
        try {
            claimed = claimBatch();
        } catch (Throwable t) {
            // 认领阶段失败（DB 抖动等）只影响本轮，不重试——下一轮 2s 后自然会再来。
            log.error("outbox 认领失败，本轮跳过: {}", describe(t), t);
            return;
        }
        if (claimed.isEmpty()) {
            return;
        }
        int published = 0;
        int retried = 0;
        int dead = 0;
        for (OutboxEvent event : claimed) {
            Outcome outcome = processOne(event);
            switch (outcome) {
                case PUBLISHED -> published++;
                case RETRY -> retried++;
                case DEAD -> dead++;
            }
        }
        log.info("outbox relay 本轮结束 claimed={} published={} retry={} dead={}",
                claimed.size(), published, retried, dead);
    }

    /**
     * 原子认领一批事件：选 id、加锁、打租约并递增尝试次数，<b>全部在同一个短事务内</b>。
     *
     * @return 已认领的事件（含递增后的 {@code retry_count}）；无可用事件时返回空列表
     */
    List<OutboxEvent> claimBatch() {
        List<OutboxEvent> claimed = newTransaction.execute(status -> {
            Instant now = Instant.now();
            List<Long> ids = outboxRepository.findClaimableIds(now, BATCH_SIZE);
            if (ids.isEmpty()) {
                return List.<OutboxEvent>of();
            }
            outboxRepository.markClaimed(ids, instanceId, now.plus(LEASE));
            return outboxRepository.findAllById(ids);
        });
        return claimed == null ? List.of() : claimed;
    }

    /** 处理单条事件：任何异常都在此收敛，绝不上抛影响同批其它事件。 */
    private Outcome processOne(OutboxEvent event) {
        long eventId = event.getId();
        String eventType = event.getEventType();
        OutboxHandler handler = handlers.get(eventType);

        if (handler == null) {
            // 策略：直接 DEAD，不做退避重试。
            // 理由：没有消费者是「部署缺口 / 编码错误」，不是瞬时故障，重试一万次也不会凭空冒出
            // handler；若留在 NEW/FAILED 反复退避，就是在死循环里空转，还会持续刷错误日志并
            // 撑大活跃索引。进 DEAD 后行仍在（数据没丢）、last_error 有明确线索、可被人工重投，
            // 一旦补上 handler 重投即可成功。
            String error = NO_HANDLER_ERROR + ": eventType=" + eventType;
            log.error("outbox 事件无匹配 handler，直接进 DEAD（零消费者 = 部署缺口，重试无意义）"
                    + " eventId={} type={}", eventId, eventType);
            markDead(eventId, error);
            return Outcome.DEAD;
        }

        try {
            handler.handle(eventId, event.getPayloadJson());
            // handler 正常返回即视为成功 —— 包括「业务挂起」：挂起是业务终局（如提成规则缺失，
            // 需人工补配置），不是事件失败，重试毫无意义，故不消耗重试次数也不记 last_error。
            markPublished(eventId);
            return Outcome.PUBLISHED;
        } catch (Throwable t) {
            String error = describe(t);
            int attempt = event.getRetryCount();
            if (isRetryable(t) && attempt < event.getMaxAttempts()) {
                long backoffSeconds = backoffSeconds(attempt);
                Instant nextAttemptAt = Instant.now().plusSeconds(backoffSeconds);
                markRetry(eventId, nextAttemptAt, error);
                log.warn("outbox 事件投递失败，退避后重试 eventId={} type={} attempt={}/{} nextIn={}s err={}",
                        eventId, eventType, attempt, event.getMaxAttempts(), backoffSeconds, error);
                return Outcome.RETRY;
            }
            log.error("outbox 事件投递失败且不可重试/重试已耗尽 → DEAD eventId={} type={} attempt={}/{} err={}",
                    eventId, eventType, attempt, event.getMaxAttempts(), error, t);
            markDead(eventId, error);
            return Outcome.DEAD;
        }
    }

    /**
     * 是否值得重试。
     *
     * <p>只把<b>技术故障</b>判为可重试（DB、锁、网络、序列化、未知异常）；{@code Error} 与
     * {@code IllegalArgumentException}（含子类如 {@code NumberFormatException}）属编程/环境错误，
     * 重来一次也不会变好，直接进 DEAD 免得占用重试预算。
     *
     * <p>注意：业务规则类异常由 handler 自行消化后正常返回（见 {@link OutboxHandler} 约定），
     * 不要靠抛异常表达「业务挂起」。
     */
    static boolean isRetryable(Throwable t) {
        return !(t instanceof Error) && !(t instanceof IllegalArgumentException);
    }

    /**
     * 指数退避（秒）：{@code min(5^(attempt-1), 3600)} → 1s / 5s / 25s / 125s / 625s，
     * 再叠加 ±10% 抖动防惊群。在 Java 里算好再入库，不在 SQL 里写幂运算（可读、可单测）。
     *
     * @param attempt 当前尝试次数（即 {@code retry_count}，认领时已 +1，最小为 1）
     * @return 退避秒数，至少 1 秒
     */
    static long backoffSeconds(int attempt) {
        long base = 1L;
        for (int i = 1; i < attempt; i++) {
            base *= 5L;
            if (base >= MAX_BACKOFF_SECONDS) {
                base = MAX_BACKOFF_SECONDS;
                break;
            }
        }
        base = Math.min(base, MAX_BACKOFF_SECONDS);
        double jitter = 0.9D + 0.2D * ThreadLocalRandom.current().nextDouble();
        return Math.max(1L, Math.round(base * jitter));
    }

    /**
     * 异常摘要：类名 + message + 堆栈首行，总长截断到 {@value #MAX_ERROR_LENGTH} 字符。
     *
     * <p><b>先给堆栈首行留额度</b>：message 可能极长（如把整个 JSON payload 拼进异常），
     * 若从头拼再整体截断，最有价值的「抛在哪一行」会被 message 挤掉 —— 死信台就只剩一句
     * 没头没尾的话。因此先扣掉类名与堆栈行的长度，剩余预算才给 message。
     */
    static String describe(Throwable t) {
        String head = t.getClass().getName() + ": ";
        String stackLine = "";
        StackTraceElement[] stack = t.getStackTrace();
        if (stack.length > 0) {
            stackLine = " | at " + stack[0];
        }
        int budget = MAX_ERROR_LENGTH - head.length() - stackLine.length();
        String message = t.getMessage();
        if (message == null) {
            message = "";
        }
        if (budget < 0) {
            // 类名或堆栈行本身就超长（极罕见）：优先保留类名，其次堆栈行
            budget = 0;
        }
        if (message.length() > budget) {
            message = message.substring(0, budget);
        }
        return head + message + stackLine;
    }

    // ------------------------------------------------------------------
    // 终态化（各自一个独立的短事务）
    // ------------------------------------------------------------------

    /** 终态化：成功。兼容双写 {@code published/published_at}。 */
    private void markPublished(long eventId) {
        newTransaction.execute(status -> {
            outboxRepository.findById(eventId).ifPresent(e -> {
                Instant now = Instant.now();
                e.setStatus(OutboxEvent.STATUS_PUBLISHED);
                e.setPublished(true);
                e.setPublishedAt(now);
                e.setProcessedAt(now);
                e.setLastError(null);
                e.setLockedBy(null);
                e.setLockedUntil(null);
                outboxRepository.save(e);
            });
            return null;
        });
    }

    /** 终态化：待重试。状态回到 {@code FAILED}，{@code next_attempt_at} 后移，释放租约。 */
    private void markRetry(long eventId, Instant nextAttemptAt, String error) {
        newTransaction.execute(status -> {
            outboxRepository.findById(eventId).ifPresent(e -> {
                e.setStatus(OutboxEvent.STATUS_FAILED);
                e.setNextAttemptAt(nextAttemptAt);
                e.setLastError(error);
                e.setLockedBy(null);
                e.setLockedUntil(null);
                outboxRepository.save(e);
            });
            return null;
        });
    }

    /** 终态化：死信。释放租约，保留 {@code last_error} 供人工排查。 */
    private void markDead(long eventId, String error) {
        newTransaction.execute(status -> {
            outboxRepository.findById(eventId).ifPresent(e -> {
                e.setStatus(OutboxEvent.STATUS_DEAD);
                e.setLastError(error);
                e.setLockedBy(null);
                e.setLockedUntil(null);
                outboxRepository.save(e);
            });
            return null;
        });
    }

    /** 按 {@code eventType()} 建索引；重复注册只保留第一个并报错（避免静默覆盖）。 */
    private static Map<String, OutboxHandler> indexByEventType(ObjectProvider<OutboxHandler> provider) {
        Map<String, OutboxHandler> byType = new LinkedHashMap<>();
        for (OutboxHandler handler : provider) {
            OutboxHandler previous = byType.putIfAbsent(handler.eventType(), handler);
            if (previous != null) {
                log.error("outbox handler 事件类型冲突 type={} keep={} ignored={}",
                        handler.eventType(), previous.getClass().getName(), handler.getClass().getName());
            }
        }
        return Map.copyOf(byType);
    }

    /**
     * 实例标识 {@code host:pid}，用于 {@code locked_by} 排障。
     * 截断到 64 字符以内（列宽 {@code VARCHAR(64)}）。
     */
    static String resolveInstanceId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int at = runtimeName.indexOf('@');
        String pid = at > 0 ? runtimeName.substring(0, at) : runtimeName;
        String host = at > 0 ? runtimeName.substring(at + 1) : "unknown";
        String id = host + ":" + pid;
        return id.length() <= 64 ? id : id.substring(0, 64);
    }

    /** 单条事件的投递结果。 */
    private enum Outcome {
        /** 投递成功（含业务挂起）。 */
        PUBLISHED,
        /** 技术异常，已安排退避重试。 */
        RETRY,
        /** 不可重试或重试耗尽，进入死信。 */
        DEAD
    }
}
