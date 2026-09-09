package com.claw.server.integration;

import com.claw.server.common.event.OutboxEvent;
import com.claw.server.common.event.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbox 认领 SQL 在<b>真实 PostgreSQL</b> 上的行为验证（V86）。
 *
 * <p>单元测试只能断言 SQL 文本，无法证明它真能在 PG 上跑通（命名参数绑定、
 * {@code FOR UPDATE SKIP LOCKED} 与 {@code LIMIT} 的语序、read-only 事务限制等都可能
 * 在真实执行时才暴露）。这里用真库补齐这一层：
 * <ul>
 *   <li>认领 SQL 可执行，命中到期且无租约的行；</li>
 *   <li>{@code markClaimed} 打上租约并把 {@code retry_count} +1；</li>
 *   <li>租约未过期的行不可再被认领；</li>
 *   <li><b>被另一个会话锁住的行会被 SKIP LOCKED 跳过</b> —— 这是多实例互斥的核心保证。</li>
 * </ul>
 */
class OutboxRelayClaimIT extends AbstractIntegrationTest {

    private static final String EVENT_TYPE = "PICKUP_COMPLETED";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("认领 SQL 在真实 PG 上可执行，并按租约/到期过滤")
    void claimQueryRunsOnRealPostgresAndHonoursLease() {
        long eventId = insertEvent(OutboxEvent.STATUS_NEW);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            List<Long> claimable = tx.execute(status -> outboxRepository.findClaimableIds(now(), 10));
            assertTrue(claimable.contains(eventId), "到期且无租约的事件应被认领");

            tx.execute(status -> {
                outboxRepository.markClaimed(List.of(eventId), "it-host:1", now().plusSeconds(600));
                return null;
            });

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT retry_count, locked_by, locked_until FROM claw.outbox_events WHERE id = ?", eventId);
            assertEquals(1, ((Number) row.get("retry_count")).intValue(),
                    "retry_count 必须在认领时 +1");
            assertEquals("it-host:1", row.get("locked_by"));
            assertNotNull(row.get("locked_until"), "认领必须打上租约");

            List<Long> whileLeased = tx.execute(status -> outboxRepository.findClaimableIds(now(), 10));
            assertFalse(whileLeased.contains(eventId),
                    "租约未过期的行不得再被认领（否则多实例会并发重复投递）");

            jdbc.update("UPDATE claw.outbox_events SET locked_until = now() - interval '1 minute' WHERE id = ?", eventId);
            List<Long> afterLeaseExpired = tx.execute(status -> outboxRepository.findClaimableIds(now(), 10));
            assertTrue(afterLeaseExpired.contains(eventId),
                    "租约过期后必须可回收（进程崩溃自愈）");
        } finally {
            jdbc.update("DELETE FROM claw.outbox_events WHERE id = ?", eventId);
        }
    }

    @Test
    @DisplayName("被另一个会话锁住的行会被 FOR UPDATE SKIP LOCKED 跳过（多实例不重复投递）")
    void lockedRowsAreSkippedByConcurrentSession() throws Exception {
        long eventId = insertEvent(OutboxEvent.STATUS_NEW);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement st = other.createStatement()) {
                // 模拟另一个实例正持有该行的行锁
                st.execute("SELECT id FROM claw.outbox_events WHERE id = " + eventId + " FOR UPDATE");

                List<Long> claimable = tx.execute(status -> outboxRepository.findClaimableIds(now(), 10));
                assertFalse(claimable.contains(eventId),
                        "同一行被另一个会话锁住时必须被 SKIP LOCKED 跳过，绝不能两个 worker 同时认领");
            } finally {
                other.rollback();
            }
        } finally {
            jdbc.update("DELETE FROM claw.outbox_events WHERE id = ?", eventId);
        }
    }

    @Test
    @DisplayName("LIMIT 生效：单轮认领条数不超过传入上限")
    void claimRespectsLimit() {
        long first = insertEvent(OutboxEvent.STATUS_NEW);
        long second = insertEvent(OutboxEvent.STATUS_NEW);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            List<Long> claimable = tx.execute(status -> outboxRepository.findClaimableIds(now(), 1));
            assertEquals(1, claimable.size(), "LIMIT 必须生效，否则一次会拖垮内存（改造前的 D2 缺陷）");
        } finally {
            jdbc.update("DELETE FROM claw.outbox_events WHERE id IN (?, ?)", first, second);
        }
    }

    @Test
    @DisplayName("OutboxEvent 实体的 8 个新字段与真实表列一一对应")
    void entityNewColumnsMapToRealTable() {
        // ddl-auto: none —— 列名由 Flyway 固定，若 Hibernate 隐式命名推错（如 retryCount →
        // retrycount），本地单测发现不了，只有真库读写才炸。这里用真库锁定映射。
        Instant leaseUntil = Instant.now().plusSeconds(60);
        OutboxEvent saved = outboxRepository.save(OutboxEvent.builder()
                .aggregateType("FULFILLMENT_ORDER")
                .aggregateId(4242L)
                .eventType(EVENT_TYPE)
                .payloadJson("{}")
                .status(OutboxEvent.STATUS_FAILED)
                .retryCount(3)
                .maxAttempts(9)
                .nextAttemptAt(Instant.now())
                .lastError("boom")
                .lockedBy("it-host:9")
                .lockedUntil(leaseUntil)
                .build());
        try {
            OutboxEvent reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
            assertEquals(OutboxEvent.STATUS_FAILED, reloaded.getStatus());
            assertEquals(3, reloaded.getRetryCount());
            assertEquals(9, reloaded.getMaxAttempts());
            assertEquals("boom", reloaded.getLastError());
            assertEquals("it-host:9", reloaded.getLockedBy());
            assertNotNull(reloaded.getNextAttemptAt());
            assertNotNull(reloaded.getLockedUntil());
        } finally {
            jdbc.update("DELETE FROM claw.outbox_events WHERE id = ?", saved.getId());
        }
    }

    /** 插入一条待投事件（{@code next_attempt_at} 已在过去），返回其 id。 */
    private long insertEvent(String status) {
        Long id = jdbc.queryForObject("""
                        INSERT INTO claw.outbox_events
                            (aggregate_type, aggregate_id, event_type, payload_json, status, next_attempt_at)
                        VALUES ('FULFILLMENT_ORDER', 999999, ?, '{}', ?, now() - interval '1 minute')
                        RETURNING id
                        """,
                Long.class, EVENT_TYPE, status);
        assertNotNull(id, "插入 outbox 事件失败");
        return id;
    }

    private static java.time.Instant now() {
        return java.time.Instant.now();
    }
}
