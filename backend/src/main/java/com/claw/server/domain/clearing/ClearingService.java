package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ClearingRequests;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.ClearingMode;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 清分编排服务（L5）：算分账 → 逐腿记账 → 落清分指令。
 *
 * <p><b>记账唯一出口</b>：逐腿调用既有 {@link LedgerService#postEntries}，<b>不新造记账</b>、
 * 不改写账路径。每腿一笔过账，分录为「借 平台对冲户（MASTER, user_id=NULL，余额豁免）/
 * 贷 该收款方应付或收入子户」，借贷守恒由账本引擎兜底。
 *
 * <p><b>幂等</b>：{@code bizRef = basisRef + ":" + payeeType}（沿用项目 {@code :RELEASE/:COMMISSION}
 * 后缀风格）→ 命中账本 {@code bizType+bizRef} 唯一约束（40950）；本服务在入口按 {@code basisRef}
 * 预检既有指令，重复调用直接返回既有结果，<b>绝不重复入账</b>。
 *
 * <p><b>通道动作</b>：本服务只写账本 + 落指令（{@code status=CREATED}），不下发、不调用任何通道
 * （通道 SPI 在 T06）。因此账本权益实时、通道动作异步，通道失败时账本不产生净差异。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClearingService {

    /**
     * 收款方类型 → 账本科目映射（R1 扫码购口径）。
     *
     * <p>R5（共享池：所有人/保险等）的收款方账户映射随 T08 场景接入一并扩展；
     * 未映射的收款方类型一律抛 {@code error.clearing.payee.unsupported}，不静默错账。
     */
    private static final Map<String, AccountType> PAYEE_ACCOUNT_TYPES = Map.of(
            "PLATFORM", AccountType.PLATFORM_REVENUE,
            "MANUFACTURER", AccountType.PAYABLE_MFG,
            "STATION", AccountType.PAYABLE_STATION,
            "LOGISTICS", AccountType.PAYABLE_LOGISTICS);

    private final SplitEngine splitEngine;
    private final ClearingInstructionService clearingInstructionService;
    private final LedgerService ledgerService;
    private final AccountService accountService;

    /**
     * R1/R5 统一入口：算分账 → 逐腿 {@code postEntries} → 生成 clearing_instruction。
     *
     * @param cmd 清分请求
     * @return 清分结果（各腿指令）
     * @throws BizException 入参非法 / 无规则命中 / 收款方不支持 / 记账失败
     */
    @Transactional
    public ClearingResult settle(ClearingRequests.Settle cmd) {
        validate(cmd);
        String currency = (cmd.currency() == null || cmd.currency().isBlank()) ? "USD" : cmd.currency();
        ClearingMode mode = cmd.mode() != null ? cmd.mode() : ClearingMode.AT_SOURCE;

        // 幂等：同 basisRef 已有指令 → 直接返回既有结果，不重复入账
        List<ClearingInstruction> existing = clearingInstructionService.findByBasisRef(cmd.basisRef());
        if (!existing.isEmpty()) {
            log.info("[Clearing] basisRef={} 已清分（{} 条指令），幂等跳过", cmd.basisRef(), existing.size());
            return ClearingResult.existing(cmd.basisRef(), cmd.bizScene(), existing);
        }

        List<SplitEngine.SplitLeg> legs =
                splitEngine.compute(cmd.bizScene(), cmd.total(), cmd.manufacturerId(), currency);
        Long offsetAccountId = ledgerService.getPlatformAccountId();

        List<ClearingInstruction> instructions = new ArrayList<>(legs.size());
        for (SplitEngine.SplitLeg leg : legs) {
            Long payeeAccountId = resolvePayeeAccount(leg.payeeType());
            String ledgerBizRef = cmd.basisRef() + ":" + leg.payeeType();
            List<LedgerRequests.Entry> entries = List.of(
                    new LedgerRequests.Entry(offsetAccountId, LedgerRequests.Direction.D, leg.amount(),
                            "clearing offset " + cmd.basisRef()),
                    new LedgerRequests.Entry(payeeAccountId, LedgerRequests.Direction.C, leg.amount(),
                            "clearing credit " + leg.payeeType()));
            ledgerService.postEntries(BizType.CLEARING_SETTLE, ledgerBizRef, entries);
            instructions.add(clearingInstructionService.create(cmd.scene(), mode, leg, cmd.basisRef(),
                    currency, payeeAccountId, cmd.channel()));
        }
        log.info("[Clearing] basisRef={} 清分完成，{} 腿，合计 {}", cmd.basisRef(), legs.size(), cmd.total());
        return ClearingResult.completed(cmd.basisRef(), cmd.bizScene(), cmd.total(), instructions);
    }

    /**
     * 幂等重放：按 idem_key 查既有指令。
     *
     * @param idemKey 业务幂等键
     * @return 命中指令；key 为空或无记录时返回 {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<ClearingInstruction> findByIdemKey(String idemKey) {
        return clearingInstructionService.findByIdemKey(idemKey);
    }

    /**
     * 解析收款方账本账户（平台内部科目户）。
     *
     * @param payeeType 收款方类型
     * @return 账本账户 id
     * @throws BizException 收款方类型未映射（如 R5 的 OWNER/INSURANCE，随 T08 扩展）
     */
    private Long resolvePayeeAccount(String payeeType) {
        AccountType type = PAYEE_ACCOUNT_TYPES.get(payeeType);
        if (type == null) {
            throw BizException.invalidParam("error.clearing.payee.unsupported", payeeType);
        }
        return accountService.getOrCreatePlatformAccount(type).getId();
    }

    private static void validate(ClearingRequests.Settle cmd) {
        if (cmd == null || cmd.scene() == null || isBlank(cmd.bizScene()) || isBlank(cmd.basisRef())
                || cmd.total() == null || cmd.total().signum() <= 0) {
            throw BizException.invalidParam("error.clearing.request.invalid");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 清分结果。
     *
     * @param basisRef         依据单号
     * @param bizScene         业务场景
     * @param total            合计金额
     * @param legCount         腿数
     * @param idempotentReplay 是否为幂等重放（true 表示未重复入账）
     * @param instructions     各腿清分指令
     */
    public record ClearingResult(String basisRef, String bizScene, BigDecimal total, int legCount,
                                 boolean idempotentReplay, List<ClearingInstruction> instructions) {

        static ClearingResult completed(String basisRef, String bizScene, BigDecimal total,
                                        List<ClearingInstruction> instructions) {
            return new ClearingResult(basisRef, bizScene, total, instructions.size(), false, instructions);
        }

        static ClearingResult existing(String basisRef, String bizScene, List<ClearingInstruction> instructions) {
            BigDecimal total = instructions.stream()
                    .map(ClearingInstruction::getAmount)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ClearingResult(basisRef, bizScene, total, instructions.size(), true, instructions);
        }
    }
}
