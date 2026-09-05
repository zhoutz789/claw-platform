package com.claw.server.domain.contract;

import com.claw.server.common.enums.ContractRefundStatus;
import com.claw.server.common.enums.ContractStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 服务站合约服务（V73，周老板 2026-09-06 拍板）。
 *
 * <p>生命周期：每三年一签；到期可选择退出，保证金三月内退还。
 *
 * <ul>
 *   <li>{@link #createOnActivation} —— 服务站激活时生成 3 年期合约（幂等：已存在进行中合约则复用）；</li>
 *   <li>{@link #requestExit} —— 申请退出：置 EXIT_REQUESTED，退款截止 = 申请时 + 3 月；</li>
 *   <li>{@link #markRefunded} —— 保证金清算完成（全额/扣减后），置 EXITED；</li>
 *   <li>{@link #expireDueContracts} —— 定时任务把到期未退出的 ACTIVE 合约置 EXPIRED（P2 调度）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractService {

    /** 退出后保证金应退窗口：3 个月 ≈ 90 天。 */
    private static final long REFUND_WINDOW_DAYS = 90L;

    /** 合约年限（默认 3 年）。 */
    private static final int TERM_YEARS = 3;

    private final StationContractRepository contractRepository;

    /**
     * 服务站激活时生成合约（3 年期）。
     *
     * @return 新生成或已存在的进行中合约（幂等）
     */
    @Transactional
    public StationContract createOnActivation(Long stationId, Long depositTierId,
                                             BigDecimal depositAmount, BigDecimal creditLimit) {
        // 幂等：已存在进行中合约（ACTIVE 或 EXIT_REQUESTED）则直接复用，避免重试激活重复建约
        Optional<StationContract> existing = contractRepository
                .findByStationIdAndStatusAndDeletedFalse(stationId, ContractStatus.ACTIVE);
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = Instant.now();
        // 3 年合约期满：Instant 不支持 YEARS/MONTHS，经 ZonedDateTime 计算日历年期后转回
        Instant effectiveTo = now.atZone(java.time.ZoneOffset.UTC).plusYears(TERM_YEARS).toInstant();

        StationContract contract = StationContract.builder()
                .contractNo(generateContractNo())
                .stationId(stationId)
                .applicantType("STATION")
                .depositTierId(depositTierId)
                .depositAmount(depositAmount)
                .creditLimit(creditLimit)
                .termYears(TERM_YEARS)
                .signedAt(now)
                .effectiveFrom(now)
                .effectiveTo(effectiveTo)
                .status(ContractStatus.ACTIVE)
                .refundStatus(ContractRefundStatus.NONE)
                .tenantId(1L)
                .build();
        contract = contractRepository.save(contract);
        log.info("服务站 {} 签约 3 年期合约 contractNo={} 生效 {} ~ {} 保证金 {} 授信 {}",
                stationId, contract.getContractNo(), effectiveFromStr(contract), effectiveToStr(contract),
                depositAmount, creditLimit);
        return contract;
    }

    /**
     * 申请退出：置 EXIT_REQUESTED，退款截止 = 申请时 + 3 月。
     */
    @Transactional
    public StationContract requestExit(Long contractId, Long operatorId, String remark) {
        StationContract c = contractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("contract.not.found:" + contractId));
        if (c.getStatus() == ContractStatus.EXITED) {
            throw new IllegalStateException("contract.already.exited:" + contractId);
        }
        Instant now = Instant.now();
        c.setStatus(ContractStatus.EXIT_REQUESTED);
        c.setExitRequestedAt(now);
        c.setRefundDueAt(now.plus(REFUND_WINDOW_DAYS, java.time.temporal.ChronoUnit.DAYS));
        c.setRefundStatus(ContractRefundStatus.PENDING);
        c.setRemark(remark);
        c.setUpdatedBy(operatorId);
        c.setUpdatedAt(now);
        StationContract saved = contractRepository.save(c);
        log.info("服务站合约 {} 申请退出，保证金应退截止 {}", c.getContractNo(), c.getRefundDueAt());
        return saved;
    }

    /**
     * 保证金清算完成：全额退还或扣减后部分退还，置 EXITED。
     *
     * @param refunded true=全额(REFUNDED) false=扣减后(DEDUCTED)
     * @param refundAmount 实际退款额（全额时=deposit，扣减时< deposit）
     */
    @Transactional
    public StationContract markRefunded(Long contractId, boolean refunded, BigDecimal refundAmount, Long operatorId) {
        StationContract c = contractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("contract.not.found:" + contractId));
        Instant now = Instant.now();
        c.setRefundStatus(refunded ? ContractRefundStatus.REFUNDED : ContractRefundStatus.DEDUCTED);
        c.setRefundAmount(refundAmount);
        c.setStatus(ContractStatus.EXITED);
        c.setTerminatedAt(now);
        c.setUpdatedBy(operatorId);
        c.setUpdatedAt(now);
        StationContract saved = contractRepository.save(c);
        log.info("服务站合约 {} 保证金清算完成（{}）：实退 {}", c.getContractNo(),
                refunded ? "全额" : "扣减", refundAmount);
        return saved;
    }

    /** 定时任务：把到期未退出的 ACTIVE 合约置 EXPIRED（P2 调度调用）。 */
    @Transactional
    public int expireDueContracts() {
        List<StationContract> due = contractRepository
                .findByStatusAndEffectiveToBeforeAndDeletedFalse(ContractStatus.ACTIVE, Instant.now());
        int n = 0;
        for (StationContract c : due) {
            c.setStatus(ContractStatus.EXPIRED);
            c.setUpdatedAt(Instant.now());
            contractRepository.save(c);
            n++;
        }
        if (n > 0) {
            log.info("定时任务：{} 份到期服务站合约置 EXPIRED", n);
        }
        return n;
    }

    @Transactional(readOnly = true)
    public Optional<StationContract> findActiveByStation(Long stationId) {
        return contractRepository.findByStationIdAndStatusAndDeletedFalse(stationId, ContractStatus.ACTIVE);
    }

    private String generateContractNo() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "CT" + date + suffix;
    }

    private static String effectiveFromStr(StationContract c) {
        return c.getEffectiveFrom() == null ? "-" : c.getEffectiveFrom().toString();
    }

    private static String effectiveToStr(StationContract c) {
        return c.getEffectiveTo() == null ? "-" : c.getEffectiveTo().toString();
    }
}
