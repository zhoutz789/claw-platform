/**
 * 共享池域：资产产权/入池/租赁/计费/分账/结算（V12, Phase 2）
 *
 * <p>领域边界约束（ArchUnit 守护）：本域实体/仓储只允许本域访问，
 * 跨域协作走领域事件或应用服务，禁止直查他域表。
 *
 * <p>对应 PRD 4.16：共享车辆/电池池运营 + D39/D40 灵活换电。
 */
package com.claw.server.domain.sharedpool;
