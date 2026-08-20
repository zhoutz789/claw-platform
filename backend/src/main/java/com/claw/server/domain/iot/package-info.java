/**
 * IoT 域：遥测/轨迹（TimescaleDB）/断缴锁车指令（S5）
 *
 * 领域边界约束（ArchUnit 守护）：本域实体/仓储只允许本域访问，
 * 跨域协作走领域事件或应用服务，禁止直查他域表。
 */
package com.claw.server.domain.iot;
