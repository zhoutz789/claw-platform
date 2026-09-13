# 任务大厅（Task Hall）Phase 2 (P2) 收口说明

> 状态：**HAIL_RIDE / TAXI / AD 三类型端到端闭环已完成（本地提交，未推送）**
> 关联：`增量设计-任务大厅-TaskHall-增量PRD.md`（§12 Phase 2）、`任务大厅-Phase1收口说明.md`

## 本轮落地（P2：补齐客运 / 打的 / 广告三类型）

### 后端（commit `a4b3ec4`，V95 迁移 + 实体/仓储/DTO/Service 扩展）
- **迁移 `V95__task_extensions.sql`**：`task_ride`（HAIL_RIDE/TAXI：origin/dest/ride_type/est_distance_km/est_duration_min/fare_model）+ `task_ad`（AD：advertiser/media_url/display_duration/screen_type），均 1:1 FK `tasks`
- **实体** `TaskRide` / `TaskAd` + **仓储** `TaskRideRepository` / `TaskAdRepository`
- `TaskRequests.Publish` 增可选 ride/ad 字段；`TaskViews.TaskView` 增 `ride`/`ad` Map（common.dto 仍无 domain 引用，过 ArchUnit）
- `TaskService.publish` 按类型落 `task_ride`/`task_ad`（`ride_type`↔taskType、`screen_type`∈{BODY,SCREEN} 校验）；`toViewWithExtension` 按类型附 map
- **控制器 / 结算（LedgerService TASK_SETTLEMENT）零改动** —— 全类型通用，复用既有双记账

### 前端（commit `b087f69`，`web/src/pages/TaskPublish.jsx` +520/−18）
- 抽出公共闭环 Hook `useTaskLoop`（发布/可接单/接单/进度/完成/收益）+ `TaskStatusTag`/`EarningsBlock`/`AcceptCell`/`MyAcceptedList`，`logi`/`ad`/`near` 共用
- **`AdPanel`（AD_DISPLAY）**：发布表单 → `POST /v1/tasks`（AD 报文）；列表渲染 `ad` map
- **`RidePanel`（HAIL_RIDE + TAXI）**：HAIL_RIDE/TAXI `Segmented` 切换 → `POST` 对应报文；列表渲染 `ride` map（起点→终点/类型/距离/计价）；`near` 保留只读"附近车辆"(VEHICLE/EV) 参考
- 接单资产统一 `GET /v1/assets` 过滤 VEHICLE/EV；进度/完成/收益复用同一套
- `logi` 与其余 TAB_MAP 未改动；`nav.js` / `mode` 契约未改

## 验证（独立复跑）
- `mvn -o -q compile` 0 错 + `TaskRideAdServiceTest` 3/3 PASS（HAIL_RIDE 落 task_ride、AD 落 task_ad、capability 不匹配抛 BizException）+ ArchTest 绿
- 前端 `npm run build` 0 错（3296 modules）
- 两提交均在本地 `main`，未 push（守约）

## 任务大厅整体进度
- `LOGISTICS`（P0）+ `HAIL_RIDE`/`TAXI`/`AD`（P2）五类中四类已打通
- `DRONE_OP` 为 Phase 3（经 `DroneMission` 关联）；`video`/`rent` 仍占位
- 平台佣金仍 = 0（`system_config` 费率钩子预留）；`task:publish` 权限未注册（仍登录校验）

## 真机 E2E（待 Docker 恢复）
- 启动 PG/Redis/RabbitMQ/EMQX → 后端 8080 → 前端 5173；发布方账户需先有余额（reward 由发布方账户 `D` 出，不足会 42251）
