package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.RuleBasis;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 分账引擎（L5）：规则解析 —— 场景 + 金额 + 厂家 + 币种 → 有序分账明细。
 *
 * <p><b>纯函数语义</b>：{@link #compute(String, BigDecimal, Long, String)} 只读规则表、不做任何写操作，
 * 无副作用、可单测。写账/落指令由 {@link ClearingService} 负责。
 *
 * <p><b>规则命中</b>：取 {@code biz_scene + status=ACTIVE} 的规则，按 {@code priority ASC}（越小越先扣）；
 * 若指定 {@code manufacturerId}，仅保留「厂家专属规则」与「通用规则（manufacturer_id 为空）」，其中
 * 厂家专属规则优先（同 priority 时排前）。
 *
 * <p><b>残差归位（兜底项）</b>：兜底收款方由 {@code basis=RESIDUAL} <b>显式</b>标记（V132 起；
 * 落地设计附录 A.3「分账总额必须等于交易总额 / 错误码 92」的账本侧口径），其金额 =
 * {@code total − Σ其余腿}，<b>无需</b> rate / fixed_amount / tier_json。
 *
 * <p><b>历史兼容</b>：场景内不存在 RESIDUAL 规则时，回退识别 {@code basis=RATE 且 rate>=1}
 * 为兜底项（V131 种子「MANUFACTURER / RATE 1.000000」的旧写法），保证存量配置不改也能跑。
 * 多条兜底候选时取 {@code priority} 最大者；未被选中的 RESIDUAL 规则属配置错误，会显式抛错。
 * 其余规则按各自 basis 计算。Σ腿金额恒等于 total。
 *
 * <p><b>守恒断言</b>：分组完成后再次校验 {@code Σ(leg.amount) == total}；一切越界（残差为负、
 * 无规则命中、basis 配置缺失）均抛 {@link BizException}，<b>绝不静默返回空表或错账</b>。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SplitEngine {

    /** 金额标度（保留 4 位，HALF_UP）。 */
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final SettlementRuleRepository settlementRuleRepository;

    /**
     * 规则命中 + 计算：场景 + 总额 + 厂家 + 币种 → 有序分账明细（残差归兜底收款方）。
     *
     * @param bizScene       业务场景（settlement_rule.biz_scene）
     * @param total          待分账总额（必须 &ge; 0）
     * @param manufacturerId 可选：按厂家细分规则
     * @param currency       币种（空则按规则自身 currency，不做强制过滤）
     * @return 按 priority 升序的分账腿（Σ金额 == total）
     * @throws BizException 无规则命中 / basis 配置缺失 / 残差为负 / 守恒失败
     */
    @Transactional(readOnly = true)
    public List<SplitLeg> compute(String bizScene, BigDecimal total, Long manufacturerId, String currency) {
        if (bizScene == null || bizScene.isBlank()) {
            throw BizException.invalidParam("error.clearing.request.invalid");
        }
        if (total == null || total.signum() < 0) {
            throw BizException.invalidParam("error.clearing.request.invalid");
        }

        List<SettlementRule> rules = loadRules(bizScene, manufacturerId, currency);
        if (rules.isEmpty()) {
            throw BizException.notFound("error.clearing.rule.not.found", bizScene);
        }

        // 兜底项：优先显式 basis=RESIDUAL（V132）；无则回退历史约定 basis=RATE 且 rate>=1（V131 种子）。
        // 多条候选时取 priority 最大者（“越小越先扣”语义下，兜底项必排在最后）。
        SettlementRule residueRule = rules.stream()
                .filter(r -> r.getBasis() == RuleBasis.RESIDUAL)
                .max(Comparator.comparingInt(SplitEngine::nzPriority))
                .orElseGet(() -> rules.stream()
                        .filter(r -> r.getBasis() == RuleBasis.RATE
                                && r.getRate() != null && r.getRate().compareTo(ONE) >= 0)
                        .max(Comparator.comparingInt(SplitEngine::nzPriority))
                        .orElse(null));

        List<SplitLeg> legs = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (SettlementRule rule : rules) {
            if (rule == residueRule) {
                continue;
            }
            BigDecimal amount = computeByBasis(rule, total).setScale(SCALE, ROUNDING);
            if (amount.signum() < 0) {
                throw BizException.of(42260, "error.clearing.amount.negative", rule.getPayeeType());
            }
            legs.add(toLeg(rule, amount));
            allocated = allocated.add(amount);
        }

        if (residueRule != null) {
            BigDecimal residue = total.subtract(allocated).setScale(SCALE, ROUNDING);
            if (residue.signum() < 0) {
                throw BizException.of(42261, "error.clearing.not.balanced", allocated, total);
            }
            legs.add(toLeg(residueRule, residue));
            allocated = allocated.add(residue);
        }

        // 守恒断言（分组前后双重校验）
        if (allocated.compareTo(total) != 0) {
            throw BizException.of(42261, "error.clearing.not.balanced", allocated, total);
        }
        legs.sort(Comparator.comparingInt(SplitLeg::priority));
        return legs;
    }

    /**
     * 加载并筛选命中规则。
     *
     * @param bizScene       业务场景
     * @param manufacturerId 可选厂家
     * @param currency       币种（空则不过滤）
     * @return 按 priority 升序的规则
     */
    private List<SettlementRule> loadRules(String bizScene, Long manufacturerId, String currency) {
        List<SettlementRule> sceneRules =
                settlementRuleRepository.findByBizSceneAndStatusAndDeletedFalseOrderByPriorityAsc(bizScene, "ACTIVE");
        List<SettlementRule> matched = new ArrayList<>();
        for (SettlementRule rule : sceneRules) {
            if (manufacturerId != null) {
                // 厂家专属 或 通用（manufacturer_id 为空）；其余（其他厂家专属）跳过
                if (rule.getManufacturerId() != null && !rule.getManufacturerId().equals(manufacturerId)) {
                    continue;
                }
            } else if (rule.getManufacturerId() != null) {
                // 未指定厂家时只取通用规则
                continue;
            }
            if (currency != null && !currency.isBlank()
                    && rule.getCurrency() != null && !rule.getCurrency().equals(currency)) {
                continue;
            }
            matched.add(rule);
        }
        // 厂家专属优先（同 priority 排前）；随后按 priority 升序
        matched.sort(Comparator
                .comparingInt((SettlementRule r) -> r.getManufacturerId() == null ? 1 : 0)
                .thenComparingInt(SplitEngine::nzPriority));
        return matched;
    }

    /**
     * 按规则 basis 计算单腿金额（不含残差兜底处理）。
     *
     * @param rule  规则
     * @param total 分账总额
     * @return 该腿金额
     * @throws BizException basis 配置缺失 / TIER 结构非法
     */
    private BigDecimal computeByBasis(SettlementRule rule, BigDecimal total) {
        switch (rule.getBasis()) {
            case RATE -> {
                if (rule.getRate() == null) {
                    throw BizException.invalidParam("error.clearing.rule.invalid", rule.getId());
                }
                return total.multiply(rule.getRate());
            }
            case FIXED -> {
                if (rule.getFixedAmount() == null) {
                    throw BizException.invalidParam("error.clearing.rule.invalid", rule.getId());
                }
                return rule.getFixedAmount();
            }
            case TIER -> {
                return computeTier(rule, total);
            }
            case RESIDUAL -> {
                // 只有「被 compute 选中的那一条」兜底规则走残差分支。走到这里说明同一场景配置了
                // 多条 RESIDUAL 规则 —— 属配置错误，必须显式报错，绝不按 0 或全额静默错账。
                throw BizException.invalidParam("error.clearing.rule.invalid", rule.getId());
            }
            default -> throw BizException.invalidParam("error.clearing.rule.invalid", rule.getId());
        }
    }

    /**
     * 阶梯计价（tier_json 结构约定）：
     * <pre>
     * [ {"upTo": 1000, "rate": 0.10}, {"rate": 0.05} ]
     * </pre>
     * 规则：按序取第一个满足 {@code total <= upTo} 的档；无 {@code upTo} 的档为兜底档；
     * 均不满足则取最后一档。档内金额取 {@code rate}（比例）或 {@code amount}（定额）。
     *
     * @param rule  规则（basis=TIER）
     * @param total 分账总额
     * @return 该腿金额
     * @throws BizException tier_json 为空 / 非法 / 档位无 rate/amount
     */
    private BigDecimal computeTier(SettlementRule rule, BigDecimal total) {
        if (rule.getTierJson() == null || rule.getTierJson().isBlank()) {
            throw BizException.invalidParam("error.clearing.tier.invalid", rule.getId());
        }
        List<Map<String, Object>> tiers;
        try {
            tiers = new ObjectMapper().readValue(rule.getTierJson(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw BizException.invalidParam("error.clearing.tier.invalid", rule.getId());
        }
        if (tiers == null || tiers.isEmpty()) {
            throw BizException.invalidParam("error.clearing.tier.invalid", rule.getId());
        }
        Map<String, Object> chosen = tiers.get(tiers.size() - 1);
        for (Map<String, Object> tier : tiers) {
            Object upTo = tier.get("upTo");
            if (upTo == null) {
                chosen = tier;
                break;
            }
            if (total.compareTo(new BigDecimal(String.valueOf(upTo))) <= 0) {
                chosen = tier;
                break;
            }
        }
        Object amount = chosen.get("amount");
        if (amount != null) {
            return new BigDecimal(String.valueOf(amount));
        }
        Object rate = chosen.get("rate");
        if (rate != null) {
            return total.multiply(new BigDecimal(String.valueOf(rate)));
        }
        throw BizException.invalidParam("error.clearing.tier.invalid", rule.getId());
    }

    private static SplitLeg toLeg(SettlementRule rule, BigDecimal amount) {
        return new SplitLeg(rule.getPayeeType(), amount, rule.getSettleCycle(), nzPriority(rule), rule.getId());
    }

    private static int nzPriority(SettlementRule rule) {
        return rule.getPriority() == null ? 10 : rule.getPriority();
    }

    /**
     * 单条分账腿。
     *
     * @param payeeType   收款方类型（MANUFACTURER/STATION/PLATFORM/...）
     * @param amount      金额（已按 4 位四舍五入）
     * @param settleCycle 结算周期（T+0/T+1/T+7）
     * @param priority    优先级（越小越先扣）
     * @param ruleId      命中规则 id
     */
    public record SplitLeg(String payeeType, BigDecimal amount, String settleCycle, int priority, Long ruleId) {
    }
}
