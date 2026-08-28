package com.claw.server.domain.deposit;

import com.claw.server.common.enums.PoolEntryStatus;
import com.claw.server.domain.sharedpool.SharedPoolEntry;
import com.claw.server.domain.sharedpool.SharedPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 站长押金动态核定（R3）：站长应核定押金 = Σ(该站入池资产现值) × 因子(默认 0.05)。
 *
 * <p>本期仅提供计算口径与结构，冻结执行（调 DepositService）留后续任务。
 * 资产现值来源（如估值表）本期未接入，按 0 计，仅保留因子与汇总结构，
 * 后续接入估值后直接替换 presentValue(assetId) 即可。跨域只经 SharedPoolService 服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StationDepositService {

    private static final BigDecimal FACTOR = new BigDecimal("0.05");

    private final SharedPoolService sharedPoolService;

    /** 计算站长应核定押金（在池资产现值之和 × 因子）。 */
    public BigDecimal computeStationDeposit(Long stationId) {
        if (stationId == null) {
            return BigDecimal.ZERO;
        }
        List<SharedPoolEntry> entries = sharedPoolService.listAvailableAtStation(stationId);
        BigDecimal totalPresentValue = entries.stream()
                .map(this::presentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deposit = totalPresentValue.multiply(FACTOR);
        log.info("站长押金核定 stationId={} 在池资产数={} 核定={}", stationId, entries.size(), deposit);
        return deposit;
    }

    /** 资产现值（本期无估值来源，返回 0；后续接入估值表时替换）。 */
    private BigDecimal presentValue(SharedPoolEntry entry) {
        return BigDecimal.ZERO;
    }
}
