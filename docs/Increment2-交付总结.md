# Increment 2 客户订单闭环后端 — 交付总结

> 按软件开发团队 SOP 交付：架构师设计 → 工程师实现 → QA 独立验证。
> 日期：2026-08-27｜状态：实现完成 + QA 通过（**未 commit**，V34 待真实 PG 校验）

## TL;DR

爪平台 Increment 2 已落地：**客户订单全生命周期闭环**（创建→支付→选模式→发货→完成）+ 合格证出具 + 共享池自动入池 + 阶梯押金(R4) + 成交自动分账。编译 0 错，11 个测试全过，**无源码 bug**。唯一必须处理项：V34 迁移尚未在真实 PostgreSQL 上跑过 Flyway（项目历史有 V3/V9/V10/V11 缺陷），校验通过后再 commit。

## 交付概览

| 项 | 结果 |
| --- | --- |
| 编译（JDK21 `mvn -o compile`） | SUCCESS，0 错 |
| 测试（`ArchitectureBoundaryTest` + `CustomerOrderClosureTest`） | 11 用例全过，0 失败 |
| 源码 bug | 0（QA 独立代码审查结论） |
| 设计文档 | `docs/incremental-design-order-closure.md` + 两张 mermaid 图 |
| 提交状态 | **未 commit**（V34 真实 PG 校验缺口） |

## 文件清单（新增 / 变更，均位于 `backend/src/main/java/com/claw/server/`）

**基础设施**
- `db/migration/V34__customer_order_closure.sql`
- `common/enums/OrderStatus.java`、`UsageMode.java`、`CertificateType.java`
- `common/dto/OrderDtos.java`

**订单域**
- `domain/order/CustomerOrder.java`、`CustomerOrderItem.java`
- `domain/order/CustomerOrderRepository.java`、`CustomerOrderItemRepository.java`
- `domain/order/CustomerOrderService.java`（状态机 + 建产权 + 冻押金 + 发事件）
- `domain/order/Certificate.java`、`CertificateRepository.java`、`OrderCertificateService.java`
- `domain/order/event/OrderPaidEvent.java`、`OrderCompletedEvent.java`、`OrderSharedPoolIntegration.java`、`OrderSettlementIntegration.java`

**押金规则**
- `domain/deposit/DepositRule.java`、`DepositRuleRepository.java`、`DepositRuleService.java`、`StationDepositService.java`

**共享池接线（扩展既有服务）**
- `domain/sharedpool/SharedPoolService.java`：新增 `establishOwnership` / `createSplitRule` / `createSettlement`

**控制器**
- `web/v1/AdminCustomerOrderController.java`（命名空间 `/api/v1/admin/customer-orders`）

**测试**
- `test/java/com/claw/server/domain/order/CustomerOrderClosureTest.java`（9 用例，含 edge/错误路径）

## 对设计文档的偏离（有意，已纠正文档与代码的 5 处不符）

1. `certificates` 表从未被任何迁移创建（product-link V27 实际是 `fix_final_price_median`）→ V34 用 **CREATE TABLE** 含 `order_id`，并去掉对不存在的 `product_links` 外键。
2. `Certificate` 实体/仓储/服务改落 `domain/order` 自建，使订单闭环自洽（不再依赖未落地的 product-link 增量）。
3. 产权类型用 `OwnershipType.FULL`（文档误写 `FULL_PURCHASE`）。
4. `cert_no` 在 `OrderCertificateService` 内联生成 `CERT-{Q|C}-{yyyyMM}-{6位随机}`（带查重），项目无 `LinkTokenUtil`。
5. `choose-mode(SHARED)` 重发 `OrderPaidEvent`，监听器仅在 SHARED 且未入池时入池（幂等），保证文档 happy-path 接通。

## 用户下一步建议（3–5 条）

1. **必做**：用本地 Docker PostgreSQL 启动 claw-server（或跑 `SchemaMigrationIT` Testcontainers）校验 V34 干净应用，确认无 Flyway 报错后再 commit —— 这是项目历史上吃过亏的环节。
2. 固定 JDK21：默认 `JAVA_HOME` 若指向 JDK8，`mvn test` 会直接失败（测试编成 Java21 但 Surefire fork JDK8）。CI/本地须固定 JDK21。
3. （可选）给 `CustomerOrder` / `CustomerOrderItem` 金额字段补 `@Column(precision=18, scale=4)`，与 V34 DDL 对齐（当前 `ddl-auto=none` 无害）。
4. （可选）补 `poolAsset` 真实路径的集成测试，覆盖查产权 / 建池记录。
5. 校验通过后，commit 到本地分支（暂不推送，GitHub 当前 disconnected）。
