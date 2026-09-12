package com.claw.server.domain.ocpp;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.ServletContextInitializer;

/**
 * OCPP WebSocket 端点注册器。
 *
 * <p>不依赖 {@code spring-websocket}（本仓库未引入），改用 JSR-356
 * {@code jakarta.websocket} API：在 Servlet 上下文启动时从 {@code ServerContainer}
 * 注册 {@link OcppWebSocketEndpoint}（路径 {@code /ocpp/{chargePointId}}）。
 *
 * <p>端点实例由 Spring 容器提供（Configurator 返回 Spring Bean），故端点可正常注入各域服务。
 * 若运行环境未提供 ServerContainer（如纯单元测试上下文），仅记日志、不影响应用启动。
 */
@Configuration
@Slf4j
public class OcppWsRegistrar implements ServletContextInitializer, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onStartup(ServletContext servletContext) {
        ServerContainer container = (ServerContainer) servletContext.getAttribute(
                ServerContainer.class.getName());
        if (container == null) {
            log.warn("[OCPP] 未找到 ServerContainer（非 Web/WebSocket 运行环境），跳过 WS 端点注册");
            return;
        }
        ServerEndpointConfig cfg = ServerEndpointConfig.Builder
                .create(OcppWebSocketEndpoint.class, "/ocpp/{chargePointId}")
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getEndpointInstance(Class<T> clazz) {
                        return (T) applicationContext.getBean(OcppWebSocketEndpoint.class);
                    }
                })
                .build();
        try {
            container.addEndpoint(cfg);
            log.info("[OCPP] WebSocket 端点已注册：/ocpp/{{chargePointId}}");
        } catch (Exception e) {
            log.error("[OCPP] WebSocket 端点注册失败", e);
        }
    }
}
