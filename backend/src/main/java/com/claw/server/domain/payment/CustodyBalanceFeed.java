package com.claw.server.domain.payment;

import com.claw.server.common.enums.AccountType;
import com.claw.server.domain.funds.FundsLocation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 机构托管余额数据源（L3 托管对账用）。
 *
 * <p>L3 按 {@code funds_location × netByAccountType} 勾对「机构托管余额」与「账本客户资金科目合计」。
 * 账本侧由 {@code AccountService.sumBalanceByAccountType} 提供；机构侧（ABA / Bakong / 银行）
 * 由本接口提供。本增量仅给出默认实现（{@link InMemoryCustodyBalanceFeed}，恒返回 {@code null}
 * 表示「无上报数据，跳过」），真实机构对账文件 / API 接入后替换为具体实现即可，无需改动
 * {@code ReconciliationService} 的对账编排逻辑。
 */
public interface CustodyBalanceFeed {

    /**
     * 某机构在指定日期、某账本科目下的托管余额。
     *
     * @param location    托管点位（含 institution / locationCode）
     * @param accountType 账本科目（覆盖科目之一）
     * @param date        对账日
     * @return 机构托管余额；返回 {@code null} 表示当日无上报数据，调用方跳过该对勾对
     */
    BigDecimal balanceFor(FundsLocation location, AccountType accountType, LocalDate date);
}
