/**
 * 支付域：KHQR 收单 + ABA 受托 + Bakong 清算（S4）
 *
 * 领域边界约束（ArchUnit 守护）：本域实体/仓储只允许本域访问，
 * 跨域协作走领域事件或应用服务，禁止直查他域表。
 */
package com.claw.server.domain.payment;
