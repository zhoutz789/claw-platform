package com.claw.server.common.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Outbox 事件仓库（common 层，不依赖任何 domain 包）。 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 兼容旧写法：捞取所有未发布事件。
     *
     * <p><b>已废弃</b>：无分页、无上限，数据量大时会拖垮内存。{@link OutboxRelay} 自 V86 起改用
     * {@link #findClaimableIds} + {@link #markClaimed} 限量认领；本方法仅为兼容保留，请勿新增调用。
     */
    @Deprecated
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();

    /**
     * 认领查询 SQL：限量捞取「到期且无人持有租约」的事件 id，并<b>对命中的行加行锁</b>。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 是并发安全的核心：多实例同时认领时，各自跳过已被别人
     * 锁住的行 → 天然分片，无需 ShedLock / Redis / advisory lock 等任何外部组件。
     *
     * <p>⚠️ 必须在<b>读写事务内</b>执行：① {@code FOR UPDATE} 在 read-only 事务里 PG 会直接报错；
     * ② 锁只活到事务结束，必须与随后的 {@link #markClaimed} 在同一事务内，否则认领不具原子性。
     */
    String CLAIM_IDS_SQL = """
            SELECT id
              FROM outbox_events
             WHERE status IN ('NEW', 'FAILED')
               AND next_attempt_at <= :now
               AND (locked_until IS NULL OR locked_until < :now)
             ORDER BY created_at ASC
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """;

    /** 认领标记 SQL：占用租约并把 {@code retry_count} +1（崩溃也计次，防无限重投）。 */
    String MARK_CLAIMED_SQL = """
            UPDATE outbox_events
               SET locked_by    = :owner,
                   locked_until = :leaseUntil,
                   retry_count  = retry_count + 1
             WHERE id IN (:ids)
            """;

    /**
     * 选出本轮可处理的事件 id，并对这些行加排他锁（跳过已被其它实例锁住的行）。
     *
     * @param now   当前时间（由调用方传入，便于测试与批量内时间一致）
     * @param limit 本轮上限
     * @return 已加锁的事件 id（可能为空）；调用方必须紧接着在同一事务内 {@link #markClaimed}
     */
    @Query(value = CLAIM_IDS_SQL, nativeQuery = true)
    List<Long> findClaimableIds(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * 为已加锁的事件行打上租约并递增尝试次数。
     *
     * @param ids        事件 id（来自 {@link #findClaimableIds}）
     * @param owner      实例标识
     * @param leaseUntil 租约到期时间
     * @return 更新行数
     */
    @Modifying
    @Query(value = MARK_CLAIMED_SQL, nativeQuery = true)
    int markClaimed(@Param("ids") List<Long> ids,
                    @Param("owner") String owner,
                    @Param("leaseUntil") Instant leaseUntil);
}
