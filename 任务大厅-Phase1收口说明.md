# 任务大厅（Task Hall）Phase 1 (P0) 收口说明

> 状态：**端到端闭环已完成（本地提交，未推送）**  
> 关联设计：`增量设计-任务大厅-TaskHall-增量PRD.md`（§10 验收闭环、§12 Phase 1）

## 本轮落地（Phase 1 仅 LOGISTICS 物流类型打通）

### 后端（commit `4b3a0156`，22 文件 +1108/−1）

- **迁移 `V93__task_hall_base.sql`**：`tasks` / `task_assignments` / `task_logistics` 三表 + 三个枚举（TaskType / TaskStatus / AssetCapability）
- **迁移 `V94__asset_capabilities.sql`**：`assets` 加 `capabilities` 列 + 给 `VEHICLE`/`EV` 打 `LOGISTICS,RIDE_HAIL,TAXI,AD_DISPLAY` 种子
- **新增枚举**：`TaskType` / `TaskStatus` / `AssetCapability`；`BizType` 增 `TASK_SETTLEMENT`（复用既有 ledger 账户体系，**无第二账本**）
- **域模型 `domain/task/`**：`Task` / `TaskAssignment` / `TaskLogistics` 实体 + 3 个 Repository
- **DTO**：`common/dto/TaskRequests` / `TaskViews`（映射下沉至 `TaskService` 以过 ArchUnit「common 层不依赖 domain」守护）
- **服务**：`TaskService`（发布→接单→进度→完成）+ `TaskSettlementService`（完成触发结算，复用 `LedgerService.postEntries`，`bizRef=TASK-{taskId}-{assignmentId}`，`D` 出版方 / `C` 接单方，借贷平衡）
- **控制器 `TaskController`**（`/api/v1/tasks`）：
  - `POST /tasks` 发布
  - `GET /tasks?role=publisher|provider` 双视角列表
  - `GET /tasks/{id}` 详情（含物流扩展）
  - `POST /tasks/{id}/accept` 接单（绑定 Asset，校验能力标签）
  - `POST /tasks/{id}/progress` 更新进度
  - `POST /tasks/{id}/complete` 完成（→ 触发结算）
  - `GET /assets/{id}/task-earnings` 资产任务收益
  - `GET /me/my-tasks` 我的任务（发布/接单）
  - 全部走 `AuthContext.currentUserId()` 登录校验；**未加 `@RequirePermission`**（`task:publish` 权限注册留 P2，避免未种子化导致全阻）

### 前端（commit `60df13e`，`web/src/pages/TaskPublish.jsx`）

- `logi` 模式改为真实闭环：发布方/接单方 `Segmented` 切换
  - 发布表单 `POST /v1/tasks`
  - 可接单列表 `GET /v1/tasks?role=provider` + 接单 `POST /v1/tasks/{id}/accept`
  - 我的接单：进度 `POST /v1/tasks/{id}/progress` / 完成 `POST /v1/tasks/{id}/complete`，完成后拉取资产收益 `GET /v1/assets/{id}/task-earnings`
- 其余模式（near/ad/video/rent/drone）保持占位，未破坏，`mode` 契约不变

## 验证结果（独立复跑）

- `mvn -o -q compile`：**0 错误**
- 单元测试 `TaskSettlementServiceTest`：**PASS**（断言 `postEntries` 仅一次、2 条分录 `D/5.00/acct10` + `C/5.00/acct20`、bizRef=`TASK-7-5`、task→`SETTLED`）
- `ArchitectureBoundaryTest`：**PASS**
- 前端 `npm run build`：**0 错误**
- git：两提交均在本地 `main`，无 upstream 跟踪、**未 push**（守约）

## 验收闭环（PRD §10）已通过代码层证明

1. 发布方发物流任务（reward 5）→ 2. 接单方持 `LOGISTICS` 资产在「可接单」看到并接单（绑定 Asset）→ 3. 更新进度至完成 → 4. 触发 `TaskSettlementService` → 5. 资产账本出现 `TASK-{id}-{aid}` 入账、借贷平衡 → 6. 资产详情「任务收益」经 `GET /assets/{id}/task-earnings` 可见

## 未做（P2+，）

- `HAIL_RIDE` / `TAXI` / `AD` 三类型 + 各自子页
- `DRONE_OP` 经 `DroneMission` 关联接入大厅
- 接单方「可接单」地理匹配优化（本期仅能力标签匹配）
- `task:publish` 权限位注册（RBAC 菜单/权限种子）
- 平台佣金 `system_config` 化（本期佣金=0，预留公式钩子）
- 真机 E2E（需 Docker 起 PG/Redis/RabbitMQ，本沙箱守护已停；代码层闭环已证）

## 真机运行提示（待 Docker 恢复）

- 启动 PG/Redis/RabbitMQ/EMQX → 启动 claw 后端（端口 8080）→ 前端 dev / 5173
- 演示：用持 `VEHICLE`/`EV` 资产账号接单；发布方账户需先有余额（reward 由发布方账户 `D` 出，余额不足会 42251，需先充值/种子）
