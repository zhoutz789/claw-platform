package com.claw.server.domain.commission;

import com.claw.server.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 设备销售提成规则服务（增量 B · R7）。
 *
 * <p>与光伏分成规则 revenue_split_rules 完全解耦，单独维护。
 * 命中规则优先级取高（priority 大者优先）：厂家+商品 > 厂家 > 平台默认（manufacturer_id IS NULL）。
 * commission_type ∈ {RATE 比例, AMOUNT 定额}；可设 min/max 封顶保底。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommissionRuleService {

    private final CommissionRuleRepository ruleRepository;

    @Transactional(readOnly = true)
    public List<CommissionRule> listRules(Long manufacturerId) {
        if (manufacturerId == null) {
            return ruleRepository.findAll();
        }
        List<CommissionRule> list = new ArrayList<>(ruleRepository.findByManufacturerIdAndEnabledTrue(manufacturerId));
        list.addAll(ruleRepository.findByManufacturerIdIsNullAndEnabledTrue());
        return list;
    }

    /**
     * 按主键直查单条规则。
     *
     * <p>不能拿 {@link #listRules(Long)} 做 findFirst：列表是「厂家规则 + 平台默认规则」的并集，
     * 传入 manufacturerId 时只含该厂家与平台默认两类，其它厂家的规则 id 永远匹配不到，
     * 会误报 404。
     */
    @Transactional(readOnly = true)
    public CommissionRule getRule(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> BizException.of(40401, "commission.rule.not.found"));
    }

    @Transactional
    public CommissionRule createRule(CommissionRule rule) {
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        if (rule.getEnabled() == null) {
            rule.setEnabled(true);
        }
        if (rule.getPriority() == null) {
            rule.setPriority(0);
        }
        return ruleRepository.save(rule);
    }

    @Transactional
    public CommissionRule updateRule(Long id, CommissionRule upd) {
        CommissionRule r = ruleRepository.findById(id)
                .orElseThrow(() -> BizException.of(40401, "commission.rule.not.found"));
        if (upd.getRuleName() != null) {
            r.setRuleName(upd.getRuleName());
        }
        if (upd.getCommissionType() != null) {
            r.setCommissionType(upd.getCommissionType());
        }
        if (upd.getRate() != null) {
            r.setRate(upd.getRate());
        }
        if (upd.getAmount() != null) {
            r.setAmount(upd.getAmount());
        }
        if (upd.getMinAmount() != null) {
            r.setMinAmount(upd.getMinAmount());
        }
        if (upd.getMaxAmount() != null) {
            r.setMaxAmount(upd.getMaxAmount());
        }
        if (upd.getPriority() != null) {
            r.setPriority(upd.getPriority());
        }
        if (upd.getEffectiveFrom() != null) {
            r.setEffectiveFrom(upd.getEffectiveFrom());
        }
        if (upd.getEffectiveTo() != null) {
            r.setEffectiveTo(upd.getEffectiveTo());
        }
        if (upd.getEnabled() != null) {
            r.setEnabled(upd.getEnabled());
        }
        r.setUpdatedAt(Instant.now());
        return ruleRepository.save(r);
    }

    @Transactional
    public void deleteRule(Long id) {
        ruleRepository.deleteById(id);
    }

    /** 解析某笔履约的提成金额（命中优先：厂家+商品 > 厂家 > 平台默认）。 */
    @Transactional(readOnly = true)
    public BigDecimal resolveCommission(Long manufacturerId, Long productId, BigDecimal saleAmount) {
        if (saleAmount == null) {
            saleAmount = BigDecimal.ZERO;
        }
        CommissionRule best = null;
        if (productId != null) {
            best = ruleRepository.findByManufacturerIdAndProductIdAndEnabledTrue(manufacturerId, productId)
                    .orElse(null);
        }
        if (best == null) {
            best = ruleRepository.findByManufacturerIdAndEnabledTrue(manufacturerId).stream()
                    .max(Comparator.comparingInt(CommissionRule::getPriority)).orElse(null);
        }
        if (best == null) {
            best = ruleRepository.findByManufacturerIdIsNullAndEnabledTrue().stream()
                    .max(Comparator.comparingInt(CommissionRule::getPriority)).orElse(null);
        }
        if (best == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal commission;
        if ("RATE".equals(best.getCommissionType()) && best.getRate() != null) {
            commission = saleAmount.multiply(best.getRate());
        } else if ("AMOUNT".equals(best.getCommissionType()) && best.getAmount() != null) {
            commission = best.getAmount();
        } else {
            commission = BigDecimal.ZERO;
        }
        if (best.getMinAmount() != null && commission.compareTo(best.getMinAmount()) < 0) {
            commission = best.getMinAmount();
        }
        if (best.getMaxAmount() != null && commission.compareTo(best.getMaxAmount()) > 0) {
            commission = best.getMaxAmount();
        }
        return commission.max(BigDecimal.ZERO);
    }

    /**
     * 命中提成规则的明细（含规则主键与快照），供履约结算留存审计与金额计算复用。
     *
     * <p>在 {@link #resolveCommission} 的匹配口径上增加<b>生效期校验</b>：仅采纳
     * effective_from/effective_to 窗口包含当前时刻（或起止为空）的规则；命中后返回规则 id、
     * 快照 JSON 与计算出的提成额（含 min/max 封顶保底，结果 max(ZERO)）。
     */
    public record CommissionDetail(Long ruleId, String ruleSnapshot, BigDecimal commission) {}

    @Transactional(readOnly = true)
    public CommissionDetail resolveCommissionDetail(Long manufacturerId, Long productId, BigDecimal saleAmount) {
        if (saleAmount == null) {
            saleAmount = BigDecimal.ZERO;
        }
        Instant now = Instant.now();
        CommissionRule best = null;
        if (productId != null) {
            best = ruleRepository.findByManufacturerIdAndProductIdAndEnabledTrue(manufacturerId, productId)
                    .orElse(null);
        }
        if (best == null) {
            best = ruleRepository.findByManufacturerIdAndEnabledTrue(manufacturerId).stream()
                    .filter(r -> isEffective(r, now))
                    .max(Comparator.comparingInt(CommissionRule::getPriority)).orElse(null);
        }
        if (best == null) {
            best = ruleRepository.findByManufacturerIdIsNullAndEnabledTrue().stream()
                    .filter(r -> isEffective(r, now))
                    .max(Comparator.comparingInt(CommissionRule::getPriority)).orElse(null);
        }
        if (best == null) {
            return new CommissionDetail(null, null, BigDecimal.ZERO);
        }
        BigDecimal commission = computeCommission(best, saleAmount);
        return new CommissionDetail(best.getId(), buildSnapshot(best), commission);
    }

    /** 生效期校验：effective_from/effective_to 为空表示无限制；窗口必须包含 now。 */
    private boolean isEffective(CommissionRule r, Instant now) {
        if (r.getEffectiveFrom() != null && now.isBefore(r.getEffectiveFrom())) {
            return false;
        }
        if (r.getEffectiveTo() != null && now.isAfter(r.getEffectiveTo())) {
            return false;
        }
        return true;
    }

    /** 按规则计算提成：RATE → 基数×rate；AMOUNT → 定额；含 min/max 封顶保底，结果 max(ZERO)。 */
    private BigDecimal computeCommission(CommissionRule best, BigDecimal saleAmount) {
        BigDecimal commission;
        if ("RATE".equals(best.getCommissionType()) && best.getRate() != null) {
            commission = saleAmount.multiply(best.getRate());
        } else if ("AMOUNT".equals(best.getCommissionType()) && best.getAmount() != null) {
            commission = best.getAmount();
        } else {
            commission = BigDecimal.ZERO;
        }
        if (best.getMinAmount() != null && commission.compareTo(best.getMinAmount()) < 0) {
            commission = best.getMinAmount();
        }
        if (best.getMaxAmount() != null && commission.compareTo(best.getMaxAmount()) > 0) {
            commission = best.getMaxAmount();
        }
        return commission.max(BigDecimal.ZERO);
    }

    /** 规则快照 JSON（审计留痕，手动拼接避免引入额外依赖）。 */
    private String buildSnapshot(CommissionRule r) {
        return String.format(
                "{\"id\":%d,\"type\":\"%s\",\"rate\":%s,\"amount\":%s,\"min\":%s,\"max\":%s,\"priority\":%d}",
                r.getId(), r.getCommissionType(),
                r.getRate() == null ? "null" : r.getRate().toPlainString(),
                r.getAmount() == null ? "null" : r.getAmount().toPlainString(),
                r.getMinAmount() == null ? "null" : r.getMinAmount().toPlainString(),
                r.getMaxAmount() == null ? "null" : r.getMaxAmount().toPlainString(),
                r.getPriority() == null ? 0 : r.getPriority());
    }
}
