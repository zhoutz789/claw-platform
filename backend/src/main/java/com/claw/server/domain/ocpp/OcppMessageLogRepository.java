package com.claw.server.domain.ocpp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Async;

import java.time.Instant;

/**
 * OCPP 报文日志仓储。
 *
 * <p>生产建议：{@code save} 改为异步（{@code @Async} 需 {@code @EnableAsync}）并对早于
 * 保留期（如 30 天）的 record 定时清理，避免 {@code ocpp_message_log} 无限膨胀。
 * 当前为同步 best-effort 落库（调用方已 try/catch，日志失败不影响业务报文流）。
 */
public interface OcppMessageLogRepository extends JpaRepository<OcppMessageLog, Long> {
}
