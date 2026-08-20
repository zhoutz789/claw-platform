# Claw 新能源资产全生命周期运营管理平台

> 依据《PRD v1.1（定稿）》《技术开发文档 v0.4》搭建。**全球化运营框架已内置**：以柬埔寨为首个试点(PILOT)，全球一家、互通有无——所有国家都是开放互通节点，无排除国。中国为全球商品供给方与商家网络枢纽(HUB)；牵扯两国贸易的物资转移按「各算各的」跨境结算模型处理（进口国关税/增值税、出口国退税，中间手续线上补充、法律合规分法域落实）。
> S0 状态：**工程脚手架已就绪（2026-08-20）**；S1 状态：**用户/权限/KYC + 角色包引擎 + 资产域已落地（2026-08-20）**；全球化框架：**法域主表 + 适配器注册表 + 国家清单接口已落地（2026-08-20）**；S2 状态：**复式记账引擎 + 三专户 + 押金流转 + 站点现货 + DTI 合规已落地（2026-08-20）**；S3 状态：**换电域（下单/押金双向流转/预扣结算/地图适配层）已落地（2026-08-20）**；S4 状态：**服务站 APP（扫码收发/电池位/日账单）+ ABA 托管对接（三专户 KHQR 收单 + Bakong 清算、充值/提现/T+1 对账）已落地（2026-08-20）**；S5 状态：**IoT 遥测/轨迹上报 + Claw Score 信用分 + 金融消费者投诉 + 数据大屏已落地（2026-08-20）**

## 仓库结构

```
claw-platform/
├── backend/          # Java 21 + Spring Boot 3.3 模块化单体
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/claw/server/
│       │   ├── ClawServerApplication.java
│       │   ├── common/api/        # 统一响应 ApiResult / BizException / 全局异常
│       │   ├── common/security/   # JWT 工具 / 过滤器 / 安全配置 / 当前用户上下文
│       │   ├── common/enums/      # 共享枚举（资产/角色/KYC 等，跨域共享内核）
│       │   ├── common/dto/        # 请求/响应 DTO（仅依赖 enums，不引 domain 实体）
│       │   ├── config/            # i18n 三语（en/km/zh）等配置
│       │   ├── domain/            # 领域分包（ArchUnit 守护边界）
│       │   │   ├── user/          #   用户/权限/KYC（CamDigiKey，S1）
│       │   │   ├── role/          #   人人经济角色包 + 三层权限（S1）
│       │   │   ├── asset/         #   资产域：车/电池/桩/电站 + 状态机（S1）
│       │   │   ├── jurisdiction/  #   全球化运营：国家主表 + 身份/支付/牌照适配器注册表 + 分润（共营框架）
│       │   │   ├── ledger/        #   账户域：复式记账/三专户（S2）
│       │   │   ├── station/       #   站点域：附近站点现货/投放（S2）
│       │   │   ├── deposit/       #   押金域：冻结/归还/扣收（S2）
│       │   │   ├── compliance/    #   合规域：DTI≤50% 强制（S2）
│       │   │   ├── swap/          #   换电域：订单状态机/计价/双向押金（S3）
│       │   │   ├── payment/       #   支付域：KHQR/ABA/Bakong（S4）
│       │   │   ├── iot/           #   IoT：遥测/轨迹/锁车（S5）
│       │   │   ├── notification/  #   通知域
│       │   │   └── report/        #   报表域：大屏/对账（S5）
│       │   ├── web/v1/            # REST 控制器（/api/v1）
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

## 已交付接口（/api/v1）

| 模块 | 接口 | 说明 |
|---|---|---|
| 认证 | POST /auth/sms-code | 发送验证码（dev 回显） |
| 认证 | POST /auth/login | 验证码登录 / 注册（签发 JWT） |
| 用户 | GET /users/me | 我的资料 |
| 用户 | POST /users/kyc | 平台实名（MANUAL） |
| 用户 | POST /users/kyc/camdigikey | CamDigiKey 国家数字身份 eKYC（OAuth2.0 接入点） |
| 用户 | GET /users/me/roles | 我的角色包列表 |
| 用户 | POST /users/me/roles/{code}/apply | 申请开通角色包 |
| 资产 | POST /assets/vehicle | 登记车辆（自动授予车主角色包 + MANAGE ACL） |
| 资产 | POST /assets/battery | 登记电池 |
| 资产 | GET /assets | 资产列表（按类型/状态过滤） |
| 资产 | GET /assets/{id} | 资产详情 |
| 资产 | PUT /assets/{id}/acl | 设置资产 ACL（MANAGE/USE/LEASE） |
| 资产 | POST /assets/{id}/status | 状态流转（状态机校验 + 审计） |
| 资产 | POST /assets/{id}/functions | 开通功能（子账户 S2 实现） |
| 法域 | GET /countries | 国家清单（?status=PILOT/ACTIVE/PLANNED，?nodeRole=OPERATOR/SUPPLIER/MARKET/HUB；全球一家，无排除国） |
| 法域 | GET /countries/{code} | 某国详情（含身份/支付/牌照适配器摘要） |
| 法域 | GET /jurisdictions/me | 当前法域完整摘要（X-Country-Code 决定，缺省 KHM） |
| 结算 | POST /settlements/cross-border | 跨境物资转移结算报价（各算各的：出口国退税 + 进口国关税/增值税） |
| 账户 | GET /ledger/accounts | 账户列表（?userId=&accountType=） |
| 账户 | GET /ledger/accounts/{id} | 账户详情（余额/冻结） |
| 账户 | POST /ledger/transactions | 复式记账（借贷平衡 + bizType/bizRef 幂等） |
| 账户 | GET /ledger/transactions/{txnId} | 某笔交易完整分录 |
| 账户 | GET /ledger/accounts/{id}/entries | 账户流水 |
| 站点 | GET /stations/nearby | 附近站点现货（?countryCode=&lat=&lng=&limit=3，Haversine 距离） |
| 站点 | GET /stations/search?sku= | 搜车型 → 附近有现货的站点 |
| 站点 | GET /stations/{id}/stock | 站内现货 |
| 站点 | POST /stations/{id}/stock | 投放现货（投资者认购入站） |
| 押金 | POST /deposits | 支付押金冻结（复式入账） |
| 押金 | POST /deposits/{no}/release | 归还押金 |
| 押金 | POST /deposits/{no}/forfeit | 违约扣收（转残值准备金专户） |
| 押金 | GET /deposits?userId= | 押金单列表 |
| 合规 | POST /compliance/dti-check | DTI≤50% 强制校验（PASS/REJECT 留痕） |
| 换电 | GET /swap-orders/quote | 计价预览（锁版：电费 0.12 + 服务费 0.32，按度） |
| 换电 | POST /swap-orders | 创建换电单（押金冻结 + 预扣，FIFO 派发满电电池） |
| 换电 | POST /swap-orders/{no}/confirm | 服务站确认双向流转（旧押金退还 + 管理权切换 + 电池位） |
| 换电 | POST /swap-orders/{no}/settle | 结算（按锁定快照实际用量，多退少补） |
| 换电 | POST /swap-orders/{no}/cancel | 取消（押金解冻 + 预扣退回） |
| 换电 | GET /swap-orders | 我的换电记录 |
| 换电 | GET /swap-orders/{no}/events | 订单事件流 |
| 站点 | GET /stations/map | 地图适配层：附近换电站（距离/满电电池数/充电中） |

## 全球化运营框架（共营架构）

核心业务域（user/asset/ledger/order…）保持**法域无关**；合规按国家以「注册表 + 适配器」插件化挂载。
每进入一个国家，只需在 `countries` + `identity_providers` + `payment_providers` + `regulatory_licenses`
+ `tenants` + `partner_programs` 插入数据，**不动核心代码**。请求经 `X-Country-Code` 头落入对应国家上下文（缺省 KHM）。

**全球网络节点（seed 数据已写入 V3/V4 迁移）**

全球一家、互通有无：每个国家政策不同，但运作模式基本相同。所有国家都是开放互通节点，无排除国。

| 角色 node_role | 分区 | 代表国家 | 说明 |
|---|---|---|---|
| 试点 OPERATOR | 柬埔寨 KHM | **PILOT（运营中）** | 平台自营落地：资产/换电/分期/人人经济 |
| 运营 OPERATOR | 东南亚/西亚/非洲/东欧 | 越南/印尼/泰国…俄罗斯/乌克兰…尼日利亚/肯尼亚… | PLANNED（规划自营落地） |
| 枢纽 HUB | 东亚 EAST_ASIA | **中国 CHN** | 全球商品供给方 + 服务全球商家网络枢纽 |
| 市场 MARKET | 北美/欧洲/东亚/大洋洲/西亚 | 美/英/德/法/日/韩/澳/加/新/海湾 | 消费与商家网络节点，全球互通 |

> 跨境结算（V4 新增）：牵扯两国贸易的物资转移，出口国按自身退税/单证计算、进口国按自身关税/增值税/单证计算，**各算各的**；中间手续（报关/发票/许可证等）由平台线上补充采集，法律合规分法域落实。各国规则存于 `countries.trade_policy_json`，进一国只维护该 JSON，结算引擎不变。

## 已落地决策（详见 PRD v1.1 决策记录 D1-D32）

- 计价锁版：光伏电 $0.12 / 市电 $0.18 / 换电服务费 $0.32 / 充电 $0.15（V1 快照已初始化）
- 三专户 escrow 受托口径（D27）、KHQR + ABA + Bakong 零牌照资金路径（D18）
- 复式记账 + 幂等分录（`account_entries` 唯一索引 biz_type+biz_ref+account+direction）
- DTI ≤50% 强制校验已预留错误码与三语文案（负责任信贷标杆）
- 人人经济角色包 9 个种子角色已初始化

## Sprint 计划（12 周 MVP）

- [x] **S0** 工程脚手架、CI/CD、DB 基础表、i18n 框架
- [x] **S1** 用户/权限/KYC（含 CamDigiKey eKYC 接入点）、人人经济角色包引擎（授予规则 + 三层权限）、资产域 CRUD + 状态机 + ACL
- [x] **全球化框架** 法域主表 + 身份/支付/牌照适配器注册表 + 运营主体 + 分润雏形 + 国家清单接口（柬埔寨 PILOT，V3 迁移）
- [x] **S2 账户域** 复式记账引擎、三专户、押金流转、站点现货（客户附近三站选购）、DTI 合规强制（V5 迁移）
- [x] **S3 换电域** 下单（押金冻结+预扣）、双向押金流转、预扣结算（多退少补）、地图适配层（V6 迁移）
- [ ] S4 服务站 APP、ABA 托管对接（KHQR 收单 + Bakong 清算）
- [ ] S5 Web 后台、IoT 遥测/轨迹、Claw Score
- [ ] S6 三语校对、压测、渗透测试、上线演练

**MVP 验收**：真实环境跑通「充值 → 购车 → 换电押金流转 → 结算 → 提现」+ ABA 日终对账零差异。

## 工程约束

1. 表结构一律走 Flyway 迁移脚本，`ddl-auto: validate`；
2. 领域边界由 `ArchitectureBoundaryTest` 守护（ledger 封闭域，跨域走领域事件）；
3. 所有资金变动必须复式记账、借贷平衡；
4. 所有面向用户的错误文案必须走 i18n 三语，禁止硬编码。
