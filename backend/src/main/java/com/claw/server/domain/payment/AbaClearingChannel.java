package com.claw.server.domain.payment;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.ClearingMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ABA PayWay 清分通道（L1）<b>骨架</b>——错误码映射已落地，真实 HTTP 调用<b>待通道书面确认</b>。
 *
 * <p><b>为什么只能是骨架</b>：落地设计 §7「待通道确认清单」与附录 A.5 明确要求
 * <b>严禁在未确认前臆造 API 签名与字段</b>。截至本实现，已核实的是通道<b>能力与错误码</b>
 * （附录 A，2026-09-13），未核实的是<b>接口地址、报文结构、签名方式、字段名</b>。
 * 因此这里只把「已核实的错误码语义」固化成代码，真实调用留 {@code TODO(待通道确认)}。
 *
 * <p><b>已核实的错误码 → i18n 文案映射</b>（来源：落地设计附录 A.2 / A.4）：
 * <ul>
 *   <li>{@code 92}  —— 分账总额必须等于交易总额（尾差未吸收 / 金额不平）</li>
 *   <li>{@code 25}  —— 单次受益人数量超上限（≤10 受益人）</li>
 *   <li>{@code 70} / {@code LAM01} / {@code LAM02} —— 日/月/单笔限额类（<b>已证实存在，数值未公开</b>，
 *       故三者统一映射为「命中通道限额」，不臆造各自精确语义）</li>
 *   <li>{@code 6}   —— 通道系统错误</li>
 *   <li>其它 —— 未识别错误码（保留原码，便于运维排查与后续补映射）</li>
 * </ul>
 *
 * <p><b>装配条件</b>：{@code claw.clearing.channel.mock=true} 时不装配（由
 * {@link ClearingChannelMock} 影子实现接管），二者互斥。
 *
 * <p><b>降级策略</b>：At source 分账不可用时（未接入 / 通道拒绝限额类错误），调用方须退回到账即清
 * （路径 B）或周期批量代付（路径 C）—— 见 {@code ConsignmentClearingHandler} 与
 * {@link #payoutToPayee}。
 */
@Component
@ConditionalOnProperty(prefix = "claw.clearing.channel", name = "mock", havingValue = "false", matchIfMissing = true)
@Slf4j
public class AbaClearingChannel implements ClearingChannelGateway {

    /** 通道号（与设计文档通道词表一致）。 */
    public static final String CHANNEL_CODE = "ABA_PAYWAY";

    /** 未接入真实 API 的错误码。 */
    private static final int CODE_NOT_IMPLEMENTED = 42275;

    /** 分账总额与交易总额不一致（ABA 错误码 92）。 */
    public static final int CODE_SPLIT_TOTAL_MISMATCH = 42270;
    /** 受益人超上限（ABA 错误码 25）。 */
    public static final int CODE_BENEFICIARY_LIMIT = 42271;
    /** 命中通道限额（ABA 错误码 70 / LAM01 / LAM02，数值未公开）。 */
    public static final int CODE_LIMIT_EXCEEDED = 42272;
    /** 通道系统错误（错误码 6）。 */
    public static final int CODE_SYSTEM_ERROR = 42273;
    /** 未识别的通道错误码。 */
    public static final int CODE_UNKNOWN = 42274;

    /** 通道错误码 → i18n key（仅固化「已核实」的语义，未核实者不猜）。 */
    private static final Map<String, ErrorMapping> ERROR_CODE_MAPPINGS = Map.of(
            "92", new ErrorMapping(CODE_SPLIT_TOTAL_MISMATCH, "error.clearing.channel.split.total.mismatch"),
            "25", new ErrorMapping(CODE_BENEFICIARY_LIMIT, "error.clearing.channel.beneficiary.limit"),
            "70", new ErrorMapping(CODE_LIMIT_EXCEEDED, "error.clearing.channel.limit.exceeded"),
            "LAM01", new ErrorMapping(CODE_LIMIT_EXCEEDED, "error.clearing.channel.limit.exceeded"),
            "LAM02", new ErrorMapping(CODE_LIMIT_EXCEEDED, "error.clearing.channel.limit.exceeded"),
            "6", new ErrorMapping(CODE_SYSTEM_ERROR, "error.clearing.channel.system.error"));

    /** 未识别错误码的兜底映射。 */
    private static final ErrorMapping UNKNOWN_MAPPING = new ErrorMapping(CODE_UNKNOWN, "error.clearing.channel.code.unknown");

    @Override
    public String channelCode() {
        return CHANNEL_CODE;
    }

    @Override
    public boolean supports(ClearingMode mode) {
        // 附录 A.1：Split & Payout 已确认支持 at source；周期批量走 payoutToPayee（路径 C）。
        return mode == ClearingMode.AT_SOURCE || mode == ClearingMode.ON_ARRIVAL;
    }

    /**
     * 分账 at source（真实 HTTP 调用待通道确认）。
     *
     * @param orderNo  收单业务单号
     * @param amount   交易总额
     * @param payees   收款方明细
     * @param currency 币种
     * @return 分账结果
     * @throws BizException 未接入真实 API（{@value #CODE_NOT_IMPLEMENTED}）
     */
    @Override
    public SplitResult splitAtSource(String orderNo, BigDecimal amount, List<ChannelPayee> payees, String currency) {
        // TODO(待通道确认): 对接 ABA PayWay "Split & Payout"。需通道书面提供：
        //   ① 接口地址与报文结构；② 签名方式（附录 A.2 提到回调为 HMAC-SHA512）；
        //   ③ 收款方字段名（ABA 账户号 / MID）；④ 幂等键字段（external_reference 口径）；
        //   ⑤ 回执号字段（对账锚点，附录 A.2 的 external_reference / apv 网关号）。
        // 落地要点（已确认，实现时不得遗漏）：
        //   ① 分账总额必须精确等于交易总额，否则通道报错 92 → 请求前本地校验（见 ChannelSplitPlanner）；
        //   ② 单次 ≤10 受益人 → 超出按 shardIndex 拆单，同一 basisRef 用分片序号关联；
        //   ③ 收款方必须是 ABA 账户持有人 / ABA MID → 请求前做前置校验。
        log.error("[AbaClearingChannel] at source 分账尚未接入真实 API（待通道确认），orderNo={} 受益人={}",
                orderNo, payees == null ? 0 : payees.size());
        throw BizException.of(CODE_NOT_IMPLEMENTED, "error.clearing.channel.not.implemented", CHANNEL_CODE);
    }

    /**
     * 代付到收款方（真实 HTTP 调用待通道确认；语义与既有 {@code AbaGateway.payout} 一致）。
     *
     * @param instructionNo    清分指令号（幂等键）
     * @param amount           代付金额
     * @param payeeAccountJson 收款方账户信息
     * @param currency         币种
     * @return 机构回执号
     * @throws BizException 未接入真实 API（{@value #CODE_NOT_IMPLEMENTED}）
     */
    @Override
    public String payoutToPayee(String instructionNo, BigDecimal amount, String payeeAccountJson, String currency) {
        // TODO(待通道确认): 复用/扩展既有 AbaGateway.payout 的真实出金通道，并补齐批量代付报文
        //   （附录 A.2：批量代付支持、单次 ≤10 受益人、最小 USD 0.01 / KHR 100，日/月限额数值未公开）。
        // 重发前必须先查回执，放款类严禁重复放款（设计 §6.1）。
        log.error("[AbaClearingChannel] 代付尚未接入真实 API（待通道确认），instructionNo={}", instructionNo);
        throw BizException.of(CODE_NOT_IMPLEMENTED, "error.clearing.channel.not.implemented", CHANNEL_CODE);
    }

    /**
     * 通道错误码 → i18n key（供真实对接时统一转换；已核实的 6 个码 + 兜底）。
     *
     * @param channelErrorCode 通道返回的错误码（如 {@code 92} / {@code LAM01}）
     * @return 对应 i18n key
     */
    public static String errorKeyOf(String channelErrorCode) {
        return mappingOf(channelErrorCode).key();
    }

    /**
     * 把通道错误码翻译为项目内 {@link BizException}（真实对接时在通道返回处调用）。
     *
     * @param channelErrorCode 通道返回的错误码
     * @param detail           通道返回的原始描述（会作为参数透出，便于运维定位）
     * @return 带 i18n 文案的异常
     */
    public static BizException translateError(String channelErrorCode, String detail) {
        ErrorMapping mapping = mappingOf(channelErrorCode);
        return BizException.of(mapping.code(), mapping.key(), detail == null ? channelErrorCode : detail);
    }

    private static ErrorMapping mappingOf(String channelErrorCode) {
        if (channelErrorCode == null) {
            return UNKNOWN_MAPPING;
        }
        return ERROR_CODE_MAPPINGS.getOrDefault(channelErrorCode.trim(), UNKNOWN_MAPPING);
    }

    /**
     * 通道错误码映射条目。
     *
     * @param code 项目内异常码
     * @param key  i18n message key
     */
    private record ErrorMapping(int code, String key) {
    }
}
