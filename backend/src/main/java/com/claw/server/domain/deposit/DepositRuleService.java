package com.claw.server.domain.deposit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 阶梯押金规则服务（R4）：计算押金率与押金金额。
 *
 * <p>首年（yearIndex <= 0 视作第 1 年）取 deposit_rules 中 year_index=1 的费率；
 * 查不到规则时默认 0.30。押金金额 = 总额 × 对应年率。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepositRuleService {

    private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.30");

    private final DepositRuleRepository depositRuleRepository;

    /** 取阶梯押金率。 */
    public BigDecimal computeDepositRate(String assetType, int yearIndex) {
        if (assetType == null) {
            return DEFAULT_RATE;
        }
        int idx = Math.max(yearIndex, 1);
        return depositRuleRepository.findByAssetTypeAndYearIndex(assetType, idx)
                .map(DepositRule::getDepositRate)
                .orElse(DEFAULT_RATE);
    }

    /** 押金金额 = 总额 × 对应年率。 */
    public BigDecimal computeDepositAmount(BigDecimal total, String assetType, int yearIndex) {
        BigDecimal rate = computeDepositRate(assetType, yearIndex);
        BigDecimal base = total == null ? BigDecimal.ZERO : total;
        return base.multiply(rate);
    }
}
