package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.enums.ClearingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 清分指令服务（L5）：指令落库 + 状态机（设计 §6.1）。
 *
 * <pre>
 * CREATED ──send──> SENT ──ack──> ACKED ──settle──> SETTLED
 *                     │             │
 *                     └──fail──> FAILED ──retry(&le;3)──> SENT
 *                                   │
 *                                   └──exhaust/需人工──> MANUAL ──(admin)──> ...
 * </pre>
 *
 * <p>终态：{@link ClearingStatus#SETTLED} / {@link ClearingStatus#MANUAL}（不可再转移）。
 * 幂等：{@code instruction_no}(UNIQUE) + {@code idem_key}(UNIQUE)；{@link #create} 命中既有
 * idem_key 时返回既有指令，不重复落库。重试上限 {@value #MAX_RETRY} 次，超出转 MANUAL。
 *
 * <p>通道动作（下发/回执）在 T06 由通道 SPI 触发；本服务只维护状态与字段。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClearingInstructionService {

    /** 最大重试次数。 */
    public static final int MAX_RETRY = 3;

    /** 非法状态迁移错误码。 */
    private static final int CODE_ILLEGAL_TRANSITION = 40903;

    private final ClearingInstructionRepository clearingInstructionRepository;

    // ===================== 创建 =====================

    /**
     * 创建指令（设计 §4.2 签名）。
     *
     * @param scene    清分场景
     * @param mode     清分时机模式
     * @param leg      分账腿
     * @param basisRef 依据单号
     * @param currency 币种（空则默认 USD）
     * @return 新建或已存在的指令
     */
    @Transactional
    public ClearingInstruction create(ClearingScene scene, ClearingMode mode, SplitEngine.SplitLeg leg,
                                      String basisRef, String currency) {
        return create(scene, mode, leg, basisRef, currency, null, null, null);
    }

    /**
     * 创建指令（含收款账户 / 通道）。
     *
     * @param scene          清分场景
     * @param mode           清分时机模式
     * @param leg            分账腿
     * @param basisRef       依据单号
     * @param currency       币种（空则默认 USD）
     * @param payeeAccountId 账本收款账户 id（可空）
     * @param channel        通道标识（可空）
     * @return 新建或已存在的指令
     * @throws BizException 入参缺失
     */
    @Transactional
    public ClearingInstruction create(ClearingScene scene, ClearingMode mode, SplitEngine.SplitLeg leg,
                                      String basisRef, String currency, Long payeeAccountId, String channel) {
        return create(scene, mode, leg, basisRef, currency, payeeAccountId, channel, null);
    }

    /**
     * 创建指令（含收款账户 / 通道 / 显式金额）。
     *
     * @param scene          清分场景
     * @param mode           清分时机模式
     * @param leg            分账腿
     * @param basisRef       依据单号
     * @param currency       币种（空则默认 USD）
     * @param payeeAccountId 账本收款账户 id（可空）
     * @param channel        通道标识（可空）
     * @param amount         指令金额（可空；为空时回退 {@code leg.amount()}，即 WHT 前毛额）。
     *                       T11 WHT 代扣场景下传<b>净额 net</b>（毛额 − 代扣税）。
     * @return 新建或已存在的指令
     * @throws BizException 入参缺失
     */
    @Transactional
    public ClearingInstruction create(ClearingScene scene, ClearingMode mode, SplitEngine.SplitLeg leg,
                                      String basisRef, String currency, Long payeeAccountId, String channel,
                                      BigDecimal amount) {
        if (scene == null || mode == null || leg == null || leg.payeeType() == null) {
            throw BizException.invalidParam("error.clearing.request.invalid");
        }
        String ccy = (currency == null || currency.isBlank()) ? "USD" : currency;
        String idemKey = idemKey(scene, basisRef, leg.payeeType());

        Optional<ClearingInstruction> existing = clearingInstructionRepository.findByIdemKey(idemKey);
        if (existing.isPresent()) {
            log.info("[Clearing] 指令已存在（idemKey={}），跳过重复创建", idemKey);
            return existing.get();
        }

        BigDecimal finalAmount = amount != null ? amount : leg.amount();
        Instant now = Instant.now();
        ClearingInstruction instruction = ClearingInstruction.builder()
                .instructionNo(generateInstructionNo(scene))
                .idemKey(idemKey)
                .scene(scene)
                .mode(mode)
                .payeeAccountId(payeeAccountId)
                .amount(finalAmount)
                .currency(ccy)
                .basisRef(basisRef)
                .ledgerBizType(BizType.CLEARING_SETTLE.name())
                .ledgerBizRef(basisRef + ":" + leg.payeeType())
                .channel(channel)
                .status(ClearingStatus.CREATED)
                .retryCount(0)
                .tenantId(1L)
                .deleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return clearingInstructionRepository.save(instruction);
    }

    // ===================== 状态机 =====================

    /** CREATED→SENT。 */
    @Transactional
    public ClearingInstruction markSent(Long id, String channel) {
        ClearingInstruction instruction = get(id);
        requireStatus(instruction, ClearingStatus.SENT, ClearingStatus.CREATED);
        instruction.setStatus(ClearingStatus.SENT);
        if (channel != null && !channel.isBlank()) {
            instruction.setChannel(channel);
        }
        instruction.setSentAt(Instant.now());
        instruction.setUpdatedAt(Instant.now());
        return clearingInstructionRepository.save(instruction);
    }

    /** SENT→ACKED（通道回执）。 */
    @Transactional
    public ClearingInstruction onAck(Long id, String institutionRef) {
        ClearingInstruction instruction = get(id);
        requireStatus(instruction, ClearingStatus.ACKED, ClearingStatus.SENT);
        instruction.setStatus(ClearingStatus.ACKED);
        instruction.setInstitutionRef(institutionRef);
        instruction.setAckedAt(Instant.now());
        instruction.setUpdatedAt(Instant.now());
        return clearingInstructionRepository.save(instruction);
    }

    /** ACKED→SETTLED。 */
    @Transactional
    public ClearingInstruction markSettled(Long id) {
        ClearingInstruction instruction = get(id);
        requireStatus(instruction, ClearingStatus.SETTLED, ClearingStatus.ACKED);
        instruction.setStatus(ClearingStatus.SETTLED);
        instruction.setSettledAt(Instant.now());
        instruction.setUpdatedAt(Instant.now());
        return clearingInstructionRepository.save(instruction);
    }

    /** {CREATED|SENT|ACKED|RETRY}→FAILED。 */
    @Transactional
    public ClearingInstruction markFailed(Long id, String reason) {
        ClearingInstruction instruction = get(id);
        requireStatus(instruction, ClearingStatus.FAILED, ClearingStatus.CREATED, ClearingStatus.SENT,
                ClearingStatus.ACKED, ClearingStatus.RETRY);
        instruction.setStatus(ClearingStatus.FAILED);
        instruction.setFailReason(reason);
        instruction.setUpdatedAt(Instant.now());
        return clearingInstructionRepository.save(instruction);
    }

    /**
     * 重试（FAILED/RETRY→SENT，&le;{@value #MAX_RETRY} 次）。超出上限转 MANUAL。
     *
     * @param id 指令 id
     * @return 重试后（SENT）或升级（MANUAL）的指令
     * @throws BizException 指令不存在或当前状态不可重试
     */
    @Transactional
    public ClearingInstruction retry(Long id) {
        ClearingInstruction instruction = get(id);
        requireStatus(instruction, ClearingStatus.SENT, ClearingStatus.FAILED, ClearingStatus.RETRY);

        int next = nzRetry(instruction) + 1;
        instruction.setRetryCount(next);
        instruction.setUpdatedAt(Instant.now());
        if (next > MAX_RETRY) {
            instruction.setStatus(ClearingStatus.MANUAL);
            log.warn("[Clearing] 指令 {} 重试超限（{}），转 MANUAL", id, next);
        } else {
            instruction.setStatus(ClearingStatus.SENT);
            instruction.setSentAt(Instant.now());
        }
        return clearingInstructionRepository.save(instruction);
    }

    /** 任意非终态→MANUAL（需人工介入）。 */
    @Transactional
    public ClearingInstruction escalateManual(Long id, String reason) {
        ClearingInstruction instruction = get(id);
        if (isTerminal(instruction.getStatus())) {
            throw illegal(instruction, ClearingStatus.MANUAL);
        }
        instruction.setStatus(ClearingStatus.MANUAL);
        instruction.setFailReason(reason);
        instruction.setUpdatedAt(Instant.now());
        return clearingInstructionRepository.save(instruction);
    }

    // ===================== 查询 =====================

    /** 按业务幂等键查询（幂等重放）。 */
    @Transactional(readOnly = true)
    public Optional<ClearingInstruction> findByIdemKey(String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            return Optional.empty();
        }
        return clearingInstructionRepository.findByIdemKey(idemKey);
    }

    /** 按依据单号查询（结算幂等判定）。 */
    @Transactional(readOnly = true)
    public List<ClearingInstruction> findByBasisRef(String basisRef) {
        if (basisRef == null || basisRef.isBlank()) {
            return List.of();
        }
        return clearingInstructionRepository.findByBasisRef(basisRef);
    }

    /** 按主键取指令（不存在或已删除则抛异常）。 */
    @Transactional(readOnly = true)
    public ClearingInstruction get(Long id) {
        return clearingInstructionRepository.findById(id)
                .filter(i -> !Boolean.TRUE.equals(i.getDeleted()))
                .orElseThrow(() -> BizException.notFound("error.clearing.instruction.not.found", id));
    }

    // ===================== 内部 =====================

    /** 业务幂等键：scene + basisRef + payeeType。 */
    static String idemKey(ClearingScene scene, String basisRef, String payeeType) {
        return scene.name() + ":" + (basisRef == null ? "" : basisRef) + ":" + payeeType;
    }

    /** 指令号：CI-<scene>-<epochMillis>-<6HEX>。 */
    private static String generateInstructionNo(ClearingScene scene) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "CI-" + scene.name() + "-" + System.currentTimeMillis() + "-" + suffix;
    }

    private static boolean isTerminal(ClearingStatus status) {
        return status == ClearingStatus.SETTLED || status == ClearingStatus.MANUAL;
    }

    private static int nzRetry(ClearingInstruction instruction) {
        return instruction.getRetryCount() == null ? 0 : instruction.getRetryCount();
    }

    private void requireStatus(ClearingInstruction instruction, ClearingStatus target, ClearingStatus... allowed) {
        ClearingStatus current = instruction.getStatus();
        if (isTerminal(current)) {
            throw illegal(instruction, target);
        }
        for (ClearingStatus s : allowed) {
            if (s == current) {
                return;
            }
        }
        throw illegal(instruction, target);
    }

    private static BizException illegal(ClearingInstruction instruction, ClearingStatus target) {
        return BizException.of(CODE_ILLEGAL_TRANSITION, "error.clearing.instruction.illegal.transition",
                instruction.getStatus(), target);
    }
}
