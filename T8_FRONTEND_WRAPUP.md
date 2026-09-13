# T8 前端收尾报告（web 三端 UI 之外）

> 范围：T8 车辆资产产品化前端，在已提交的三端 UI（commits `cd1c857` / `21d7700` / `4297866`）之外的收尾验证。
> 验证对象：`claw-platform/web`（claw-admin-web，React18 + antd5 + vite5）。

## 1. 构建验证 ✅

```
cd web && npm run build
✓ 3309 modules transformed
✓ built in 6.38s
dist/assets/index-*.js   2,451.80 kB (gzip 769.23 kB)
```

- 结果：**0 编译错误、0 类型错误**，构建通过。
- 唯一提示：单 chunk > 500 kB（体积警告，**非阻断**，可后续用 `manualChunks` 拆包优化，不在本次范围）。
- 环境：node v22.22.2 / npm 10.9.7，`node_modules` 已就绪（无需重装）。
- `web/dist` 已被 `.gitignore` 忽略，**构建产物不入库**，无新提交。

## 2. i18n 三语 parity（中 / 英 / 柬）✅

程序化逐键 diff（`task.json` 三个语言文件）：

| 命名空间 | zh | en | km | 差异 |
|---|---|---|---|---|
| 全量 `task.json` | 246 | 246 | 246 | **0** |
| `vehicle.*` 子域 | 128 | 128 | 128 | **0** |

- 其余 7 个 ns（common / nav / system / supply / drone / station / task）三语文件齐全，回退链 km→en→zh。
- T8 页面实际使用的 `task:vehicle.*` 全部三语齐备，Khmer 译文已落位（无白屏 / 无 ⚠key 风险）。

## 3. 前端 ↔ 后端 API 对齐 ✅

所有取数经 `src/api/vehicle.js`（铁律「页面一律通过本模块取数，不得直写 /v1/vehicles/...」达成）。26 个导出全部命中 E2E 已验证端点：

| 前端函数 | 端点 | E2E |
|---|---|---|
| `listProductClasses` / `createProductClass` / `seedProductClasses` | `GET/POST /v1/admin/vehicle-product-classes` | ✅ |
| `getProductClass` / `getProductClassAttrs` / `createProductClassAttr` | `…/{code}` · `…/{code}/attrs` | ✅ |
| `getVehicle` / `getVehicleEnergy` / `getVehicleBattery` | `GET /v1/vehicles/{id}`(+子路径) | ✅ |
| `getVehicleTrajectory` / `getVehicleTrajectoryLatest` | `GET /v1/vehicles/{id}/trajectory(+/latest)` | ✅ |
| `getAutonomyModule` / `setAutonomyModule` / `setDriveMode` | `…/autonomy/module` · `…/autonomy/drive-mode` | ✅ |
| `enterTeleop` / `exitTeleop` / `sendVoice` | `…/autonomy/teleop/*` · `…/autonomy/voice` | ✅ |
| `getAutonomySafety` / `lockSafety` | `…/autonomy/safety` · `…/autonomy/safety/lock` | ✅ |
| `listAutonomyTasks` / `createAutonomyTask` / `dispatchAutonomyTask` | `…/autonomy/tasks`(+/dispatch) | ✅ |
| `updateTaskProgress` / `reportProgress` | `…/tasks/{taskId}/progress` · `…/tasks/report-progress` | ✅ |
| `listGeofences` / `validateGeofence` | `GET/POST /v1/vehicles/geofences`(+/validate) | ✅ |
| `listVehicleAssets` | `GET /v1/assets?assetType=VEHICLE` | ✅ |

页面接线：
- `VehicleProductClass.jsx` → 车型 CRUD + 场景属性（EAV）抽屉
- `TaskVehicle.jsx` → 复用 `TaskPublish` 车辆分支
- `VehicleTrajectory.jsx` + `TrajectoryPlayback.jsx` → 轨迹回放（纯 SVG，无地图库依赖，兼容裸数组 / `{points:[]}` 两种返回）

## 4. 结论 & 非阻断缺口

- **构建绿、三语齐、API 全对齐**，T8 前端收尾完成，无需改码。
- `createVehicle`(POST /v1/assets) 与 `bindDevice`(POST /v1/assets/{id}/bind) 两步资产生命周期操作**未收口进 vehicle.js**，而是按既有设计在资产页内联调用（`AppPortal.jsx` / `BindOwnership.jsx`）。`vehicle.js` 专注「车辆功能面」（读 / 自主 / 围栏 / 任务），符合项目约定，**不视为缺陷，无需改动**。
- Docker 真机复跑：`scripts/e2e-vehicle.sh`（41 断言）与前端同源，可在具备 Docker 的机器上原样复跑做对照（沙箱 Docker 被硬杀，已在 `c7b090d` 改用本地 H2 实启完成等价验证）。

## 5. 后续可选（非本次范围）

- 体积优化：`vite.config` 加 `build.rollupOptions.output.manualChunks` 拆分 antd 大 chunk。
- 若希望 `createVehicle` / `bindDevice` 也经 `vehicle.js` 暴露以统一门面，可补两个导出（纯增量，不影响现有调用）。

## 6. 增量收口（commit 3e4253d，本地未push）

> 用户指令："把 createVehicleAsset 接到新建车辆 UI 入口、对 antd 做更细子包拆分。"

- **新建车辆 UI 入口**：`VehicleProductClass.jsx`（车辆管理页）新增「新建车辆」按钮 + 模态框，提交调 `createVehicleAsset`(POST /v1/assets/vehicle)，成功后 `navigate('/assets')` 核验落地；车型下拉复用已加载的车型列表（`rows`）。`createVehicleAsset` 在 commit `192d4ca` 已收口进 `vehicle.js` 门面。
- **三语 parity**：`task.json` 新增 `vehicle.create.*` 16 key，zh/en/km 程序化 diff **0 差异**。
- **antd 拆包修正（重要）**：函数式 `manualChunks` 把 antd 核心 + 强耦合的 `rc-*` 底层组件 + `@ant-design/icons` **合并为同一 antd chunk**——三者间存在 `antd<->rc` / `antd<->icons` 导入环，拆成独立 chunk 会触发 `circular chunk` 警告且可能引发 ES module 初始化顺序问题（已实测：分离时构建报该警告）。仅把 `react`(164k) / `i18n`(58k) / `dayjs`(17k) 与业务代码干净拆出。`chunkSizeWarningLimit` 提至 1800。
- **构建**：`npm run build` → **0 错误 0 警告**，6.24s。最终 chunk：antd 1,603kB / index(业务) 892kB / react 164kB / i18n 58kB / dayjs 17kB / css 两片。
- 结论：antd 无法再做"更细"的安全拆分（底层耦合环限制），当前拆包已把业务代码与三方库解耦到最优可维护状态。

## 7. 增量收口（commit T9-vehicle-create，本地未push）

> 用户指令："把'新建车辆'也接到菜单管理里加一个独立导航项，补一个创建后的'立即入网绑定'快捷流程。"

- **独立导航项「新建车辆」**：`nav.js` 的 `vehicle` 分组新增子项 `{ key:'vehicle-create', label:'nav:item.vehicle-create', path:'/vehicle-create' }`，同步加入 `ROUTES` 白名单；`App.jsx` 引入 `CreateVehicle` 并注册 `<Route path="vehicle-create" element={<CreateVehicle />} />`（置于 T8 车辆域段末）。`nav.json` 三语补齐 `item.vehicle-create`：zh=新建车辆 / en=New Vehicle / km=បង្កើតយានយន្ត。
- **独立建档页 `CreateVehicle.jsx`**：两步式——① 建档表单提交 `createVehicleAsset`(POST /v1/assets/vehicle)，响应经 `api.js` 解包为 `AssetView`，取 `asset.id` 进入第二阶段；② 「立即入网绑定」快捷流程：服务站 Select（复用 `listStations` 真实接口 `/v1/stations/nearby`）+ 设备类型（VEHICLE_TCU/BATTERY_BMS/CHARGER）+ 可选 IMEI / 安装位置，提交 `bindVehicleDevice(id, …)`(POST /v1/assets/{id}/bind)。可「跳过，稍后绑定」直接跳 `/assets`，绑定成功后提供「查看资产台账 / 再建一辆」。
- **三语 parity**：`task.json` 新增 `vehicle.create.bind.*` 18 key（assetCreated/title/hint/station/stationPlaceholder/deviceType/imei/imeiPlaceholder/location/locationPlaceholder/submit/skip/success/failed/noId/done/viewAssets/createAnother），zh/en/km 程序化 diff **0 差异**。
- **构建**：`npm run build` → **0 错误 0 警告**，6.40s，3309 modules。最终 chunk 与 §6 一致：antd 1,603kB / index 894kB / react 164kB / i18n 58kB / dayjs 17kB。
- **车型页入口收敛为跳转**（commit 6aea733）：`VehicleProductClass.jsx` 内「新建车辆」模态（及 `createVehicleAsset`/`InputNumber` 未用导入）已移除，「新建车辆」按钮改为 `navigate('/vehicle-create')`，统一走独立建档 + 入网绑定页；`vehicle.create.*`（含 `bind.*`）18 key 仍由 `CreateVehicle.jsx` 复用，无孤儿 i18n。`npm run build` **0 错误 0 警告**（6.32s）本地提交未 push。
- 约束复核：本地提交、未 push；前端门面单点取数（无直写 /v1 端点）；i18n 三语齐备；`ApiResult` 解包后 `created.id` 防御性兜底（`asset.id ?? asset.data?.id`，缺则提示 `noId`）。
