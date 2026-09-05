package com.claw.server.domain.contract;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务站合约定时调度器（缺口③）。
 *
 * <p>每日凌晨扫描到期未退出的 ACTIVE 合约，置 EXPIRED，避免人工漏处理。
 * 仅调用 {@link ContractService#expireDueContracts()}，所有流转仍走服务层状态校验 + 审计。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContractScheduler {

    private final ContractService contractService;

    /** 每日凌晨 4 点执行。 */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void scan() {
        int n = contractService.expireDueContracts();
        if (n > 0) {
            log.info("合约定时扫描：{} 份到期服务站合约置 EXPIRED", n);
        }
    }
}
