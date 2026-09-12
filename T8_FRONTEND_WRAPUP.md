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
