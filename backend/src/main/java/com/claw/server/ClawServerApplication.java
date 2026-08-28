package com.claw.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Claw 新能源资产全生命周期运营管理平台 — 模块化单体入口。
 *
 * 领域分包（对应技术文档 1.2 / 1.3，拆分边界由 ArchUnit 守护）：
 *  domain.user        用户/权限/KYC（CamDigiKey）
 *  domain.role        人人经济角色包（Prosumer）
 *  domain.asset       资产域（车辆/电池/充电桩/光伏电站 + 状态机）
 *  domain.ledger      账户域（复式记账/三专户/押金）
 *  domain.order       订单域（换电/充电/状态机）
 *  domain.iot         IoT 域（遥测/轨迹/锁车指令）
 *  domain.payment     支付域（KHQR/ABA 受托/Bakong）
 *  domain.notification 通知域（推送）
 *  domain.report      报表域（大屏/对账）
 */
@SpringBootApplication
@EnableScheduling
public class ClawServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClawServerApplication.class, args);
    }
}
