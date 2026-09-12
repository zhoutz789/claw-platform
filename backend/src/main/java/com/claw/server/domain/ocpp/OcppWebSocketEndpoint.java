package com.claw.server.domain.ocpp;

import com.claw.server.domain.iot.TelemetryLatestRepository;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.CloseReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OCPP 1.6J WebSocket 端点（{@code /ocpp/{chargePointId}}）。
 *
 * <p>后端直接开 WS 端点（不经 EMQX）：充电桩以 chargePointId 作路径连接；帧收发与路由
 * 委托给 {@link OcppMessageRouter}，域动作委托给 {@link OcppAdapter}。本类只做传输层：
 * 连接登记 / 帧解析 / 回送 CALLRESULT / 报文日志（IN/OUT）。
 *
 * <p>实例由 Spring 容器管理（{@code OcppWsRegistrar} 的 Configurator 返回 Spring Bean），
 * 故可注入各域服务；JSR-356 容器不另建实例。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OcppWebSocketEndpoint extends Endpoint {

    private final OcppSessionRegistry registry;
    private final OcppAdapter adapter;
    private final OcppMessageRouter router;
    private final OcppMessageLogRepository logRepo;

    @PostConstruct
    public void init() {
        log.info("[OCPP] WebSocket 端点 Bean 已就绪（路径 /ocpp/{{chargePointId}}）");
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        String chargePointId = session.getPathParameters().get("chargePointId");
        if (chargePointId == null || chargePointId.isBlank()) {
            log.warn("[OCPP] 缺少 chargePointId，拒绝连接");
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "missing chargePointId"));
            } catch (Exception ignored) {
            }
            return;
        }
        registry.register(chargePointId, session);
        log.info("[OCPP] 连接建立 chargePointId={}", chargePointId);

        session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                handle(chargePointId, session, message);
            }
        });
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        String chargePointId = session.getPathParameters().get("chargePointId");
        if (chargePointId != null) {
            registry.unregister(chargePointId);
        }
        log.info("[OCPP] 连接关闭 chargePointId={} reason={}", chargePointId, closeReason);
    }

    @Override
    public void onError(Session session, Throwable thr) {
        String chargePointId = session != null ? session.getPathParameters().get("chargePointId") : null;
        log.warn("[OCPP] 连接异常 chargePointId={}：{}", chargePointId, thr.getMessage());
    }

    /** 处理一帧入站文本（CALL / CALLRESULT / CALLERROR）。 */
    private void handle(String chargePointId, Session session, String raw) {
        registry.touch(chargePointId);
        OcppMessageRouter.ParsedMessage parsed = router.parse(raw);

        if (parsed.type() == 0) {
            log.warn("[OCPP] 非法帧 chargePointId={} raw={}", chargePointId,
                    raw != null && raw.length() > 512 ? raw.substring(0, 512) : raw);
            logIn(chargePointId, "UNKNOWN", null, raw);
            return;
        }

        // 日志：IN
        String inType = parsed.isCall() ? "CALL" : parsed.isCallResult() ? "CALLRESULT" : "CALLERROR";
        logIn(chargePointId, inType, parsed.messageId(), raw);

        try {
            if (parsed.isCall()) {
                String response = adapter.handleCall(chargePointId, parsed.messageId(), parsed.action(), parsed.payload());
                send(session, chargePointId, response);
            } else if (parsed.isCallResult()) {
                adapter.handleCallResult(chargePointId, parsed.messageId(), parsed.payload());
            } else {
                adapter.handleCallError(chargePointId, parsed.messageId(), parsed.errorCode(), parsed.errorDesc());
            }
        } catch (Exception e) {
            log.error("[OCPP] 帧处理异常 chargePointId={}", chargePointId, e);
        }
    }

    /** 经会话回送帧（OUT 日志在 send 内落库）。 */
    private void send(Session session, String chargePointId, String frame) {
        if (frame == null || frame.isBlank()) {
            return;
        }
        logOut(chargePointId, "CALLRESULT", frame);
        if (session.isOpen()) {
            try {
                session.getAsyncRemote().sendText(frame);
            } catch (Exception e) {
                log.warn("[OCPP] 回送帧失败 chargePointId={}：{}", chargePointId, e.getMessage());
            }
        }
    }

    private void logIn(String chargePointId, String msgType, String msgId, String raw) {
        try {
            logRepo.save(OcppMessageLog.builder()
                    .stationId(chargePointId).direction("IN").msgType(msgType)
                    .msgId(msgId).payloadJson(raw).build());
        } catch (Exception e) {
            log.debug("[OCPP] 报文日志(IN)失败 chargePointId={}：{}", chargePointId, e.getMessage());
        }
    }

    private void logOut(String chargePointId, String msgType, String raw) {
        try {
            logRepo.save(OcppMessageLog.builder()
                    .stationId(chargePointId).direction("OUT").msgType(msgType)
                    .payloadJson(raw).build());
        } catch (Exception e) {
            log.debug("[OCPP] 报文日志(OUT)失败 chargePointId={}：{}", chargePointId, e.getMessage());
        }
    }
}
