# 增量设计 · 任务大厅（Task Hall）· 增量 PRD + 域模型 + 迁移清单

> 状态：设计闭环（已锁定两项关键决策，待实现）
> 关联：复用 `TaskPublish.jsx`（现有 6 子模式枢纽）、`DroneMission`/空域/payload（无人机子类型）、`LedgerService`+三套结算、`项目管理域 V36`、`AssetType` 枚举、RBAC/菜单/i18n 三语骨架。
> 非目标：不新建第二个收益账本、不新建第二套权限体系、不做跨 claw+uav 聚合（uav 本期退化为无人机子引擎）。

---

## 1. 决策锁定（按推荐）

- **D1 范围 = claw 内统一任务大厅**：复用 claw 已有无人机域（`DroneMission`/airspace/payload），uav-platform 退化为无人机专用子引擎（后续可选对接）。
- **D2 客运/打的 资产建模 = 能力标签化**：在现有 `VEHICLE`/`EV` 上加 `capabilities` 能力标签（SET），**不新增 `AssetType`**。一辆车的资产可同时挂 `RIDE_HAIL`(客运/`招手即停`)+`TAXI`(打的)+`LOGISTICS`(物流)+`AD_DISPLAY`(广告)，任务按能力标签+地理匹配，而非按 `assetType` 硬匹配。

---

## 2. 定位与边界

任务大厅是"**需求聚合 + 接单 + 收益挂载资产**"的统一中台，属于 claw-platform 增量模块。

- 复用：资产 `Asset`、`LedgerService` + `FulfillmentSettlementService`/`CrossBorderSettlementService`/`StationSettlementService`、项目管理域 V36（`AssetProject`）、无人机域、现有 RBAC/菜单/i18n 三语。
- 不新建：第二个收益账本、第二个权限体系。

---

## 3. 角色与双视角（双边市场）

同一自然人可兼具两角色，通过**视角切换**，不拆应用：

| 视角 | 能力 | 数据来源 |
|---|---|---|
| **发布方（需求方）** | 发布任务、看"我发布的"+ 状态/进度 | `publisher_id = 当前用户` |
| **接单方（资产持有方/服务商）** | 看"可接单"（按能力标签+地理匹配）、接单、"我的接单"（服务进度 + 收益） | `provider_id = 当前用户` + 资产具备 `capabilityRequired` |

> 现状只建模了运营/发布侧，本模块补齐接单方视角与 `TaskAssignment` 接单链路。

---

## 4. 域模型（`backend/.../domain/task/`）

### 4.1 基类 `Task`
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| publisher_id | BIGINT | 发布方用户 |
| task_type | ENUM | 见 4.2 |
| title / description | VARCHAR | |
| reward_amount | NUMERIC(18,4) | 报酬（接单产生收入） |
| currency | VARCHAR(3) | 默认 USD（柬埔寨） |
| capability_required | ENUM `AssetCapability` | 接单资产须具备的能力 |
| status | ENUM `TaskStatus` | 见 4.3 |
| geo_lat / geo_lng | DECIMAL | 发布地（或 zone_id 关联空域） |
| service_radius_m | INT | 可接单匹配半径 |
| created_at / deadline_at / assigned_at / completed_at / settled_at | TIMESTAMP | |

### 4.2 `TaskType` 枚举
`DRONE_OP`(无人机作业) / `LOGISTICS`(物流配送) / `HAIL_RIDE`(招手即停客运) / `TAXI`(打的) / `AD`(广告)

### 4.3 `TaskStatus` 枚举
`OPEN` → `ASSIGNED` → `IN_PROGRESS` → `COMPLETED` → `SETTLED`，外加 `CANCELLED`(任意非终态可转)、`DISPUTED`(`COMPLETED` 后 → 仲裁 → `SETTLED`/退款)

### 4.4 `TaskAssignment`（接单链接）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| task_id | BIGINT FK Task | |
| provider_id | BIGINT | 接单方 |
| asset_id | BIGINT FK Asset | **收益挂载的资产**（核心：收益看板走这里） |
| project_id | BIGINT FK AssetProject NULL | 复用 V36 项目管理域，服务进度挂在"自己项目该资产" |
| status | ENUM | 镜像 TaskStatus 或独立小状态机 |
| progress_pct | INT | 服务进度 |
| last_progress_note | VARCHAR | |
| started_at / finished_at | TIMESTAMP | |

### 4.5 类型扩展表（与 `Task` 一对一）
- `task_logistics`：pickup_addr, dropoff_addr, cargo_type, weight_kg, vehicle_required(`LOGISTICS`)
- `task_ride`（覆盖 客运/打的）：origin_addr, dest_addr, ride_type(`HAIL`|`TAXI`), est_distance_km, est_duration_min, fare_model（按里程/时长）
- `task_ad`：advertiser, media_url, display_duration, screen_type(车身/屏显), asset_required(`AD_DISPLAY`)
- `task_drone_op`：**不新建表**，通过 `drone_mission_id` 关联既有 `drone_missions`（`DroneMission` 已是真后端）

### 4.6 资产能力标签
- `assets` 表新增列 `capabilities`（`JSONB` 或 `SET` 编码），存 `AssetCapability` 集合。
- 新增枚举 `AssetCapability`：`RIDE_HAIL` / `TAXI` / `LOGISTICS` / `AD_DISPLAY` / `DRONE_OP` / `SWAP` / …

---

## 5. 状态机

```
OPEN ──接单──▶ ASSIGNED ──开始服务──▶ IN_PROGRESS ──完成──▶ COMPLETED ──结算──▶ SETTLED
 │                                              │                          │
 └────────── CANCELLED ◀────────────────────────┘── DISPUTED ──仲裁──▶ SETTLED / 退款
```
各类型可加子状态（无人机：飞行中/已着陆；客运：行程中/已到达），由扩展表或 `progress_note` 承载。

---

## 6. 收益与结算集成（复用现有，不新建）

- 任务 `COMPLETED` → 触发 **`TaskSettlementService`**（新建 service，但**复用 `LedgerService.postEntries` 与现有分成引擎**）。
- 分成规则复用既有：`uav 55/25/12/8`、`claw` 提成规则 / `revenue_split_rules`；平台 / 资产持有方 / 服务站 / 飞手 分成。
- 入账 `biz_ref` 形如 `TASK-{taskId}-{assignmentId}`，资产账本按此过滤即得"任务收益"视图。
- **挂载到资产**：`Asset` 详情页新增「任务收益」Tab（后端 `GET /assets/{id}/task-earnings`）+「我的任务」子面板（`GET /me/my-tasks`）。

---

## 7. 前端结构（改造 `TaskPublish.jsx`）

- 单一**「任务大厅」枢纽页**替换 6 个散落 `task-*` 入口：
  - 顶栏：视角切换（发布方/接单方）+ 类型筛选（无人机/物流/客运/打的/广告）
  - 发布方视图：我发布的任务列表 + 状态/进度
  - 接单方视图：可接单（按能力标签+地理）+ 我的接单（进度+收益）
- 各类型独立明细/流程页（复用现有 `TAB_MAP` 表单）：`TaskDrone` / `TaskLogistics` / `TaskRide` / `TaskAd`。
- 资产详情页增 Tab（任务收益 / 我的任务）。
- 三语 i18n 复用现有体系（新增少量 key）。

---

## 8. 迁移脚本清单（Flyway，序号接现有最大 V）

| 脚本 | 内容 |
|---|---|
| `V??__task_hall_base.sql` | `tasks` / `task_assignments` 表 + `TaskType`/`TaskStatus`/`AssetCapability` 枚举 |
| `V??__task_extensions.sql` | `task_logistics` / `task_ride` / `task_ad` 表 |
| `V??__asset_capabilities.sql` | `assets` 加 `capabilities` 列；给现有 `VEHICLE`/`EV` 默认打 `LOGISTICS`+`RIDE_HAIL`+`TAXI` 标签的种子更新 |
| `V??__task_settlement.sql` | （如需新表）结算关联；否则仅 service 层复用 `ledger`，无新表 |
| `V??__task_seed.sql` | 演示任务 + 能力标签种子（可选，体验用） |

> 注：`DroneMission` 已存在（`drone_missions`），`DRONE_OP` 类型经 `drone_mission_id` 关联，**不新建表**。

---

## 9. API 清单（`web/v1`）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/tasks` | 发布任务 | 发布方角色 + `task:publish` |
| GET | `/tasks?role=publisher\|provider&type=&status=` | 列表（双视角） | 登录 |
| GET | `/tasks/{id}` | 任务详情（含扩展表） | 登录 |
| POST | `/tasks/{id}/accept` | 接单 → 建 `TaskAssignment` + 绑 `Asset`(须具备 `capability_required`) | 接单方 + 资产能力校验 |
| POST | `/tasks/{id}/progress` | 更新进度 | 接单方 |
| POST | `/tasks/{id}/complete` | 完成 → 触发结算 | 接单方 |
| GET | `/assets/{id}/task-earnings` | 资产任务收益（账本按 biz_ref 过滤） | 资产持有方 |
| GET | `/me/my-tasks` | 我的任务（发布/接单） | 登录 |

---

## 10. 验收 / 闭环标准

端到端跑通一例即可证明闭环：
1. 发布方发一个 **物流任务**（报酬 $5）→
2. 接单方（持 `LOGISTICS` 标签资产的用户）在"可接单"看到并接单 → `TaskAssignment` 绑定资产 →
3. 更新进度至完成 → 触发 `TaskSettlementService` →
4. 资产账本出现 `TASK-{id}-{aid}` 入账、分成正确 →
5. 资产详情「任务收益」Tab 可见该笔收益。
6. 三语、权限、结算**复用既有，不回归**。

---

## 11. 不在本期（P2+）

- 跨 claw+uav 聚合（本期 claw 内统一）。
- 录像数据 / 资产出租 作为独立任务类型（本期只做 5 类：无人机/物流/客运/打的/广告；`video`/`rent` 留 P2）。
- 实时调度 / 智能撮合引擎（本期按能力标签 + 地理简单匹配，复杂匹配 P2）。
- uav-platform 与 claw 的任务数据打通（P2，uav 退化为无人机子引擎时再做）。

---

## 12. 实现阶段建议（供排期）

- **Phase 1（P0）**：`V??__task_hall_base` + `V??__asset_capabilities` 迁移；`domain/task/` 实体+仓储+`TaskService`；`LOGISTICS` 类型端到端打通（发布→接单→进度→完成→结算→资产收益）；前端枢纽页 + 物流子页。→ 即上述验收闭环。
- **Phase 2**：补齐 `HAIL_RIDE`/`TAXI`/`AD` 三类型 + 各自子页 + 能力标签匹配 UI。
- **Phase 3**：`DRONE_OP` 经 `DroneMission` 关联接入大厅；资产详情 Tab；三语补全；接单方"可接单"地理匹配优化。
