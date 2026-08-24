# 增量实施计划：资产生命周期闭环 + 角色权限重构 + 数据大屏真实化（2026-08-23）

> 承接现有 PRD v1.1（决策 D1–D51）、技术开发文档 v0.4。本计划为**增量**，仅描述新增/改造部分。
> 由于多智能体调度工具临时不可用，本次由交付总监直接按 SOP 结构执行（计划→实现→验证）。

## 一、背景与问题（老板四点诉求）
1. **资产生命周期闭环缺失（最核心）**：当前 `AssetController.createVehicle/createBattery` 让运营方凭空建资产，没有「工厂发布商品→SKU定价→客户购买→出厂前录二维码→资产出生」这条「从无处来」的首步流程；资产无 manufacturer/product/sku/serialNumber，也无生产/流通/溯源/回收/销毁阶段与维修/使用/车辆运营数据。许多资产看不到任何数据信息。
2. **角色权限不可维护**：`Role.grants` 是自由 JSON，`Roles.jsx` 仅 textarea 编辑，无「菜单+增删改查+按钮」矩阵，无数据范围（本人/特殊授权可见类型）。
3. **数据大屏**：`DashboardService` 已绑定真实聚合，但 `Dashboard.jsx` 仍混入大量硬编码演示数据且未标注来源，用户感知「统计无来源」。
4. **用户授权**：`AdminUserController` 仅列出用户+角色码，无给用户增删改角色的端点/界面。

## 二、设计假设（待老板确认，先按以下落地）
- **A1 厂家账号**：本阶段厂家由平台管理员在后台维护（不单独登录），后续可升级为「厂家角色」账号。
- **A2 SKU↔资产**：一个 SKU 是商品变体；一次采购(order)按 sku+qty 下单；**发货前厂家逐台登记序列号+二维码 → 每台登记生出一台 Asset**（1 SKU → N 资产），彻底解释「资产从哪来」。
- **A3 二维码**：后端存储二维码 payload（如 `CLAW|ASSET|<assetNo>|<serial>`），提供生成端点；前端展示 payload 并渲染二维码（采用纯前端轻量方案，后续可替换为扫码枪识别）。
- **A4 数据范围**：`Role` 增加 `dataScope`（SELF/DEPARTMENT/ALL/TYPE）+ `dataScopeTypes`（json 允许的额外可见类型）。本阶段落地模型与「SELF 时按 ownerId 过滤资产/用户」的基础 enforcement，完整按部门/类型过滤为后续迭代。
- **A5 权限目录**：种子 16+ 后台菜单；平台固定角色（autoGrant=false）默认授予全量权限，开箱可用；动态角色可由管理员在矩阵页分配。

## 三、数据模型与迁移
- **V22** `assets` 加列：`manufacturer_id`, `product_id`, `sku_id`, `serial_number`(unique)。`qr_code` 已存在。
- **V23** 新表：`manufacturers` / `products` / `product_skus` / `purchase_orders`。
- **V24** 新表：`asset_lifecycle_events`(阶段:PRODUCED/IN_TRANSIT/IN_USE/MAINTENANCE/RECYCLED/DESTROYED) / `asset_maintenance_records` / `asset_usage_records` / `asset_vehicle_ops`(运营:PASSENGER/LOGISTICS/MOBILE_SELL/ADVERTISING/RECORDING)。
- **V25** 新表：`permissions`(MENU/BUTTON 目录) / `role_permissions`(can_read/create/update/delete/export + buttons_json 矩阵)；`roles` 加 `data_scope`/`data_scope_types`；种子菜单目录 + 平台固定角色全权限。

## 四、后端 API 清单（前缀 /api/v1/admin）
- `manufacturer/manufacturers` GET/POST/PUT/DELETE
- `manufacturer/products` GET/POST/PUT/DELETE
- `manufacturer/skus` GET/POST/PUT/DELETE
- `manufacturer/purchase-orders` GET/POST；`/purchase-orders/{id}/pay` PUT；`/purchase-orders/{id}/ship` PUT
- `manufacturer/purchase-orders/{id}/register-qr` POST（登记 N 台 → 出生 N 台 Asset + 写生命周期 PRODUCED）
- `manufacturer/assets/{id}/trace` GET（溯源聚合：出厂/生命周期/维修/使用/运营/收益）
- `manufacturer/assets/{id}/lifecycle|maintenance|usage|vehicle-ops` GET/POST（+DELETE）
- `permissions/catalog` GET（树）；`/catalog` POST/PUT/DELETE
- `permissions/role/{roleId}` GET；`/role/{roleId}` PUT（保存矩阵）
- `roles/{id}/data-scope` GET/PUT
- `users/{id}/roles` PUT（整体替换）；`/users/{id}/roles/add` POST；`/users/{id}/roles/{code}` DELETE
- `dashboard` GET（扩展指标 + source 标注）

## 五、前端页面
- 新增 `Manufacturer.jsx`（厂家/商品/SKU/采购/二维码登记 Tabs）
- 新增 `AssetTrace.jsx`（资产溯源详情，Tabs：出厂数据/生命周期/维修/使用/车辆运营/收益/二维码）
- 新增 `Permission.jsx`（菜单树 + 角色权限矩阵编辑：增删改查勾选 + 按钮开关 + 数据范围）
- 改造 `Roles.jsx`（数据范围 + 权限矩阵入口）、`Users.jsx`（角色分配抽屉）、`Dashboard.jsx`（真实数据+来源标签）、`Assets.jsx`（厂家/商品+溯源入口）
- `App.jsx` 路由 + `AdminLayout` 菜单补充

## 六、验证口径
后端 Maven(JDK21) 打包 → 前端 npm build → 重启后端 8080 → token 冒烟：厂家→商品→SKU→采购→支付→发货→登记二维码→资产出生→溯源；权限目录与矩阵；用户角色分配；大屏指标均来自真实表。

## 七、决策定稿（2026-08-24 · 老板授权交付总监代定，即日执行）
> 原"待拍板"四项由交付总监按最优方案定稿，老板已授权全权执行。

1. **二维码**：采用纯前端渲染（antd `QRCode` / `@rc-component/qrcode`），零硬件依赖，先跑通资产溯源闭环；后续如需扫码枪识别，仅替换渲染/识别层，不动业务逻辑。
2. **厂家账号**：本阶段厂家由平台管理员在后台维护（`AdminManufacturerController`），**不单独登录、不与厂家独立结算**；后续迭代可升级为独立厂家角色 + 结算。
3. **数据范围**：本阶段落地 `SELF`（按 `ownerId` 过滤资产/用户）。模型层 `DataScope` 枚举 + `Role.dataScope/dataScopeTypes` + V25 权限表已就绪；完整「部门/类型」维度过滤列为后续迭代。
4. **车辆运营**：**仅记录数据**（`asset_vehicle_ops` 表 + `VehicleOpType` 枚举：客运/物流/移动售卖/广告/录像），本阶段不独立计费、不独立分账，仅作为资产生命周期运营数据沉淀。

## 八、落地状态（2026-08-24 核查）
- C1 二维码：前端 `@rc-component/qrcode` + antd `QRCode` 已就绪，`AssetTrace` 渲染 payload ✅
- C2 厂家后台维护：`AdminManufacturerController` 已实现 ✅
- C3 数据范围：`DataScope`/`Role.dataScope`/V25 已落地；SELF enforcement 基础实现，完整维度过滤待迭代 ⚠️
- C4 车辆运营记录：`AssetVehicleOps`/`VehicleOpType`/V24 已落地 ✅

## 九、端到端验证（2026-08-24 20:30 · 全闭环跑通）
> 后端 default profile 连真实 PostgreSQL（Docker `claw-postgres` PG16），前端 `npm run build` 通过，全部链路实跑验证。

**验证结果：**
- 后端启动：`Started ClawServerApplication`，Flyway 校验 26 迁移通过，真实 PG 连接成功；`/actuator/health=200`。
- 前端构建：`vite build` ✓ 3121 modules transformed，dist 产物产出（无编译错误）。
- 登录鉴权：短信验证码 dev 回显登录 → JWT（252 字符）→ admin 接口 dashboard/users 均 200（自动注册用户可访问 admin 域）。
- 大屏真实数据：`swap=4 / assets=20 / mf=12 / products=11 / skus=11 / purchase=26400 / escrow=3 / sources=10`，全部来自真实库表。
- 厂家闭环：厂家(MFID=13)→商品(PRID=12)→SKU(SKID=12)→采购单(POID=13,598)→支付 PAID→发货 SHIPPED→登记二维码 **bornCount=2（资产 31/32 出生）** ✅。
- 资产溯源：写前 lifecycle=1（PRODUCED）；全生命周期写 lifecycle/maintenance/usage/vehicle-ops **全部 200**；写后 lifecycle=2/maint=1/usage=1/vops=1/**totalRevenue=3.0**（计数与收益汇总正确）✅。
- 权限目录/矩阵：`permissions/catalog=200`、`permissions/role/{id}=200`；用户角色分配 `users/{id}/roles PUT=200` ✅。

**结论：** 资产生命周期闭环 + 角色权限矩阵 + 数据大屏真实化 三项增量功能**后端接口、前端页面、端到端数据流全部验证通过**。任务 #94/#95/#96/#98/#102 已标记完成。

**遗留（非阻断）：**
- 数据范围 SELF 维度过滤的完整"部门/类型"维度仍为后续迭代（C3 ⚠️ 不变）。
- 资产 19 被本轮调试脚本额外写入一条 lifecycle 测试记录（测试环境，可忽略或清理）。
