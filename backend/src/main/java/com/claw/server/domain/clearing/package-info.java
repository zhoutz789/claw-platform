/**
 * 分账/清分域（L5）：分账规则 → 分账引擎 → 结算批次 → 清分指令 → 多方入账；差错挂账。
 *
 * <p>领域边界约束（ArchUnit 守护）：本域实体/仓储只允许本域访问，跨域协作走
 * 应用服务（{@code SplitEngine} / {@code ClearingService} / {@code ClearingInstructionService} /
 * {@code SettlementBatchService} / {@code SuspenseService}）；记账唯一出口为 ledger 域
 * {@code LedgerService.postEntries}，账户经 {@code AccountService}，禁止直查他域表。
 */
package com.claw.server.domain.clearing;
