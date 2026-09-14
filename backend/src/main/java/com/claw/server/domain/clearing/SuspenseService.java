package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.SuspenseDiffType;
import com.claw.server.common.enums.SuspenseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 差错挂账服务（L5）：差异登记与处置（设计 §8）。
 *
 * <p>{@link #record} 落一条 {@link SuspenseEntry}（{@code status=OPEN}），
 * 差异类型须为 {@link SuspenseDiffType} 五类之一（CHANNEL_EXTRA / BOOK_EXTRA /
 * AMOUNT_MISMATCH / FX_DIFF / UNMATCHED）；{@link #resolve} 将 OPEN / PROCESSING
 * 工单处置为 {@code RESOLVED} 或 {@code WRITTEN_OFF}（核销）。
 *
 * <p>金额约定：正=长款（账本多于通道），负=短款（账本少于通道）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuspenseService {

    private final SuspenseEntryRepository suspenseEntryRepository;

    /**
     * 登记一笔差错差异工单。
     *
     * @param diffType    差异类型（见 {@link SuspenseDiffType}）
     * @param channelRef  通道回执/流水号
     * @param ledgerRef   账本 bizRef
     * @param amount      金额（正=长款，负=短款）
     * @param currency    币种（空则默认 USD）
     * @param reconRunId  关联 reconciliation_runs.id（可为空）
     * @return 已落库的工单
     * @throws BizException diffType 非法
     */
    @Transactional
    public SuspenseEntry record(String diffType, String channelRef, String ledgerRef,
                               BigDecimal amount, String currency, Long reconRunId) {
        if (!SuspenseDiffType.isValid(diffType)) {
            throw BizException.invalidParam("error.clearing.request.invalid");
        }
        Instant now = Instant.now();
        SuspenseEntry entry = SuspenseEntry.builder()
                .entryNo(generateEntryNo())
                .diffType(diffType)
                .channelRef(channelRef)
                .ledgerRef(ledgerRef)
                .amount(amount)
                .currency(currency == null || currency.isBlank() ? "USD" : currency)
                .reconRunId(reconRunId)
                .status(SuspenseStatus.OPEN)
                .tenantId(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        SuspenseEntry saved = suspenseEntryRepository.save(entry);
        log.info("[Suspense] 登记差错工单 {} diffType={} amount={} reconRunId={}",
                saved.getEntryNo(), diffType, amount, reconRunId);
        return saved;
    }

    /**
     * 处置工单：OPEN / PROCESSING → RESOLVED 或 WRITTEN_OFF。
     *
     * <p>处置结论含 {@code WRITTEN_OFF} 字样视为核销（{@link SuspenseStatus#WRITTEN_OFF}），
     * 否则视为正常冲销（{@link SuspenseStatus#RESOLVED}）。
     *
     * @param id          工单 id
     * @param operatorId  处置人 id
     * @param resolution  处置说明（含 {@code WRITTEN_OFF} 则核销）
     * @return 更新后的工单
     * @throws BizException 工单不存在或已处置
     */
    @Transactional
    public SuspenseEntry resolve(Long id, Long operatorId, String resolution) {
        SuspenseEntry entry = suspenseEntryRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.clearing.suspense.not.found", id));
        if (entry.getStatus() == SuspenseStatus.RESOLVED || entry.getStatus() == SuspenseStatus.WRITTEN_OFF) {
            throw BizException.of(40960, "error.clearing.suspense.already.resolved", id);
        }
        boolean writtenOff = resolution != null && resolution.contains("WRITTEN_OFF");
        entry.setStatus(writtenOff ? SuspenseStatus.WRITTEN_OFF : SuspenseStatus.RESOLVED);
        entry.setResolution(resolution);
        entry.setResolvedBy(operatorId);
        entry.setResolvedAt(Instant.now());
        entry.setUpdatedAt(Instant.now());
        SuspenseEntry saved = suspenseEntryRepository.save(entry);
        log.info("[Suspense] 处置工单 {} → {}（operator={}）", id, saved.getStatus(), operatorId);
        return saved;
    }

    private static String generateEntryNo() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "SP-" + System.currentTimeMillis() + "-" + suffix;
    }
}
