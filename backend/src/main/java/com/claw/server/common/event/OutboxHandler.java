package com.claw.server.common.event;

/**
 * Outbox 事件处理器（按 {@code event_type} 精确路由）。
 *
 * <p><b>为什么要有这个接口</b>：原 {@code OutboxRelay} 把事件包装成 {@link DomainEvent} 后用
 * {@code ApplicationEventPublisher.publishEvent} 广播，存在两个致命问题：
 * <ol>
 *   <li>消费者若用 {@code @TransactionalEventListener}（默认 AFTER_COMMIT），监听器抛异常时
 *       relay 事务早已提交、{@code published=true} 已落库 → <b>事件永久丢失且无任何痕迹</b>；</li>
 *   <li>{@code publishEvent} 无法回答「有没有人消费」——本项目就是零消费者静默跑了很久，
 *       每条事件都被标记成功，实际什么都没发生。</li>
 * </ol>
 * 改为 relay 直接持有本接口的实例并按 {@code eventType()} 精确匹配后同步调用，异常能冒泡回
 * relay 决定终态，无匹配也能被显式识别（见 {@link OutboxRelay} 的 NO_HANDLER 策略）。
 *
 * <p><b>实现约定（写 handler 的人必读）</b>：
 * <ul>
 *   <li>实现类放在 <b>domain 层</b>（ArchUnit：{@code common} 不得依赖 {@code domain}）；</li>
 *   <li>必须自己开事务，推荐 {@code @Transactional(propagation = Propagation.REQUIRES_NEW)}，
 *       使整条资金链路是一个独立事务，与 relay 的终态化事务分离；</li>
 *   <li><b>业务上「挂起 / 跳过」必须正常返回，不许抛异常</b>。提成规则缺失、找不到收款户、
 *       订单当前不可结算等都属于此类——结算服务应自行落挂起记录后返回。抛异常只保留给
 *       <b>技术故障</b>（DB、锁、序列化等），否则会把 outbox 打成重试风暴并误进死信；</li>
 *   <li>必须幂等：relay 语义是 at-least-once（「处理已提交、标记未落库」这个窗口崩溃会重投一次），
 *       重投时必须能靠业务幂等键识别并跳过；</li>
 *   <li>{@code payloadJson} 只用于日志与追溯，<b>不得用其中的金额参与计算</b>，一律以 DB 为权威。</li>
 * </ul>
 */
public interface OutboxHandler {

    /**
     * 本处理器支持的事件类型，与 {@code outbox_events.event_type} <b>精确匹配</b>（大小写敏感）。
     *
     * @return 事件类型，如 {@code PICKUP_COMPLETED}
     */
    String eventType();

    /**
     * 同步处理一条 outbox 事件。
     *
     * @param eventId     outbox 事件 id（可直接用作下游 {@code source_event_id} 追溯）
     * @param payloadJson 事件载荷 JSON（可能为 {@code null}）
     * @throws RuntimeException 仅当发生<b>技术故障</b>时抛出；业务挂起请正常返回
     */
    void handle(long eventId, String payloadJson);
}
