package com.claw.server.domain.payment;

import com.claw.server.common.enums.AccountType;
import com.claw.server.domain.funds.FundsLocation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 内存版机构托管余额数据源（默认实现）。
 *
 * <p>恒返回 {@code null} —— 即「当期无机构托管余额上报」，L3 托管对账据此跳过所有勾对，
 * 等价于「未接入机构对账文件前的空跑」。真实 ABA / Bakong / 银行对账能力接入后，
 * 以 {@code @Primary} 或条件装配替换为返回真实余额的实现即可。
 *
 * <p>此类不持有任何状态，单测中可直接 {@code spy} / 覆写 {@link #balanceFor} 注入差异以验证 L3 落账。
 */
@Component
public class InMemoryCustodyBalanceFeed implements CustodyBalanceFeed {

    @Override
    public BigDecimal balanceFor(FundsLocation location, AccountType accountType, LocalDate date) {
        return null;
    }
}
