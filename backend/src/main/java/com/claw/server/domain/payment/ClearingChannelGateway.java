package com.claw.server.domain.payment;

import com.claw.server.common.enums.ClearingMode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 清分通道 SPI（L1 通道层）——把「账本已确认的分账结果」下发到真实通道。
 *
 * <p><b>职责边界</b>：本 SPI <b>只下发、不碰账本</b>。账本权益由 {@code ClearingService} 先落
 * {@code LedgerService.postEntries}；通道动作失败时账本不产生净差异（指令落 FAILED/MANUAL 等重试），
 * 从而「账实可对」。这与设计 §5「先落账本、再驱动通道」一致。
 *
 * <p><b>通道能力（落地设计附录 A，2026-09-13 已核实）</b>：
 * <ul>
 *   <li>ABA PayWay 提供 <b>Split &amp; Payout</b>：收单时按 payout 指令把一笔交易分配到多个收款人
 *       → {@link #splitAtSource} 为一等公民；</li>
 *   <li><b>分账总额必须精确等于交易总额</b>（错误码 92）→ 每腿金额须精确到分，尾差显式吸收；</li>
 *   <li><b>单次 ≤10 受益人</b>（错误码 25）→ 超出须拆单，同一 {@code basisRef} 用分片序号关联对账；</li>
 *   <li><b>受益人必须是 ABA 账户持有人或 ABA MID</b> → 分账前须做收款方 ABA 账户前置校验；</li>
 *   <li>日/月限额存在（错误码 70 / LAM01 / LAM02）但数值未公开 → 必须保留降级路径（到账即清 / 周期批量）。</li>
 * </ul>
 *
 * <p><b>多通道注册</b>：实现类均为 Spring Bean，由调用方按 {@link #channelCode()} 选取（ABA_PAYWAY / BAKONG），
 * 因此本 SPI 支持同场景多通道并存与容灾（{@code PaymentGatewayFactory} 的同款思路）。
 *
 * <p><b>与设计文档的偏差（已记录）</b>：设计 §4.2 的 {@code splitAtSource} 参数为
 * {@code List<SplitLeg>}（{@code domain.clearing.SplitEngine.SplitLeg}）。此处改用本接口内聚的
 * {@link ChannelPayee} —— 目的是让通道层<b>不反向依赖分账引擎</b>，SPI 可被批次/代付等场景独立复用。
 */
public interface ClearingChannelGateway {

    /**
     * 通道标识，与 {@code clearing_instruction.channel} / {@code funds_location.channel} 同口径。
     *
     * @return 通道码，如 {@code ABA_PAYWAY}
     */
    String channelCode();

    /**
     * 本通道是否支持该清分时机。
     *
     * @param mode 清分时机模式
     * @return 支持返回 {@code true}；{@code false} 时调用方退回到账即清 / 周期批量路径
     */
    boolean supports(ClearingMode mode);

    /**
     * 分账 at source：通道侧一笔收款按规则直拆到多收款方。
     *
     * @param orderNo  收单业务单号（清分依据单号，作通道侧 {@code external_reference} 幂等键）
     * @param amount   交易总额（须与 {@code payees} 金额合计精确相等，否则通道报错误码 92）
     * @param payees   收款方明细（已精确到分、已按 ≤10 受益人分片）
     * @param currency 币种（USD/KHR）
     * @return 分账结果；{@link SplitResult#supported()} 为 {@code false} 时调用方须降级
     */
    SplitResult splitAtSource(String orderNo, BigDecimal amount, List<ChannelPayee> payees, String currency);

    /**
     * 代付到收款方（周期批量 / 到账即清回退路径复用；与既有 {@code AbaGateway.payout} 同语义）。
     *
     * @param instructionNo   清分指令号（幂等键，重发前必须先查回执，严禁重复放款）
     * @param amount          代付金额
     * @param payeeAccountJson 收款方账户信息（JSON 文本）
     * @param currency        币种
     * @return 机构回执号
     */
    String payoutToPayee(String instructionNo, BigDecimal amount, String payeeAccountJson, String currency);

    /**
     * 单条收款方明细（通道下发口径）。
     *
     * @param payeeType   收款方类型（MANUFACTURER/STATION/LOGISTICS/PLATFORM）
     * @param payeeAbaRef 收款方 ABA 账户号 / MID；平台自有方为 {@code null}（由通道侧平台 MID 承载）
     * @param amount      金额（已精确到分）
     * @param shardIndex  分片序号：{@code 1-based}；单次 ≤10 受益人，超出按此序号拆单
     */
    record ChannelPayee(String payeeType, String payeeAbaRef, BigDecimal amount, int shardIndex) {
    }

    /**
     * 分账结果。
     *
     * @param supported        通道是否支持 at source 分账（{@code false} → 调用方走降级路径）
     * @param institutionRefs  各收款方机构回执号（顺序与请求 {@code payees} 一致）
     * @param message          通道返回消息（成功摘要 / 不支持原因）
     */
    record SplitResult(boolean supported, List<String> institutionRefs, String message) {

        /** 通道不支持 at source 分账（降级路径 B/C）。 */
        public static SplitResult unsupported(String message) {
            return new SplitResult(false, List.of(), message);
        }

        /** 分账成功，返回各腿机构回执号。 */
        public static SplitResult ok(List<String> institutionRefs) {
            return new SplitResult(true, List.copyOf(institutionRefs), "OK");
        }
    }
}
