/**
 * 托管资金域（L2）：托管点位（账本账户 ↔ 真实托管/备付金账户映射）与虚拟子户。
 *
 * <p>领域边界约束（ArchUnit 守护）：本域实体/仓储只允许本域访问，跨域协作走
 * 应用服务（{@code FundsLocationService} / {@code VirtualSubAccountService}）；
 * 与 ledger 域交互只经 {@code AccountService} / {@code LedgerService}，禁止直查他域表。
 */
package com.claw.server.domain.funds;
