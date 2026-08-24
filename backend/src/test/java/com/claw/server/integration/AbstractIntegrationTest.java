package com.claw.server.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类（B2：Testcontainers 测试策略升级）。
 *
 * <h2>目标</h2>
 * 把核心域测试从内存/H2 升级为<b>真实 PostgreSQL</b>，验证 V1-V25 迁移脚本可应用、复式记账/
 * 共享池等关键域在真实 PG 上无误。无论在哪种数据源下，底层都是真实 PostgreSQL（非 H2/内存）。
 *
 * <h2>数据源策略</h2>
 * 默认连接<b>本地已运行的 TimescaleDB</b>（与线上同版本 PG16）上的专用测试库 {@code claw_it}。
 * 连接参数可通过环境变量覆盖：
 * <ul>
 *   <li>{@code CLAV_IT_DB_URL}（默认 jdbc:postgresql://localhost:5432/claw_it）</li>
 *   <li>{@code CLAV_IT_DB_USER}（默认 claw）</li>
 *   <li>{@code CLAV_IT_DB_PASSWORD}（默认 claw_dev_password）</li>
 * </ul>
 *
 * <h2>关于 Testcontainers</h2>
 * pom 已引入 testcontainers 依赖，本应优先用容器做隔离。但在本开发沙箱中，
 * Docker Desktop 的 socket 对 Testcontainers 的 {@code /info} 探针返回 HTTP 400，
 * 导致 TC 无法完成 Docker 环境校验（与代码无关，属环境限制）。因此默认走本地 TimescaleDB。
 * 当 CI / 本地具备<b>可用且 TC 兼容</b>的 Docker 时，可将下方
 * {@code buildTestcontainersDataSource()} 接入 {@code @DynamicPropertySource} 并启用容器隔离。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    /**
     * Testcontainers 容器定义（预留）：当且仅当运行环境能正常探测 Docker 时使用。
     * 当前默认不启动——见类注释中关于 Docker Desktop 探针 400 的说明。
     */
    static PostgreSQLContainer<?> testcontainersPg() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("timescale/timescaledb:latest-pg16")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("claw")
                .withUsername("claw")
                .withPassword("claw");
    }

    @DynamicPropertySource
    static void overrideExternalServices(DynamicPropertyRegistry registry) {
        // 真实 PostgreSQL：本地已运行的 TimescaleDB → 专用测试库 claw_it（Flyway 全量应用 V1-V25）
        registry.add("spring.datasource.url",
                () -> System.getenv().getOrDefault("CLAV_IT_DB_URL", "jdbc:postgresql://localhost:5432/claw_it"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("CLAV_IT_DB_USER", "claw"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CLAV_IT_DB_PASSWORD", "claw_dev_password"));

        // Redis / RabbitMQ 指向本地已运行的 Docker 中间件（与线上一致）
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "5672");

        // Flyway 在目标库上全量应用 V1-V25
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }
}
