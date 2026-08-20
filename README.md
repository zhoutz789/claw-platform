# Claw 新能源资产全生命周期运营管理平台

> 依据《PRD v1.1（定稿）》《技术开发文档 v0.4》搭建。首发市场：柬埔寨（金边及主要省城）。
> S0 状态：**工程脚手架已就绪（2026-08-20）**

## 仓库结构

```
claw-platform/
├── backend/          # Java 21 + Spring Boot 3.3 模块化单体
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/claw/server/
│       │   ├── ClawServerApplication.java
│       │   ├── common/api/        # 统一响应 ApiResult / BizException / 全局异常
│       │   ├── config/            # i18n 三语（en/km/zh）等配置
│       │   ├── domain/            # 领域分包（ArchUnit 守护边界）
│       │   │   ├── user/          #   用户/权限/KYC（CamDigiKey，S1）
│       │   │   ├── role/          #   人人经济角色包（S1）
│       │   │   ├── asset/         #   资产域：车/电池/桩/电站（S1）
│       │   │   ├── ledger/        #   账户域：复式记账/三专户（S2，封闭域）
│       │   │   ├── order/         #   订单域：换电/充电（S3）
│       │   │   ├── payment/       #   支付域：KHQR/ABA/Bakong（S4）
│       │   │   ├── iot/           #   IoT：遥测/轨迹/锁车（S5）
│       │   │   ├── notification/  #   通知域
│       │   │   └── report/        #   报表域：大屏/对账（S5）
│       │   └── infra/             # 冒烟接口等（S1 后移除）
│       └── resources/
│           ├── db/migration/V1__init_core_tables.sql   # Flyway 基础表
│           └── i18n/messages_{en,km,zh}.properties     # 三语资源
├── app/              # Flutter（C端 + 服务站 APP 共用代码库）
├── web/              # React + AntD 管理后台（S5）
├── deploy/
│   └── docker-compose.yml   # TimescaleDB / Redis / RabbitMQ / EMQX
└── .gitlab-ci.yml    # CI：test → build → deploy dev
```

## 本地启动

```bash
# 1. 启动基础设施（PG16+Timescale / Redis / RabbitMQ / EMQX）
cd deploy && docker compose up -d

# 2. 启动后端（Flyway 自动建表到 claw schema）
cd ../backend && mvn spring-boot:run

# 3. 冒烟验证
curl http://localhost:8080/api/v1/ping
# Swagger: http://localhost:8080/swagger-ui.html
# 健康检查: http://localhost:8080/actuator/health

# 4. Flutter APP / Web 后台（S1/S5 起开发）
cd ../app && flutter run
cd ../web && npm i && npm run dev
```

## 已落地决策（详见 PRD v1.1 决策记录 D1-D32）

- 计价锁版：光伏电 $0.12 / 市电 $0.18 / 换电服务费 $0.32 / 充电 $0.15（V1 快照已初始化）
- 三专户 escrow 受托口径（D27）、KHQR + ABA + Bakong 零牌照资金路径（D18）
- 复式记账 + 幂等分录（`account_entries` 唯一索引 biz_type+biz_ref+account+direction）
- DTI ≤50% 强制校验已预留错误码与三语文案（负责任信贷标杆）
- 人人经济角色包 9 个种子角色已初始化

## Sprint 计划（12 周 MVP）

- [x] **S0** 工程脚手架、CI/CD、DB 基础表、i18n 框架 ← 当前
- [ ] S1 用户/权限/CamDigiKey KYC、角色包引擎、资产域 CRUD + 状态机
- [ ] S2 复式记账引擎、三专户、押金流转；合规域初版（DTI 强制）
- [ ] S3 换电域：下单、押金双向流转、预扣结算；地图适配层
- [ ] S4 服务站 APP、ABA 托管对接（KHQR 收单 + Bakong 清算）
- [ ] S5 Web 后台、IoT 遥测/轨迹、Claw Score
- [ ] S6 三语校对、压测、渗透测试、上线演练

**MVP 验收**：真实环境跑通「充值 → 购车 → 换电押金流转 → 结算 → 提现」+ ABA 日终对账零差异。

## 工程约束

1. 表结构一律走 Flyway 迁移脚本，`ddl-auto: validate`；
2. 领域边界由 `ArchitectureBoundaryTest` 守护（ledger 封闭域，跨域走领域事件）；
3. 所有资金变动必须复式记账、借贷平衡；
4. 所有面向用户的错误文案必须走 i18n 三语，禁止硬编码。
