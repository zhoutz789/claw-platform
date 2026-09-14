package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ClearingRequests;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.CustodyOwnerType;
import com.claw.server.domain.funds.VirtualSubAccountRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 清分编排服务（L5）：算分账 → 逐腿记账（含 WHT 代扣）→ 落清分指令。
 *
 * <p><b>记账唯一出口</b>：逐腿调用既有 {@link LedgerService#postEntries}，<b>不新造记账</b>、
 * 不改写账路径。每腿一笔过账，分录为「借 平台对冲户（MASTER, user_id=NULL，余额豁免）/
 * 贷 该收款方应付或收入子户」，借贷守恒由账本引擎兜底。
 *
 * <p><b>WHT 代扣（T11）</b>：每腿在记账前先经 {@link WhtEngine} 计算预扣税。若 WHT &gt; 0，
 * 该腿分录扩展为「借 平台对冲户(gross) / 贷 收款方应付子户(net) / 贷 WHT_PAYABLE(whtAmount)」，
 * 借贷仍守恒（gross = net + whtAmount）；并落一条 {@code tax_withholding} 台账（tax_period = 清分月）。
 * 清分指令的 {@code amount} 取<b>净额 net</b>（实际应付收款方金额）。WHT=0 时退化成「借 gross / 贷 gross」，
 * 不写 {@code tax_withholding}、不记 WHT_PAYABLE。
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
     * 收款方类型 → 账本科目映射（R1 扫码购口径 + R5 共享池口径）。
     *
     * <p>OWNER/INSURER 随 T08（R5）接入；PLATFORM/MANUFACTURER/STATION/LOGISTICS 为 R1 口径。
     * 未映射的收款方类型一律抛 {@code error.clearing.payee.unsupported}，不静默错账。
     */
    private static final Map<String, AccountType> PAYEE_ACCOUNT_TYPES = Map.of(
            "PLATFORM", AccountType.PLATFORM_REVENUE,
            "MANUFACTURER", AccountType.PAYABLE_MFG,
            "STATION", AccountType.PAYABLE_STATION,
            "LOGISTICS", AccountType.PAYABLE_LOGISTICS,
            "OWNER", AccountType.PAYABLE_OWNER,
            "INSURER", AccountType.PAYABLE_INSURER);

    /**
     * 收款方类型 → 虚拟子户持有方类型（用于解析收款方税务档案 ownerType，T11 WHT 判定）。
     *
     * <p>平台自有收入（PLATFORM）无第三方收款方 → 不代扣（映射为 {@code null}）。
     * R5 所有人（OWNER）即资产投资人 → 映射到 {@code INVESTOR}（最佳努力解析，口径落定前按类型尽力解析；
     * 具体主体 ownerId 映射依赖 principal_bindings，属设计 §11.4 待明确项，此处按类型取首条 ACTIVE 子户）。
     */
    private static final Map<String, CustodyOwnerType> PAYEE_OWNER_TYPE = Map.of(
            "MANUFACTURER", CustodyOwnerType.MANUFACTURER,
            "STATION", CustodyOwnerType.STATION,
            "LOGISTICS", CustodyOwnerType.LOGISTICS,
            "INSURER", CustodyOwnerType.INSURER,
            "OWNER", CustodyOwnerType.INVESTOR);

    private final SplitEngine splitEngine;
    private final ClearingInstructionService clearingInstructionService;
    private final LedgerService ledgerService;
    private final AccountService accountService;
    private final WhtEngine whtEngine;
    private final VirtualSubAccountRepository virtualSubAccountRepository;
    private final TaxWithholdingRepository taxWithholdingRepository;

    /**
     * R1/R5 统一入口：算分账 → 逐腿 {@code postEntries}（含 WHT 代扣）→ 生成 clearing_instruction。
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
        Long whtAccountId = accountService.getOrCreatePlatformAccount(AccountType.WHT_PAYABLE).getId();
        String taxPeriod = YearMonth.now().toString(); // YYYY-MM

        List<ClearingInstruction> instructions = new ArrayList<>(legs.size());
        for (SplitEngine.SplitLeg leg : legs) {
            Long payeeAccountId = resolvePayeeAccount(leg.payeeType());
            Long payeeVsaId = resolvePayeeVsaId(leg.payeeType(), currency);
            WhtEngine.WhtResult wht = whtEngine.compute(payeeVsaId, leg.amount(), currency);

            BigDecimal gross = leg.amount();
            BigDecimal net = wht.net();
            BigDecimal whtAmount = wht.whtAmount();

            String ledgerBizRef = cmd.basisRef() + ":" + leg.payeeType();
            List<LedgerRequests.Entry> entries = new ArrayList<>(3);
            entries.add(new LedgerRequests.Entry(offsetAccountId, LedgerRequests.Direction.D, gross,
                    "clearing offset " + cmd.basisRef()));
            entries.add(new LedgerRequests.Entry(payeeAccountId, LedgerRequests.Direction.C, net,
                    "clearing credit " + leg.payeeType() + " net"));
            if (whtAmount.signum() > 0) {
                entries.add(new LedgerRequests.Entry(whtAccountId, LedgerRequests.Direction.C, whtAmount,
                        "WHT withheld " + leg.payeeType()));
            }

            ledgerService.postEntries(BizType.CLEARING_SETTLE, ledgerBizRef, entries);
            ClearingInstruction instruction = clearingInstructionService.create(
                    cmd.scene(), mode, leg, cmd.basisRef(), currency, payeeAccountId, cmd.channel(), net);

            // WHT>0 时落代扣台账（与指令一对一；WHT=0 不写本表）
            if (whtAmount.signum() > 0) {
                taxWithholdingRepository.save(TaxWithholding.builder()
                        .clearingInstructionId(instruction.getId())
                        .payeeVsaId(payeeVsaId)
                        .grossAmount(gross)
                        .whtRate(wht.whtRate())
                        .whtAmount(whtAmount)
                        .netAmount(net)
                        .currency(currency)
                        .taxPeriod(taxPeriod)
                        .paidStatus("UNPAID")
                        .build());
            }
            instructions.add(instruction);
        }
        log.info("[Clearing] basisRef={} 清分完成，{} 腿，合计 {}", cmd.basisRef(), legs.size(), cmd.total());
        return ClearingResult.completed(cmd.basisRef(), cmd.bizScene(), cmd.total(), instructions, legs);
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
     * @throws BizException 收款方类型未映射（如未来新增未接入方）
     */
    private Long resolvePayeeAccount(String payeeType) {
        AccountType type = PAYEE_ACCOUNT_TYPES.get(payeeType);
        if (type == null) {
            throw BizException.invalidParam("error.clearing.payee.unsupported", payeeType);
        }
        return accountService.getOrCreatePlatformAccount(type).getId();
    }

    /**
     * 解析收款方虚拟子户 id（用于 WHT 税务档案读取，仅第三方收款方需要）。
     *
     * @param payeeType 收款方类型
     * @param currency  币种
     * @return 虚拟子户 id；平台自有收入（PLATFORM）或无命中子户时返回 {@code null}（不代扣）
     */
    private Long resolvePayeeVsaId(String payeeType, String currency) {
        CustodyOwnerType ownerType = PAYEE_OWNER_TYPE.get(payeeType);
        if (ownerType == null) {
            return null;
        }
        return virtualSubAccountRepository
                .findByOwnerTypeAndCurrencyAndStatusAndDeletedFalseOrderByIdAsc(ownerType, currency, "ACTIVE")
                .stream()
                .findFirst()
                .map(com.claw.server.domain.funds.VirtualSubAccount::getId)
                .orElse(null);
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
     * @param legs             各腿分账明细（{@code payeeType + amount + settleCycle}）。
     *                         <p>通道层（{@code ConsignmentClearingHandler} / {@code ChannelSplitPlanner}）需要
     *                         「收款方类型」来解析 ABA 账户并做尾差吸收；{@code clearing_instruction} 表刻意
     *                         不冗余 {@code payee_type} 列（只存 {@code idem_key} / {@code ledger_biz_ref}），
     *                         故由本结果对象透出，避免调用方去解析字符串后缀。
     *                         <b>幂等重放时为空表</b>（既有指令已含金额，通道动作早已发生过）。
     */
    public record ClearingResult(String basisRef, String bizScene, BigDecimal total, int legCount,
                                 boolean idempotentReplay, List<ClearingInstruction> instructions,
                                 List<SplitEngine.SplitLeg> legs) {

        static ClearingResult completed(String basisRef, String bizScene, BigDecimal total,
                                        List<ClearingInstruction> instructions, List<SplitEngine.SplitLeg> legs) {
            return new ClearingResult(basisRef, bizScene, total, instructions.size(), false, instructions, legs);
        }

        static ClearingResult existing(String basisRef, String bizScene, List<ClearingInstruction> instructions) {
            BigDecimal total = instructions.stream()
                    .map(ClearingInstruction::getAmount)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ClearingResult(basisRef, bizScene, total, instructions.size(), true, instructions, List.of());
        }
    }
}
