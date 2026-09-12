package com.claw.server.domain.ocpp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OCPP 会话注册表：chargePointId ↔ WS 连接 的内存映射（心跳超时剔除）。
 *
 * <p>充电桩经 {@code /ocpp/{chargePointId}} 接入后在此登记；心跳（Heartbeat / 任意报文）
 * 刷新 {@code lastHeartbeat}。后台定时扫描，超过 {@code heartbeat-timeout-ms} 未活动的连接
 * 视为掉线：从表移除、关闭 WS、并把 {@code ocpp_charging_stations.status} 置 OFFLINE。
 */
@Component
@Slf4j
public class OcppSessionRegistry {

    private final ChargingStationRepository stationRepository;
    private final long heartbeatTimeoutMs;

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ocpp-session-sweeper");
                t.setDaemon(true);
                return t;
            });

    public OcppSessionRegistry(ChargingStationRepository stationRepository,
                               @Value("${claw.ocpp.heartbeat-timeout-ms:90000}") long heartbeatTimeoutMs) {
        this.stationRepository = stationRepository;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    @PostConstruct
    public void init() {
        // 每 1/3 超时周期扫一次，及时剔除掉线连接
        long period = Math.max(1_000, heartbeatTimeoutMs / 3);
        sweeper.scheduleAtFixedRate(this::sweep, period, period, TimeUnit.MILLISECONDS);
        log.info("[OCPP] 会话注册表已启动，心跳超时={}ms", heartbeatTimeoutMs);
    }

    @PreDestroy
    public void destroy() {
        sweeper.shutdownNow();
        for (Map.Entry<String, SessionState> e : sessions.entrySet()) {
            closeQuietly(e.getKey(), e.getValue().session());
        }
        sessions.clear();
    }

    /** 登记 / 刷新连接（连接建立或收到任意报文时调用）。 */
    public void register(String chargePointId, Session session) {
        sessions.compute(chargePointId, (k, old) -> {
            if (old != null) {
                closeQuietly(k, old.session());
            }
            return new SessionState(session, Instant.now());
        });
        log.info("[OCPP] 充电桩上线 chargePointId={}（在线数={}）", chargePointId, sessions.size());
    }

    /** 注销连接（连接关闭时调用）。 */
    public void unregister(String chargePointId) {
        SessionState removed = sessions.remove(chargePointId);
        if (removed != null) {
            closeQuietly(chargePointId, removed.session());
            markOffline(chargePointId);
            log.info("[OCPP] 充电桩离线 chargePointId={}（在线数={}）", chargePointId, sessions.size());
        }
    }

    /** 刷新心跳时间戳（收到 Heartbeat 或任意帧时调用）。 */
    public void touch(String chargePointId) {
        SessionState s = sessions.get(chargePointId);
        if (s != null) {
            s.touch(Instant.now());
        }
    }

    /** 取连接（下发指令用）；离线返回 null。 */
    public Session getSession(String chargePointId) {
        SessionState s = sessions.get(chargePointId);
        return s == null ? null : s.session();
    }

    public boolean isOnline(String chargePointId) {
        return sessions.containsKey(chargePointId);
    }

    /** 经 WS 下发帧；离线或发送失败返回 false。 */
    public boolean send(String chargePointId, String frame) {
        Session session = getSession(chargePointId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            session.getAsyncRemote().sendText(frame);
            return true;
        } catch (Exception e) {
            log.warn("[OCPP] 下发帧失败 chargePointId={}：{}", chargePointId, e.getMessage());
            return false;
        }
    }

    public int onlineCount() {
        return sessions.size();
    }

    // ===================== 内部 =====================

    private void sweep() {
        try {
            Instant now = Instant.now();
            for (Map.Entry<String, SessionState> e : sessions.entrySet()) {
                if (now.toEpochMilli() - e.getValue().lastHeartbeat().toEpochMilli() > heartbeatTimeoutMs) {
                    String cp = e.getKey();
                    closeQuietly(cp, e.getValue().session());
                    sessions.remove(cp);
                    markOffline(cp);
                    log.warn("[OCPP] 心跳超时剔除 chargePointId={}", cp);
                }
            }
        } catch (Exception e) {
            log.warn("[OCPP] 会话扫描异常", e);
        }
    }

    private void markOffline(String chargePointId) {
        try {
            stationRepository.findByChargePointId(chargePointId).ifPresent(station -> {
                if (!"OFFLINE".equals(station.getStatus())) {
                    station.setStatus("OFFLINE");
                    stationRepository.save(station);
                }
            });
        } catch (Exception e) {
            log.warn("[OCPP] 置 OFFLINE 失败 chargePointId={}", chargePointId, e);
        }
    }

    private void closeQuietly(String chargePointId, Session session) {
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException e) {
            log.debug("[OCPP] 关闭会话异常 chargePointId={}：{}", chargePointId, e.getMessage());
        }
    }

    /** 会话状态：连接 + 最近心跳时间。 */
    private static final class SessionState {
        private final Session session;
        private volatile Instant lastHeartbeat;

        SessionState(Session session, Instant lastHeartbeat) {
            this.session = session;
            this.lastHeartbeat = lastHeartbeat;
        }

        Session session() {
            return session;
        }

        Instant lastHeartbeat() {
            return lastHeartbeat;
        }

        void touch(Instant t) {
            this.lastHeartbeat = t;
        }
    }
}
