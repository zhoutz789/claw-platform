package com.claw.server.common.event;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Outbox 事件行（对应 claw.outbox_events）。
 *
 * <p>与业务变更写入同一本地事务，保证「状态变更」与「事件发布」原子；
 * 由 {@link OutboxRelay} 异步认领、同步投递给 {@link OutboxHandler}，并按结果终态化。
 *
 * <p><b>V86 可靠性改造</b>：原表只有 {@code published} 布尔位，无法表达「处理中 / 待重试 /
 * 死信 / 为什么失败」，导致丢事件时查不到任何痕迹。新增 8 列（见 V86 迁移），语义如下：
 * <ul>
 *   <li>{@link #status} —— 状态机真源，relay 只认它；取值由 {@code ck_ob_status} 约束；</li>
 *   <li>{@link #retryCount} —— 已尝试次数，<b>认领时 +1</b>（不是失败时 +1），这样进程在
 *       「处理中崩溃」也计次，避免崩溃循环导致无限重投；</li>
 *   <li>{@link #maxAttempts} —— 重试上限，人工重投时可临时调大；</li>
 *   <li>{@link #nextAttemptAt} —— 下次可投时间，退避落点；</li>
 *   <li>{@link #lastError} —— 最后一次异常摘要，死信排障的唯一线索；</li>
 *   <li>{@link #lockedUntil} / {@link #lockedBy} —— 处理租约。租约已表达「正在处理」，
 *       故不再引入 {@code PROCESSING} 状态（否则进程崩溃后要额外写 reaper 才能解锁）；</li>
 *   <li>{@link #processedAt} —— 成功处理时间。</li>
 * </ul>
 * {@code published} 保留不动：{@link OutboxPublisher} 与存量代码仍在写它，relay 成功时也双写，
 * 用于向后兼容；<b>查询与判断一律以 {@code status} 为准</b>。
 */
@Entity
@Table(name = "outbox_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    /** 待投递（新建事件的初始状态）。 */
    public static final String STATUS_NEW = "NEW";
    /** 投递失败、等待退避重试（仍是可回收的活跃状态）。 */
    public static final String STATUS_FAILED = "FAILED";
    /** 投递成功（包含「业务挂起」——挂起是业务终局，不是事件失败）。 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 死信：不可重试或重试耗尽，需人工介入。 */
    public static final String STATUS_DEAD = "DEAD";

    /** 默认重试上限，与 V86 迁移里 {@code max_attempts} 的列默认值一致。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(columnDefinition = "text")
    private String payloadJson;

    @Builder.Default
    @Column(nullable = false)
    private boolean published = false;

    private Instant publishedAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** 状态机真源：NEW / FAILED / PUBLISHED / DEAD（见常量）。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = STATUS_NEW;

    /** 已尝试次数，认领时 +1。 */
    @Builder.Default
    @Column(nullable = false)
    private int retryCount = 0;

    /** 重试上限，耗尽后进 DEAD。 */
    @Builder.Default
    @Column(nullable = false)
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    /** 下次可投时间（退避落点）。 */
    @Builder.Default
    @Column(nullable = false)
    private Instant nextAttemptAt = Instant.now();

    /** 最后一次异常摘要（类名 + message + 堆栈首行）。 */
    @Column(columnDefinition = "text")
    private String lastError;

    /** 处理租约到期时间；为空表示未持有租约。 */
    private Instant lockedUntil;

    /** 持租实例标识（{@code host:pid}）。 */
    @Column(length = 64)
    private String lockedBy;

    /** 成功处理时间。 */
    private Instant processedAt;
}
