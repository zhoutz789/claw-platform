/**
 * 账户域：复式记账引擎、总/资产/子账户、三专户、押金流转（S2）
 *
 * 领域边界约束（ArchUnit 守护）：本域实体/仓储只允许本域访问，
 * 跨域协作走领域事件或应用服务，禁止直查他域表。
 */
package com.claw.server.domain.ledger;
