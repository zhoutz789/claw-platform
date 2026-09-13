package com.claw.server.domain.payment;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ClearingRequests;
import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.enums.PayStatus;
import com.claw.server.common.event.OutboxHandler;
import com.claw.server.domain.clearing.ClearingInstruction;
import com.claw.server.domain.clearing.ClearingInstructionService;
import com.claw.server.domain.clearing.ClearingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * R1 扫码购分账接入（L4/L5 边界）：消费 {@code PAYMENT_PAID} 事件，把「用户扫码购买寄售商品」的
 * 收单金额按 {@code settlement_rule} 分账到各收款方，并把清分指令推进到终态。
 *
 * <h2>为什么走 Outbox 而不是直接写在 {@code onCallback} 里</h2>
 * 落地设计 §5.1 步骤 4 写的是「onCallback 内调用 ClearingService.settle」。直接内联有两个问题：
 * <ol>
 *   <li>清分是<b>资金链路</b>，必须「不丢」：内联时若清分失败会连带回滚支付主流程，
 *       或反过来把支付主流程的错误语义污染成清分错误；</li>
 *   <li>违反项目已确立的可靠性约定（{@code common/event} 的 {@link OutboxHandler} 契约）：
 *       资金类联动一律落库、可重试、有死信。{@code FulfillmentSettlementService} 就是范例。</li>
 * </ol>
 * 因此 {@code PaymentService.onCallback} <b>只做一件新增的事</b>：在支付成功、同一事务内发布
 * {@code PAYMENT_PAID} outbox 事件（{@code OutboxPublisher} 为 MANDATORY 传播，与支付变更原子）。
 * 订单/支付主流程（状态机、幂等、账本入账）<b>一行未改</b>；清分的全部动作在本处理器内完成。
 *
 * <h2>事务与失败语义（务必区分两类失败）</h2>
 * <ul>
 *   <li>{@code REQUIRES_NEW} 独立事务：与 relay 的终态化事务分离；</li>
 *   <li><b>清分前置失败</b>（无规则命中、收款方类型未映射、入参非法）→ {@code ClearingService.settle}
 *       抛 {@link BizException} <b>向上冒泡</b>：此时本事务整体回滚、<b>账本零副作用</b>，交给 relay 退避重试，
 *       耗尽后进死信台（配置缺口必须对运维可见，不能静默 PUBLISHED 把清分悄悄丢掉）；</li>
 *   <li><b>通道侧失败/降级</b>（收款方未绑定 ABA、通道不支持 at source、通道业务拒付）→ 账本已提交，
 *       <b>绝不能回滚账本</b>：把指令落 {@code FAILED}（即「挂起记录」）后<b>正常返回</b>，不触发重投，
 *       留待人工修复或周期批量代付（路径 C）；</li>
 *   <li>仅技术故障（DB/锁/序列化）才允许冒泡触发重试。</li>
 * </ul>
 *
 * <h2>幂等</h2>
 * 三重兜底：{@code ClearingService.settle} 按 {@code basisRef} 预检既有指令；账本 {@code bizType+bizRef}
 * 唯一约束（40950）；{@code clearing_instruction.idem_key} 唯一。relay 的 at-least-once 重投因此安全。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsignmentClearingHandler implements OutboxHandler {

    /** 本处理器订阅的事件类型（与 {@code outbox_events.event_type} 精确匹配）。 */
    public static final String EVENT_TYPE = "PAYMENT_PAID";

    /** R1 扫码购的业务场景（{@code settlement_rule.biz_scene}）。 */
    public static final String BIZ_SCENE = "CONSIGNMENT_SCAN";

    /** 收单币种（与 {@code AccountService} 现行 USD 口径一致）。 */
    public static final String CURRENCY = "USD";

    /** 通道提示：优先选取该通道号的实现。 */
    private static final String CHANNEL_HINT = AbaClearingChannel.CHANNEL_CODE;

    private final PaymentOrderRepository paymentOrderRepository;
    private final ClearingService clearingService;
    private final ClearingInstructionService clearingInstructionService;
    private final ChannelSplitPlanner channelSplitPlanner;
    private final PayeeAbaRefResolver payeeAbaRefResolver;
    private final List<ClearingChannelGateway> channelGateways;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    /**
     * 处理一条「支付成功」事件：R1 扫码购分账 → 通道下发 → 指令推进到 SETTLED。
     *
     * @param eventId     outbox 事件 id（日志追溯）
     * @param payloadJson 事件载荷 JSON（<b>仅取 orderNo 定位订单</b>；金额一律以 DB 的
     *                    {@code PaymentOrder.amountUsd} 为权威，不使用载荷中的任何金额）
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(long eventId, String payloadJson) {
        String orderNo = parseOrderNo(payloadJson);
        if (orderNo == null) {
            log.error("PAYMENT_PAID 事件解析失败，放弃处理 eventId={}", eventId);
            return;
        }
        PaymentOrder order = paymentOrderRepository.findByOrderNo(orderNo).orElse(null);
        if (order == null) {
            log.warn("支付单不存在，跳过 R1 分账 eventId={} orderNo={}", eventId, orderNo);
            return;
        }
        if (order.getStatus() != PayStatus.PAID) {
            // 幂等/状态护栏：未支付成功的单不得分账（重复回调、退款后重投等）
            log.warn("支付单非 PAID，跳过 R1 分账 orderNo={} status={}", orderNo, order.getStatus());
            return;
        }

        ClearingChannelGateway gateway = resolveGateway(CHANNEL_HINT);
        if (gateway == null) {
            log.warn("无可用清分通道实现，R1 分账降级为周期批量代付 basisRef={}", orderNo);
            return;
        }

        // ① 账本侧分账（唯一写账出口；失败冒泡 → 整事务回滚 → relay 重试/死信）
        ClearingService.ClearingResult result = clearingService.settle(new ClearingRequests.Settle(
                ClearingScene.R1, BIZ_SCENE, orderNo, order.getAmountUsd(), null, CURRENCY,
                ClearingMode.AT_SOURCE, gateway.channelCode()));
        if (result.idempotentReplay()) {
            log.debug("R1 分账已处理过（幂等重放，不重复入账）basisRef={}", orderNo);
            return;
        }
        List<ClearingInstruction> instructions = result.instructions();

        // ② 通道下发计划：尾差吸收 + ≤10 受益人分片 + 收款方 ABA 前置校验
        ChannelSplitPlanner.ChannelPlan plan;
        try {
            plan = channelSplitPlanner.plan(orderNo, order.getAmountUsd(), result.legs(), CURRENCY,
                    payeeAbaRefResolver);
        } catch (BizException e) {
            // 收款方未绑定 ABA 账户 → 准入不通过：账本权益已实时，指令保留 CREATED 待周期批量(T+7)代付
            log.warn("R1 分账降级（收款方 ABA 前置校验不通过）basisRef={} reason={}", orderNo, e.getMessage());
            return;
        }

        if (!gateway.supports(ClearingMode.AT_SOURCE)) {
            log.warn("通道 {} 不支持 at source 分账，R1 降级为到账即清/周期批量 basisRef={}",
                    gateway.channelCode(), orderNo);
            return;
        }

        // ③ 通道下发：按分片逐单提交（每单 ≤10 受益人；分片序号保证同一 basisRef 可对账关联）
        List<List<ChannelSplitPlanner.ChannelLeg>> shards = plan.shards();
        int cursor = 0;
        for (int shard = 1; shard <= shards.size(); shard++) {
            List<ChannelSplitPlanner.ChannelLeg> shardLegs = shards.get(shard - 1);
            List<ClearingChannelGateway.ChannelPayee> payees = shardLegs.stream()
                    .map(l -> new ClearingChannelGateway.ChannelPayee(
                            l.payeeType(), l.payeeAbaRef(), l.amount(), l.shardIndex()))
                    .toList();
            String shardRef = plan.shardInstructionNo(orderNo, shard);

            ClearingChannelGateway.SplitResult split;
            try {
                split = gateway.splitAtSource(shardRef, plan.shardAmount(shard), payees, plan.currency());
            } catch (BizException e) {
                // 通道业务拒付（错误码 92/25/70/LAM01/LAM02/6）：账本已提交，不能回滚 → 指令落 FAILED 挂起
                markAllFailed(instructions, e.getMessage());
                log.warn("R1 通道分账被拒 basisRef={} shard={} reason={}", orderNo, shard, e.getMessage());
                return;
            }
            if (!split.supported()) {
                log.warn("通道 {} 不支持 at source，R1 降级为周期批量 basisRef={} msg={}",
                        gateway.channelCode(), orderNo, split.message());
                return;
            }
            if (split.institutionRefs().size() != shardLegs.size()) {
                markAllFailed(instructions, "通道回执数量与收款方数量不一致");
                log.error("R1 通道回执数量不匹配 basisRef={} shard={} payees={} refs={}",
                        orderNo, shard, shardLegs.size(), split.institutionRefs().size());
                return;
            }

            // CREATED → SENT → ACKED → SETTLED（设计 §6.1）
            for (int i = 0; i < shardLegs.size(); i++) {
                ClearingInstruction instruction = instructions.get(cursor + i);
                Long id = instruction.getId();
                clearingInstructionService.markSent(id, gateway.channelCode());
                clearingInstructionService.onAck(id, split.institutionRefs().get(i));
                clearingInstructionService.markSettled(id);
            }
            cursor += shardLegs.size();
        }
        log.info("R1 扫码购分账完成 basisRef={} 腿数={} 分片={} 尾差={} 交易总额={}",
                orderNo, instructions.size(), plan.shardCount(), plan.tailAbsorbed(), plan.total());
    }

    /**
     * 选取通道实现：优先精确匹配通道号，其次取第一个可用实现。
     *
     * @param channelCode 期望的通道号
     * @return 通道实现；无任何实现时返回 {@code null}
     */
    private ClearingChannelGateway resolveGateway(String channelCode) {
        if (channelGateways == null || channelGateways.isEmpty()) {
            return null;
        }
        for (ClearingChannelGateway gateway : channelGateways) {
            if (channelCode.equals(gateway.channelCode())) {
                return gateway;
            }
        }
        return channelGateways.get(0);
    }

    /**
     * 把指令批量置为 {@code FAILED}（业务挂起记录）。已终态的指令会被状态机拒绝，此处吞掉并记日志。
     *
     * @param instructions 待置失败的指令
     * @param reason       失败原因（通道返回消息）
     */
    private void markAllFailed(List<ClearingInstruction> instructions, String reason) {
        for (ClearingInstruction instruction : instructions) {
            try {
                clearingInstructionService.markFailed(instruction.getId(), reason);
            } catch (BizException e) {
                log.warn("指令 {} 置 FAILED 被拒（可能已终态）：{}", instruction.getId(), e.getMessage());
            }
        }
    }

    /**
     * 从事件载荷解析订单号。
     *
     * @param payloadJson 载荷 JSON
     * @return 订单号；缺失或格式非法时返回 {@code null}
     */
    private String parseOrderNo(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            JsonNode orderNo = node.get("orderNo");
            if (orderNo == null || orderNo.isNull() || orderNo.asText().isBlank()) {
                return null;
            }
            return orderNo.asText();
        } catch (Exception e) {
            log.error("解析 PAYMENT_PAID payload 失败：{}", payloadJson, e);
            return null;
        }
    }
}
