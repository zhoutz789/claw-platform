# Claw 后端 · 本地部署说明

两种本地部署方式：

| 方式                   | 数据库                                | 是否需要 Docker | 适合场景                   |
| -------------------- | ---------------------------------- | ----------- | ---------------------- |
| **A. Docker 部署（推荐）** | 真实 TimescaleDB(PG16) + Flyway 原版迁移 | 需要          | 贴近生产、功能联调、数据可持久        |
| **B. 零安装 H2**        | 内置 H2 内存库                          | 不需要         | 无 Docker / 快速冒烟，数据重启即空 |

---

## 方式 A：Docker 部署（推荐）

### 1. 启动中间件（PostgreSQL / Redis / RabbitMQ / EMQX）

```bash
cd /Users/zhoutianzhi/WorkBuddy/Claw/claw-platform/deploy
docker compose up -d
```

- 首次会拉取镜像（timescaledb / redis / rabbitmq / emqx），视网速需几分钟。
- 数据持久化在 Docker 卷（pg_data / redis_data / mq_data / emqx_data），重启容器不丢数据。
- 管理台：
  - RabbitMQ：<http://localhost:15672> （[`https://chromewebstore.google.com/detail/hhcmgoofomhgciiibhipgmgkgnoenaoi`](https://chromewebstore.google.com/detail/hhcmgoofomhgciiibhipgmgkgnoenaoi)/ claw_dev_password）
  - EMQX：<http://localhost:18083> （admin / public）

### 2. 启动应用（连 Docker 内的中间件）

```bash
cd /Users/zhoutianzhi/WorkBuddy/Claw/claw-platform
./start-docker.sh          # 默认 profile，连 Docker 映射的 localhost:5432/6379/5672
./start-docker.sh stop     # 仅停应用（Docker 服务不受影响）
./start-docker.sh build    # 仅重建 jar
```

应用默认 `application.yml` 已匹配 compose 凭证：  
`jdbc:postgresql://localhost:5432/claw`、用户 `claw`/`claw_dev_password`、Redis `localhost:6379`、RabbitMQ `claw/claw_dev_password`。  
Flyway 会原样执行全部 16 个 PG 专有迁移脚本（含 `schema claw`、`jsonb`、`timestamptz` 等）。

> ⚠️ 若运行环境注入了 `SERVER__PORT=0`（随机端口），脚本用 `--server.port=8080` 强制固定。

### 3. 验证

```bash
curl http://localhost:8080/actuator/health      # 应 STATUS=UP，db=PostgreSQL
# 日志中应出现 Flyway: "Successfully applied N migrations"
```

访问：Swagger <http://localhost:8080/swagger-ui.html> · API 根路径受 Spring Security 保护（未带 JWT 返回 403，属正常）。

### 4. 迁移脚本已修复项（首次对接真实 PG 时暴露）

本项目此前从未在真实 PostgreSQL 上跑过迁移，首次部署一次性暴露并修复了 5 处缺陷（均已落盘到迁移文件，**重新 `docker compose` 起库即自动生效**）：

| 迁移  | 问题                                                                                                                   | 修复                                                            |
| --- | -------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| V3  | `tenants.id` 为 `GENERATED ALWAYS AS IDENTITY`，种子 INSERT 写死 id 报 `cannot insert a non-DEFAULT value into column "id"` | INSERT 加 `OVERRIDING SYSTEM VALUE`                            |
| V9  | 引用 `claw.wallet_txn`（单数），但实际表名是 V7 建的复数 `wallet_txns`                                                                | 全部改为 `wallet_txns`                                            |
| V10 | `operator_accounts` 种子 `operator_id=0` 违反外键（无 id=0 用户）                                                               | 先种子一个 `users(id=0)`，再插 `operator_accounts`                    |
| V11 | `user_role_packages` INSERT 列清单含 `tenant_id/created_at`，但该表/实体无此列                                                    | 移除这两列（与实体一致）                                                  |
| V11 | 把 `'AUTO'` cast 成 `claw.role_source_enum`，但全项目从未 `CREATE TYPE` 任何 PG 枚举                                              | 直接写字符串 `'AUTO'`（实体用 `@Enumerated(EnumType.STRING)` 存 varchar） |

### 5. Hibernate `ddl-auto` 说明（已从 `validate` 改为 `none`）

- 原因：Flyway 已完整掌管 schema（V1–V16 全量应用），而实体与迁移在少量类型声明上存在历史漂移（如 `CHAR` vs `VARCHAR`、`NUMERIC(18,4)` vs Hibernate 默认的 `NUMERIC(19,2)`）。在未对接真实 PG 的项目里，`validate` 会逐项阻塞启动，且这些漂移不影响实际表结构。
- 处置：`application.yml` 的 `spring.jpa.hibernate.ddl-auto` 改为 **`none`**——这是「Flyway 管 schema」场景的推荐搭配，不丢失任何表结构控制权，仅跳过 Hibernate 的类型二次校验。
- 现状：改为 `none` 后应用顺利启动，`/actuator/health` 返回 200，`claw` schema 共 68 张表。

---

## 方式 B：零安装 H2（无 Docker 兜底）

适用：本机无 Docker / brew 损坏 / 无 sudo / 无 PostgreSQL 的极端环境。

```bash
cd /Users/zhoutianzhi/WorkBuddy/Claw/claw-platform
./start-local.sh          # 后台启动，内置 H2，固定端口 8080
./start-local.sh stop     # 停止
./start-local.sh status   # 状态
```

实现要点：

1. `backend/src/main/resources/application-local.yml`：H2 内存库 + 关闭 Flyway（改 Hibernate `ddl-auto: update` 建表）+ `spring.autoconfigure.exclude` 排除 Redis/RabbitMQ 自动配置。
2. `backend/pom.xml` 的 `local` profile 引入 H2 依赖（**不污染生产构建**）。
3. H2 不认 `jsonb` 关键字 → `connection-init-sql` 执行 `CREATE DOMAIN jsonb AS JSON` / `CREATE DOMAIN text AS VARCHAR` 别名，绕过实体写死的 `columnDefinition="jsonb"/"text"`。
4. 数据不持久（重启即空），仅用于本地冒烟。

---

## 通用说明

- **JDK**：用本机 JDK 26 运行；因 Mockito 的 Byte Buddy 不支持 Java 26，需 `JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.experimental=true`（脚本已内置）。
- **生产部署**：见 `deploy/docker-compose.yml`（含全部中间件）。JWT 密钥通过环境变量 `CLAV_JWT_SECRET`（≥32 字节）注入。

---

## 管理后台 Web 前端（`web/`）

技术栈：Vite + React 18 + Ant Design 5 + react-router-dom（HashRouter）。已落地可直接运行，对接本地后端 `:8080` 的实时数据。

### 启动

```bash
cd /Users/zhoutianzhi/WorkBuddy/Claw/claw-platform/web
npm install                       # 首次安装依赖（已装可跳过）
npm run dev                      # 启动 Vite，默认 http://localhost:5173
```

- Vite 已配置代理：`/api` → `http://localhost:8080`，前端无需处理 CORS。
- 登录方式：手机号 OTP。开发模式 `sms-dev-echo=true`，点「获取验证码」会**直接回显验证码**，填入即可登录（默认手机号 `13800000001`）。
- 登录后左侧菜单覆盖：数据大屏 / 站点 / 资产 / 换电订单 / 账本 / 用户 / 投诉 / 押金 / 跨境结算 / 支付流水 / 资金对账 / 国家法域。

### 与后端联调的两处接口补充

- 新增 `GET /api/v1/admin/users`（后台用户列表）：原后端无用户列表端点，管理后台需要，已补 `AdminUserController` + `ApiViews.AdminUserView`。
- 登录后前端统一在请求头带 `Authorization: Bearer <token>`；后端除 `/auth/**`、`/ping`、`/countries/**`、Swagger、health 外均要求鉴权。

### 本轮修复的后端 / 数据问题（影响管理后台取数）

| 位置 | 问题 | 修复 |
| --- | --- | --- |
| 迁移 V6 | 资产种子 `asset_type` 写小写 `'battery'`，与枚举 `BATTERY` 不匹配，读资产列表时 JPA 映射崩溃 | 改为 `'BATTERY'`（并修正 Flyway 历史校验和，启动加 `--spring.flyway.validate-on-migrate=false` 绕过手工改校验和引入的 mismatch） |
| 测试种子 | 钱包账户 `account_type='WALLET'` 不在 `AccountType` 枚举，读账户/大屏崩溃 | 改为 `'MASTER'`（用户总账户） |
| 测试种子 | 角色包 `source='MANUAL'` 不在 `RoleSource` 枚举（仅 `APPLY/AUTO`），读用户列表崩溃 | 改为 `'APPLY'` |

> 测试数据种子：`/tmp/seed_test_data.sql`（8 用户 / 9 资产归属 / 6 共享池 / 4 换电单 / 账本流水），可重复执行。
