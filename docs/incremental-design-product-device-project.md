# 增量设计文档：产品 / 设备 / 项目 / 商品 / 订单 改造（Increment 3）

> **文档性质**：增量设计（仅描述相对已落地能力的**新增与变更**），承接 `incremental-design-product-link.md`（V26–V29）与 `incremental-design-order-closure.md`（V34）。
> **编写**：高见远（架构师）｜**日期**：2026-08-27｜**状态**：草案，待主理人齐活林汇总
> **技术栈**：沿用现有 —— Spring Boot 3.3.4 / Java 21 / Spring Data JPA / Flyway / PostgreSQL / JJWT / Redis / RabbitMQ / Lombok。前端 Vite + React + Ant Design。
> **新增迁移**：**V35 起**（接续 V34）。**无新增第三方依赖**。
> **风格模板**：模仿 `docs/incremental-design-product-link.md`。

---

## 0. 现状调查结论（先读，避免重复造轮子）

### 0.1 已存在、可直接复用的能力（务必复用，不重造）

| 域 / 文件 | 关键可复用点 | 本期缺口 |
| --- | --- | --- |
| `products` 表（V33 已加 brand/category/params_json/cover_images_json/detail/video_url/live_enabled/live_url/share_code/reward_rate） | **即"产品表"本体**（点 1 的"系统所有产品"；点 6 的"商品"）。`Product.java` 已含全部富元数据字段 | 缺完整 CRUD 端点 + RBAC（当前仅厂家后台读写） |
| `domain/manufacturer/Product.java`、`ProductRepository.java`、`ProductSku.java` | 产品 / SKU 实体与仓储 | 缺 admin 级产品 CRUD 控制器 |
| `domain/iot`：`tracks`（V8，deviceId/assetId/ts/lat/lng/speed/soc）、`telemetry_latest`（每设备最新）、`telemetry`（V29，每资产最新 soh/lat/lng/speed） | **GPS 历史 / 实时定位全部已落地**（点 3 的"位置相关表"） | 缺"产品聚合位置"派生视图 + 电子围栏表 |
| `domain/iot/airspace_zones`（V29：center_lat/center_lng/radius_m/level） | **电子围栏几何雏形**（无人机专用） | 缺通用 `geofences`（产品/设备/项目级、触发动作） |
| `domain/iot/DroneSafetyService` + `drone_safety_events`（V30：asset_id/cause/status OPEN=LOCKED） | **阈值锁机 / 禁通电模式**（点 7"接近阈值自动熄火、不通电"的直接类比） | 缺车辆侧复用（drone 专属命名，需泛化或平行建车辆安全域） |
| `domain/order`（V34）：`CustomerOrder`/`CustomerOrderItem` + 状态机 + `OrderPaidEvent`/`OrderCompletedEvent` 事件接线 | **订单全生命周期骨架**（点 7 主链路） | 缺主部件编号字段（VIN/电机号/二维码）+ 阈值熄火接线 |
| `domain/sharedpool`：`SharedPoolService.poolAsset` / `AssetOwnership` | **共享入池 / 产权转移语义**（点 5 共享池 / 资产大厅） | 缺"使用权授权"记录（转让/共享/授权三态并存） |
| `domain/ledger`：`Account`/`AccountEntry`/`LedgerService`（双记账） | **每项目单独核算**（点 5 ProjectLedger） | 缺 project→account 关联 |
| `assets` 表：含 `product_id`（Asset.java:39）、`owner_id`（:43） | **"产品下所有设备"天然成立**（`WHERE product_id=?`）；设备归属人已知 | — |
| `web/src/nav.js`：`device-twin` 与 `device-detail` 并列；`DeviceTwin.jsx` 仅 1 行 `export default DeviceDetail` | **点 4 证据**：孪生详情=设备详情同一页，当前是零代码重复 re-export | 删 `device-twin` 菜单项 + 产品页内联设备列表 |
| `web/src/pages/ProductPublish.jsx` | 京东式直填发布已落库（点 6 反面教材，需改向导式） | 缺向导式组件 + 商品列表子页 |
| `brand_onboarding` / `menu-permission` / `roles` | 品牌商业组织、菜单权限、角色体系 | 点 1/5/6 的权限与品牌归属直接复用 |

### 0.2 硬约束（必须遵守，影响所有取舍）

1. **Flyway 全权管 schema、`ddl-auto=none`，禁止运行时动态建表** → 直接否决点 2"每个模版单独建表"。
2. **ArchUnit**：`common` 不得依赖 `domain`；跨域协作走**服务接口 / 领域事件**（沿用 `OrderSharedPoolIntegration` 模式）。
3. **金额** `NUMERIC(18,4)`、**时间** `TIMESTAMPTZ`(UTC)。
4. **字段扩展沿用 `data_json` / `params_json`**（certificates 已验证），而非加列。

---

## 1. 实现方案与框架选型

### 1.1 核心难点与应对

| # | 难点 | 方案 | 理由 |
| --- | --- | --- | --- |
| 1 | 产品统一 CRUD + 三级权限（管理员建 / 商家调 / 用户看） | 复用 `products` + 新建 `AdminProductController`（CRUD）+ RBAC 走既有 `roles`/`menu-permission` | "产品表"已存在，只补端点与权限，不新建表 |
| 2 | 模版字段可扩展 + 绑定设备数据（**反模式**) | **拒绝**动态建表；改用 `product_template_fields`（EAV 单表）+ `products.params_json`/`attr_json` 存值 | 遵守 Flyway 纪律 + JPA 静态映射；详见 §1.3 关键取舍① |
| 3 | 实时位置 + 历史轨迹 + 电子围栏 | **复用** `tracks`+`telemetry_latest`；新建 `geofences`（通用围栏，复用 airspace_zones 几何） | 不新建平行定位表；围栏仅新增 1 表，几何模式照搬 V29 |
| 4 | 菜单整合 + 产品中心导航 | 删 `device-twin` 菜单；产品页内嵌设备列表 + 抽屉打开 `DeviceDetail` | `DeviceTwin.jsx` 已 re-export，零重复；UX 改为"点设备弹窗" |
| 5 | 项目管理新域（转让/共享/授权/核算） | 新建 `domain/project`：`projects`(自引用树)+`project_devices`+`device_authorizations`；核算复用 `ledger` | 全新域，但全部借力既有 sharedpool/ledger/ownership 语义 |
| 6 | 商品列表 + 引导式发布 | 新增 `goods-list` 子菜单 + `ProductWizard.jsx` 向导；后端复用 `products`/`ProductSku` | 发布逻辑已存在，仅改"交互形态"为分步向导 |
| 7 | 订单全生命周期 + 阈值熄火 | 复用 `CustomerOrder` 状态机 + 加主部件编号字段；熄火**复用 DroneSafetyService 锁机模式**（泛化或平行建车辆安全域） | 不重造状态机与锁机；仅补字段 + 接线 |

### 1.2 架构模式

- **分层**：沿用 `domain / web.v1 / common.dto / common.enums / common.security`。
- **跨域接线**：project 域 → sharedpool/ledger/asset **只走服务接口 / 领域事件**（遵守 ArchUnit，参照 `OrderSharedPoolIntegration`）。
- **字段扩展**：所有"可扩展属性"走 JSON（`params_json` / `attr_json` / `data_json`），模板演进不改表。

### 1.3 关键设计取舍（对原话的偏离，请主理人知悉）

#### ①【重点】点 2"每个产品模版单独建表" → **明确反对，改用 EAV 单表**

**反对理由（3 条硬伤）**：
1. **破坏 Flyway 纪律**：项目硬约束 `ddl-auto=none` + 禁止运行时建表，动态 `CREATE TABLE` 无法经 Flyway 管理、无法回滚、破坏迁移可审计性。
2. **JPA 无法静态映射**：每模版一张表 = 表名运行时才确定，Repository/Entity 无法在编译期绑定，强类型 ORM 失效，只能退化成原生 SQL，丧失 JPA 全部红利。
3. **与点 1 自相矛盾**：点 1 已要求"单独建**产品表**统一记录系统所有产品"，若再"每模版一表"则产品被拆散，统一产品表失去意义；且 N 个模版 = N 张表，查询/权限/菜单全部无法收敛。

**推荐替代方案（满足"字段可扩展 + 绑定设备数据"，零动态建表）**：
- 保留 `products` 作为**统一产品表**（点 1 的"产品表"）。
- 新增 **`product_template_fields`（EAV 单表）**：`product_id` + `field_key` + `label` + `type`(number/text/select/date/boolean) + `unit` + `options_json` + `required` + `sort_no`。这就是"可扩展字段 schema"。
- 产品实例的扩展属性值存 **`products.params_json`**（已存在）或新增 `products.attr_json`（TEXT）。
- "绑定设备数据"：字段 `field_key` 直接映射到设备遥测键（`telemetry_latest`/`tracks` 的列或 JSON 键），前端按 field_key 渲染设备实时值——**无需为模版建表即可完成"模版字段 ↔ 设备数据"绑定**。

> 若未来确需"模版"与"实例"分离（如一个模版派生多 SKU 实例），再加 `product_templates` 表（模版定义）即可，**仍不动态建表**。本期推荐最简形态：`products` + `product_template_fields` EAV。

#### ② 点 3 定位/围栏：复用 `tracks` + 通用 `geofences`
- 设备历史定位/轨迹 = `tracks`（已有）；实时 = `telemetry_latest`（已有）；轨迹回放 = 按时间查 `tracks` + `TrackSimplifier`（product-link 增量已提供）。
- **产品位置** = 该产品下代表设备（或最新上报设备）的 `telemetry_latest` 聚合/最新点，**派生视图，不存冗余表**。
- **上报间隔**：存 `products.report_interval_seconds`（每产品时序要求不同），设备级可选覆盖（后续 `device_config`，本期先产品级）。
- **电子围栏**：新建 `geofences`（owner_type∈{PRODUCT,DEVICE,ASSET,PROJECT} + owner_id + fence_type∈{RADIUS,POLYGON} + center/radius 或 polygon_wkt + trigger_action∈{ENTER,EXIT,INTRUSION→ALERT/LOCK}），几何模式**照搬 V29 `airspace_zones`**，但去无人机专用化、支持全实体。

#### ③ 点 5 项目管理：全新域，但语义全借力既有
- 转让 → **资产大厅**：复用 `AssetOwnership` 转移语义（或 `BindOwnership` 流程），资产置公开可见（资产大厅=公开资产视图，复用 `assets` + 状态机）。
- 共享 → **共享池**：直接调既有 `SharedPoolService.poolAsset(assetId,owner,station,...)`.
- 授权 = **使用权转移**：新增 `device_authorizations` 记录（不转移所有权，仅授予使用/定位/收益等 scope）。
- **三按钮可同时启用**：一个设备可同时被转让（所有权）+ 共享（入池）+ 授权（使用权）——三者正交，分别落不同表，自动归类产品资源。
- **每项目单独核算**：复用 `ledger` 双记账——每个 `projects` 关联一个 `accounts`(account_type=PROJECT)，收支走 `AccountEntry` 打 `project_id` 标签。

#### ④ 点 7 自动熄火 / 不通电：复用 V30 锁机模式
- `DroneSafetyService` 的"任一条 OPEN 安全事件 → 资产 LOCKED 禁通电；resolve → 恢复"是现成范式。
- 本期**泛化** `DroneSafetyService` → 保留 drone 行为，新增 `vehicle_safety_events`（asset_id + cause∈{THRESHOLD_BATTERY,USAGE_TIME,DISTANCE,MANUAL} + status OPEN/RESOLVED），阈值配置存 `customer_orders.threshold_config_json` 或 `products`。车辆侧 `VehicleSafetyService` 复用同一 lock/unlock 接口（或统一为 `AssetSafetyService`）。**不重造锁机逻辑**。

#### ⑤ 点 4 菜单：删冗余 + 产品中心内联设备
- 删 NAV `device-twin`（与 `device-detail` 同一页，已零重复）；保留 `device-detail`（改名"设备中心"）。
- 产品中心页（改造 `ProductTemplate.jsx` 或新建 `ProductCenter.jsx`）通过 `GET /v1/products/{id}` 返回 `devices` 列表；点击设备 → **抽屉/弹窗内联打开 `DeviceDetail`**，去除右上角 Select 强依赖（DeviceDetail 改为接受 `deviceId` 入参即可）。

---

## 2. 新增 / 变更实体与文件列表（相对路径，后端均位于 `backend/src/main/java/com/claw/server/`，前端 `web/src/`）

### 2.1 数据模型（实体 + 仓库）

| 实体（新增） | 路径 | 说明 |
| --- | --- | --- |
| `ProductTemplateField` | `domain/manufacturer/ProductTemplateField.java` | 模版字段 EAV（点 2 替代方案） |
| `ProductTemplateFieldRepository` | `domain/manufacturer/ProductTemplateFieldRepository.java` | |
| `Geofence` | `domain/iot/Geofence.java` | 通用电子围栏（点 3） |
| `GeofenceRepository` | `domain/iot/GeofenceRepository.java` | |
| `Project` | `domain/project/Project.java` | 项目（自引用树 + 排序） |
| `ProjectRepository` | `domain/project/ProjectRepository.java` | |
| `ProjectDevice` | `domain/project/ProjectDevice.java` | 项目-设备绑定（自动带 product_id） |
| `ProjectDeviceRepository` | `domain/project/ProjectDeviceRepository.java` | |
| `DeviceAuthorization` | `domain/project/DeviceAuthorization.java` | 转让/共享/授权记录 |
| `DeviceAuthorizationRepository` | `domain/project/DeviceAuthorizationRepository.java` | |
| `VehicleSafetyEvent` | `domain/iot/VehicleSafetyEvent.java` | 车辆阈值锁机事件（点 7，类比 drone_safety_events） |
| `VehicleSafetyEventRepository` | `domain/iot/VehicleSafetyEventRepository.java` | |

| 实体（变更） | 路径 | 变更 |
| --- | --- | --- |
| `Product` | `domain/manufacturer/Product.java` | 加 `reportIntervalSeconds`、`attrJson`（点 2/3） |
| `CustomerOrder` | `domain/order/CustomerOrder.java` | 加 `uniqueNo`/`qrPayload`/`vin`/`motorNo`/`frameNo`/`thresholdConfigJson`（点 7） |
| `DroneSafetyService` | `domain/iot/DroneSafetyService.java` | 泛化或平行新增 `VehicleSafetyService`（点 7） |

### 2.2 服务 / DTO / 控制器 / 前端

| 文件 | 路径 | 说明 |
| --- | --- | --- |
| `AdminProductController`（新） | `web/v1/AdminProductController.java` | 产品 CRUD + 按 id 取设备列表/共同属性（点 1/4） |
| `ProductTemplateFieldService` | `domain/manufacturer/ProductTemplateFieldService.java` | 字段 schema 增删改（点 2） |
| `GeofenceService` | `domain/iot/GeofenceService.java` | 围栏 CRUD + 越界判定（点 3） |
| `LocationViewService` | `domain/iot/LocationViewService.java` | 产品/设备聚合位置 + 轨迹回放（复用 tracks） |
| `ProjectService` | `domain/project/ProjectService.java` | 项目/子项目/设备 CRUD + 转让/共享/授权编排 |
| `VehicleSafetyService` | `domain/iot/VehicleSafetyService.java` | 阈值锁机（复用 DroneSafetyService 模式） |
| `ProductDtos` / `ProjectDtos` / `GeofenceDtos` | `common/dto/` | 请求与视图 |
| `ProductCenter.jsx`（新） | `web/src/pages/ProductCenter.jsx` | 产品中心（设备列表 + 点设备弹窗 DeviceDetail，点 4/1） |
| `GoodsList.jsx`（新） | `web/src/pages/GoodsList.jsx` | 商品列表子菜单（点 6） |
| `ProductWizard.jsx`（新） | `web/src/pages/ProductWizard.jsx` | 引导式发布向导（点 6） |
| `DeviceDetail.jsx`（改） | `web/src/pages/DeviceDetail.jsx` | 接受 deviceId 入参，可被抽屉内联 |
| `nav.js`（改） | `web/src/nav.js` | 删 `device-twin`；商品管理加 `goods-list` |

---

## 3. 数据结构与接口（迁移 V35 起 + 类图）

### 3.1 迁移清单（接续 V34）

- **V35** `product_template_fields_and_geofence.sql`：`product_template_fields`；`products` 加 `report_interval_seconds`/`attr_json`；`geofences`。
- **V36** `project_domain.sql`：`projects`/`project_devices`/`device_authorizations`；`projects.account_id` → `accounts(id)`。
- **V37** `order_lifecycle_and_vehicle_safety.sql`：`customer_orders` 加主部件编号列 + `threshold_config_json`；`vehicle_safety_events`。

### 3.2 V35 关键表

```sql
-- 模版字段 EAV（替代"每模版单独建表"）
CREATE TABLE claw.product_template_fields (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  product_id  BIGINT NOT NULL REFERENCES claw.products(id),
  field_key   VARCHAR(64) NOT NULL,
  label       VARCHAR(120) NOT NULL,
  type        VARCHAR(16) NOT NULL,   -- number/text/select/date/boolean
  unit        VARCHAR(16),
  options_json TEXT,                   -- select 选项
  required    BOOLEAN NOT NULL DEFAULT false,
  sort_no     INT DEFAULT 0,
  tenant_id   BIGINT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (product_id, field_key)
);

-- 产品表扩展（点 2 值 + 点 3 上报间隔）
ALTER TABLE claw.products
  ADD COLUMN report_interval_seconds INT,    -- 每产品时序要求不同
  ADD COLUMN attr_json TEXT;                 -- 扩展属性值（或复用 params_json）

-- 通用电子围栏（几何照搬 V29 airspace_zones，去无人机专用）
CREATE TABLE claw.geofences (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  owner_type    VARCHAR(16) NOT NULL,        -- PRODUCT/DEVICE/ASSET/PROJECT
  owner_id      BIGINT NOT NULL,
  fence_type    VARCHAR(16) NOT NULL,        -- RADIUS/POLYGON
  center_lat    NUMERIC(10,7),
  center_lng    NUMERIC(10,7),
  radius_m      INT,
  polygon_wkt   TEXT,                         -- POLYGON 类型填
  trigger_action VARCHAR(16) NOT NULL DEFAULT 'ALERT', -- ENTER/EXIT/INTRUSION→ALERT/LOCK
  status        VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  tenant_id     BIGINT NOT NULL DEFAULT 1,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_geofence_owner ON claw.geofences(owner_type, owner_id);
```

### 3.3 V36 项目管理域

```sql
CREATE TABLE claw.projects (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  name        VARCHAR(120) NOT NULL,
  parent_id   BIGINT REFERENCES claw.projects(id),   -- 子项目树
  depth       INT NOT NULL DEFAULT 0,
  sort_no     INT DEFAULT 0,                          -- 自由上下排序
  account_id  BIGINT REFERENCES claw.accounts(id),    -- 复用 ledger 双记账
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  tenant_id   BIGINT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_project_owner ON claw.projects(owner_user_id);
CREATE INDEX idx_project_parent ON claw.projects(parent_id);

CREATE TABLE claw.project_devices (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  BIGINT NOT NULL REFERENCES claw.projects(id),
  asset_id    BIGINT NOT NULL REFERENCES claw.assets(id),
  product_id  BIGINT REFERENCES claw.products(id),   -- 自动=assets.product_id
  category    VARCHAR(16),
  sort_no     INT DEFAULT 0,
  tenant_id   BIGINT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, asset_id)
);

CREATE TABLE claw.device_authorizations (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id        BIGINT NOT NULL REFERENCES claw.assets(id),
  grantor_user_id BIGINT NOT NULL,   -- 所有权人
  grantee_user_id BIGINT,            -- 被授权人（共享/授权填；转让时置 NULL=进资产大厅）
  auth_type       VARCHAR(16) NOT NULL, -- TRANSFER/SHARE/AUTHORIZE
  scope_json      TEXT,               -- 授予的权限集合(use/locate/revenue/ownership)
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  expires_at      TIMESTAMPTZ,
  tenant_id       BIGINT NOT NULL DEFAULT 1,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_devauth_asset ON claw.device_authorizations(asset_id);
```

### 3.4 V37 订单全生命周期 + 车辆安全

```sql
ALTER TABLE claw.customer_orders
  ADD COLUMN unique_no   VARCHAR(64),   -- 唯一编号
  ADD COLUMN qr_payload  TEXT,          -- 二维码
  ADD COLUMN vin         VARCHAR(64),   -- 车架号
  ADD COLUMN motor_no    VARCHAR(64),   -- 电机号
  ADD COLUMN frame_no    VARCHAR(64),   -- 车架号(主部件)
  ADD COLUMN threshold_config_json TEXT; -- 阈值配置(熄火)

-- 车辆阈值锁机（类比 V30 drone_safety_events）
CREATE TABLE claw.vehicle_safety_events (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id    BIGINT NOT NULL REFERENCES claw.assets(id),
  cause       VARCHAR(24) NOT NULL,  -- THRESHOLD_BATTERY/USAGE_TIME/DISTANCE/MANUAL
  status      VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- OPEN=LOCKED禁通电 / RESOLVED
  detail      TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ
);
CREATE INDEX idx_veh_safety_asset ON claw.vehicle_safety_events(asset_id, status);
```

### 3.5 类图（新增/变更实体与关系）

```mermaid
classDiagram
    class Product { +Long id +String name +String brand +String category +String paramsJson +Integer reportIntervalSeconds +String attrJson }
    class ProductTemplateField { +Long id +Long productId +String fieldKey +String label +String type +String unit +String optionsJson +Boolean required +Integer sortNo }
    class Geofence { +Long id +String ownerType +Long ownerId +String fenceType +BigDecimal centerLat +BigDecimal centerLng +Integer radiusM +String polygonWkt +String triggerAction }
    class Asset { +Long id +Long productId +Long ownerId +String assetNo }
    class Track { +Long id +Long deviceId +Long assetId +Instant ts +BigDecimal lat +BigDecimal lng }
    class TelemetryLatest { +Long id +Long deviceId +Long assetId +BigDecimal lat +BigDecimal lng }
    class Project { +Long id +Long ownerUserId +Long parentId +Integer depth +Integer sortNo +Long accountId +String status }
    class ProjectDevice { +Long id +Long projectId +Long assetId +Long productId +Integer sortNo }
    class DeviceAuthorization { +Long id +Long assetId +Long grantorUserId +Long granteeUserId +String authType +String scopeJson +String status }
    class Account { +Long id +String accountType }
    class CustomerOrder { +Long id +String orderNo +String vin +String motorNo +String frameNo +String qrPayload +String uniqueNo +String thresholdConfigJson }
    class VehicleSafetyEvent { +Long id +Long assetId +String cause +String status }
    class DroneSafetyEvent
    class SharedPoolEntry
    class AssetOwnership

    Product "1" --> "0..*" ProductTemplateField : EAV字段
    Product "1" --> "0..*" Asset : product_id(设备列表)
    Geofence "0..*" --> "1" Product : owner(PRODUCT)
    Geofence "0..*" --> "1" Asset : owner(ASSET)
    Asset "1" --> "0..*" Track : 轨迹
    Asset "1" --> "0..1" TelemetryLatest : 实时
    Project "1" --> "0..*" Project : parent自引用(子项目)
    Project "1" --> "0..*" ProjectDevice
    Project "1" --> "1" Account : 核算(account_type=PROJECT)
    ProjectDevice "0..*" --> "1" Asset
    Asset "1" --> "0..*" DeviceAuthorization : 转让/共享/授权
    DeviceAuthorization "..>" SharedPoolEntry : SHARE→poolAsset
    DeviceAuthorization "..>" AssetOwnership : TRANSFER→资产大厅
    CustomerOrder "1" --> "0..1" Asset : 设备绑定
    VehicleSafetyEvent "0..*" --> "1" Asset : 阈值锁机
    DroneSafetyEvent "0..*" --> "1" Asset : 无人机锁机(同类模式)
```

---

## 4. 端点契约（要点）

| 域 | 方法 + 路径 | 说明 |
| --- | --- | --- |
| 产品(点1/2/4) | `GET/POST/PUT/DELETE /api/v1/admin/products[/{id}]` | 产品 CRUD（仅 admin 建/改/删；商家只读调用） |
| | `GET /api/v1/admin/products/{id}` → 含 `devices`(按 product_id) + `commonAttrs`(params_json+template_fields) | 用户点产品弹窗展示设备+共同属性 |
| | `GET/POST/PUT/DELETE /api/v1/admin/products/{id}/fields` | 模版字段 EAV 增删改 |
| 位置(点3) | `GET /api/v1/iot/locations/product/{productId}` | 产品聚合位置（派生） |
| | `GET /api/v1/iot/tracks/{assetId}?from=&to=` | 历史轨迹回放（TrackSimplifier 抽稀） |
| | `GET/POST/PUT/DELETE /api/v1/iot/geofences[/{id}]` | 围栏 CRUD |
| 项目(点5) | `GET/POST/PUT/DELETE /api/v1/projects[/{id}]` | 项目/子项目 CRUD（owner=当前用户） |
| | `POST /api/v1/projects/{id}/devices` / `DELETE /api/v1/projects/devices/{pdId}` | 绑定/解绑设备 |
| | `POST /api/v1/projects/devices/{pdId}/authorize {authType, granteeUserId, scope}` | 转让/共享/授权三态 |
| 商品(点6) | `GET /api/v1/admin/goods` (自己发布的产品) | 商品列表 |
| | `POST /api/v1/admin/goods/wizard` (分步) | 引导式发布（选产品+分类→品牌→标题≤30→SKU/主图→直播/分享） |
| 订单(点7) | `POST /api/v1/admin/customer-orders` 带 `vin/motorNo/frameNo/qrPayload/uniqueNo/thresholdConfigJson` | 录主部件编号 |
| | `POST /api/v1/admin/customer-orders/{id}/ship` | 发货到用户位置或进共享资产大厅 |
| | `POST /api/v1/iot/safety/vehicle/{assetId}/lock {cause}` | 阈值熄火（复用锁机模式） |

---

## 5. 分期建议（A–E）

| 期 | 范围 | 迁移 | 工作量量级 | 关键文件 |
| --- | --- | --- | --- | --- |
| **A 数据底座** | 产品表 CRUD + 模版字段 EAV + 位置/围栏 | V35 | **M（中）** | `Product.java`(扩)、`ProductTemplateField*.java`、`Geofence*.java`、`AdminProductController.java`、`GeofenceService.java`、`LocationViewService.java`、`ProductDtos.java` |
| **B 易用性** | 设备详情整合 + 产品中心导航 | 无（前端为主） | **S（小）** | `nav.js`(删 device-twin)、`ProductCenter.jsx`(新)、`DeviceDetail.jsx`(改 deviceId 入参) |
| **C 项目管理域** | projects/project_devices/device_authorizations + ledger 核算 | V36 | **L（大）** | `domain/project/*`、`ProjectService.java`、`ProjectDtos.java` |
| **D 商品向导发布** | 商品列表子菜单 + 引导式向导 | 无（复用 products） | **M（中）** | `GoodsList.jsx`(新)、`ProductWizard.jsx`(新)、`nav.js`(加 goods-list) |
| **E 订单全生命周期增强** | 主部件编号 + 阈值熄火 | V37 | **M（中）** | `CustomerOrder.java`(扩)、`VehicleSafetyEvent*.java`、`VehicleSafetyService.java`(复用 DroneSafetyService)、`AdminCustomerOrderController.java`(扩) |

> 依赖：A 先于 B/C/D（B/C/D 并行）；E 依赖 order 域(V34，已落地)+ A 的位置/设备数据。C 的共享/转让依赖 sharedpool/ledger（已有）。

---

## 6. 依赖包列表

**无新增第三方依赖。** 全部复用现有栈（Spring Boot 3.3.4 / Java 21 / Spring Data JPA / Flyway / PostgreSQL / JJWT / Redis / RabbitMQ / Lombok / React / Ant Design）。轨迹抽稀复用既有 `TrackSimplifier`（Java 实现，不引 PostGIS）；图片识别（OCR）为点 6 预留接口，本期不接（后续可引入 OCR 服务，不阻塞）。

---

## 7. 共享知识（跨文件约定）

1. **金额/时间**：金额 `NUMERIC(18,4)`；时间 `TIMESTAMPTZ`(UTC)。
2. **字段扩展**：模版字段走 `product_template_fields`(EAV) + 值走 `products.params_json`/`attr_json`；订单主部件编号走 `customer_orders` 定列（结构化便于监管检索，非 JSON）。
3. **位置复用**：设备位置一律经 `tracks`/`telemetry_latest`；产品位置=派生视图；围栏几何照搬 `airspace_zones`（center/radius 或 polygon_wkt）。
4. **锁机模式**：`VehicleSafetyEvent` 与 `DroneSafetyEvent` 同构——任一条 OPEN → 资产 LOCKED 禁通电；resolve → 恢复。阈值配置存 `customer_orders.threshold_config_json`。
5. **项目核算**：每个 `projects` 绑定一个 `accounts`(account_type=PROJECT)，收支经 `AccountEntry` 双记账，复用 `LedgerService`。
6. **三态授权**：TRANSFER(所有权→资产大厅/AssetOwnership)、SHARE(入池→SharedPoolService)、AUTHORIZE(使用权→device_authorizations)，正交并存。
7. **统一响应**：`ApiResult<T>`；异常 `BizException`。
8. **跨域边界**：project/order 包禁止直持他域 Repository（ArchUnit）；跨域走服务接口 / `@TransactionalEventListener(AFTER_COMMIT)`。

---

## 8. 待明确事项（≤5 条，均给推荐默认）

| # | 待确认 | 对设计影响 | 推荐默认（先按此开发，不阻塞） |
| --- | --- | --- | --- |
| 1 | **"产品表"归属模型**：管理员独家建设 vs 厂家可建、平台审核？ | 点 1 权限粒度 | **管理员（平台）建设产品目录**；厂家/商家仅只读"调用"（引用 products 发商品）。最简且贴合"仅管理员可建设"原话 |
| 2 | **模版字段粒度**：挂在单个产品 vs 产品类目（category）？ | `product_template_fields.product_id` 是否改 `category` | **挂在单个 product**（需求是"每个产品模版"）；若多产品共用同模版，后续抽 `product_templates` 表，**不阻塞** |
| 3 | **资产大厅**具体形态：独立页面 vs 复用"资产台账+公开筛选"？ | 转让落点 | **复用 `assets` + 状态（如 LISTED）+ 公开可见视图**，不新建资产大厅表；转让=所有权变更+置 LISTED |
| 4 | **阈值熄火触发源**：电池 SOC / 使用时长 / 里程，谁优先？ | `threshold_config_json` 结构 + 判定 | **三者皆可配，默认 SOC 阈值**（类比 drone LOW_BATTERY）；判定在 `VehicleSafetyService` 轮询 tracks/telemetry |
| 5 | **引导式发布"基本属性"来源**：用 `products.params_json` 还是 `product_template_fields`？ | 点 6 向导第二页数据 | **优先 `product_template_fields`**（点 2 模版），回退 `params_json`；两者并存，向导读取后预填 |

---

## 9. 风险与待确认

1. **点 2 反模式风险**：若用户坚持"每模版单独建表"，将违反 Flyway 纪律与 ArchUnit，且无法 JPA 映射——已在 §1.3① 明确反对并给替代。**需主理人向用户拍板确认采用 EAV 方案**。
2. **DeviceDetail 去 Select 化改造**：当前 DeviceDetail 强依赖右上角 Select；改为产品中心抽屉内联需重构其 deviceId 获取方式（props/query），属中小改动，B 期评估。
3. **车辆安全域与无人机安全域合并**：推荐统一 `AssetSafetyService` 覆盖 EV/DRONE，但 drone 已上线——泛化需回归测试 drone 行为，避免影响在产低空业务。
4. **图片识别（OCR）**：点 6"上传图片识别"为预留接口，本期不实现，向导主图上传复用现有 `/v1/admin/upload`。
5. **权限边界**：点 1"商家只有调用权"与点 6"商家发布商品"表面冲突——按待确认#1 默认（平台建产品目录、商家发商品引用之）可化解；若用户要求商家自建产品，则需放开 products 写权限给 manufacturer 角色。

*— 增量设计文档终。配套类图见 §3.5 mermaid；时序图（关键流程）由工程师按端点契约补充。*
