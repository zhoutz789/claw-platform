package com.claw.server.domain.payment;

import com.claw.server.common.enums.ClearingMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 清分通道 <b>本地模拟实现</b>（不调用任何真实 API）。
 *
 * <p><b>为什么需要它</b>：落地设计附录 A.4 要求「Mock 先跑通」——在 ABA Split &amp; Payout 的
 * 真实 HTTP 对接、子商户能力、限额数值全部书面确认之前（附录 A.5 问询清单），R1/R5 必须在
 * 本地可端到端验证：四腿入账 + 指令推进到 SETTLED + 尾差吸收 + 分片，全部可复现。
 *
 * <p><b>通道号与真实实现相同（{@code ABA_PAYWAY}）</b>：它是真实通道的<b>影子实现</b>，只在
 * {@code claw.clearing.channel.mock=true} 时装配；此时 {@link AbaClearingChannel} 不装配
 * （二者条件互斥），因此永远只有一个通道 Bean 存在，不存在二义性，且
 * {@code clearing_instruction.channel} 落库口径与设计文档的通道词表（ABA_PAYWAY / BAKONG）一致。
 *
 * <p><b>可配置</b>：{@link #setSupported(boolean)} 打开/关闭 at source 分账能力，用于验证
 * 「通道不支持 → 自动降级到账即清 / 周期批量」的回退分支。所有调用被 {@link #invocations()}
 * 记录，供测试断言下发内容（金额、受益人、分片序号）。
 */
@Component
@ConditionalOnProperty(prefix = "claw.clearing.channel", name = "mock", havingValue = "true")
@Slf4j
public class ClearingChannelMock implements ClearingChannelGateway {

    /** 通道号：与真实 ABA 通道一致（影子实现，保证落库词表统一）。 */
    public static final String CHANNEL_CODE = "ABA_PAYWAY";

    /** 模拟回执号前缀。 */
    private static final String REF_PREFIX = "MOCK-ABA-";

    /** at source 分账能力开关（默认支持；置 false 验证降级路径 B/C）。 */
    private volatile boolean supported = true;

    /** 调用流水（测试断言用；无上限但仅本地/测试装配，不进入生产）。 */
    private final List<Invocation> invocations = new CopyOnWriteArrayList<>();

    /** 回执号自增序号。 */
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public String channelCode() {
        return CHANNEL_CODE;
    }

    @Override
    public boolean supports(ClearingMode mode) {
        return supported && (mode == ClearingMode.AT_SOURCE || mode == ClearingMode.ON_ARRIVAL);
    }

    /**
     * 模拟 at source 分账：不联网，直接按收款方数量返回模拟回执号。
     *
     * @param orderNo  收单业务单号
     * @param amount   交易总额
     * @param payees   收款方明细
     * @param currency 币种
     * @return {@code supported=true} 时返回逐腿回执；否则返回 {@link SplitResult#unsupported}
     */
    @Override
    public SplitResult splitAtSource(String orderNo, BigDecimal amount, List<ChannelPayee> payees, String currency) {
        List<ChannelPayee> safePayees = payees == null ? List.of() : List.copyOf(payees);
        invocations.add(new Invocation("splitAtSource", orderNo, amount, safePayees, currency));
        if (!supported) {
            log.info("[ClearingChannelMock] at source 分账被配置为不支持 orderNo={} → 调用方应降级 B/C", orderNo);
            return SplitResult.unsupported("mock: at source split disabled");
        }
        List<String> refs = new ArrayList<>(safePayees.size());
        for (ChannelPayee payee : safePayees) {
            refs.add(REF_PREFIX + sequence.incrementAndGet());
        }
        log.info("[ClearingChannelMock] 模拟分账 orderNo={} 交易额={} 受益人={} 币种={}",
                orderNo, amount, safePayees.size(), currency);
        return SplitResult.ok(refs);
    }

    /**
     * 模拟代付：不联网，返回模拟回执号。
     *
     * @param instructionNo    清分指令号
     * @param amount           代付金额
     * @param payeeAccountJson 收款方账户信息
     * @param currency         币种
     * @return 模拟回执号
     */
    @Override
    public String payoutToPayee(String instructionNo, BigDecimal amount, String payeeAccountJson, String currency) {
        invocations.add(new Invocation("payoutToPayee", instructionNo, amount, List.of(), currency));
        String ref = REF_PREFIX + sequence.incrementAndGet();
        log.info("[ClearingChannelMock] 模拟代付 instructionNo={} 金额={} 回执={}", instructionNo, amount, ref);
        return ref;
    }

    /** 设置 at source 分账能力（验证降级分支用）。 */
    public void setSupported(boolean supported) {
        this.supported = supported;
    }

    /** 当前 at source 分账能力。 */
    public boolean isSupported() {
        return supported;
    }

    /** 已记录的调用流水（按发生顺序）。 */
    public List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    /** 清空调用流水与序号（测试隔离用）。 */
    public void reset() {
        invocations.clear();
        sequence.set(0);
    }

    /**
     * 一次通道调用记录。
     *
     * @param operation 操作名（splitAtSource / payoutToPayee）
     * @param ref       业务单号（orderNo / instructionNo）
     * @param amount    金额
     * @param payees    收款方明细（代付时为空）
     * @param currency  币种
     */
    public record Invocation(String operation, String ref, BigDecimal amount, List<ChannelPayee> payees,
                             String currency) {
    }
}
