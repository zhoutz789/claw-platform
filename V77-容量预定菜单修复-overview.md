# ④ 容量预订 / 服务站合约 菜单入口修复（已完成·已验证）

## 问题
"容量预定"功能后端 `domain/capacity/*` + `CapacityController`/`AdminCapacityController` + 前端 `CapacityBooking.jsx` 早已就绪，但侧边栏看不到入口。根因：
- `permissions` 表没有对应的 `menu:capacity-booking` 菜单权限节点；
- 因此前端 `nav.js` 也不含该项（同一类"漂移"缺陷也影响了 `station-contracts`）；
- `App.jsx` 虽注册了路由，但 `AdminLayout:60` 的 `menu:{key}` 单层守卫遇到无权限直接 403。

## 修复
1. **迁移 `V77__capacity_booking_and_station_contract_menu.sql`**（幂等，三步法，沿用 V67/V68 范式）
   - 新增节点：`menu:capacity-booking`（父 `menu:supply`，path `/capacity-booking`，sort 458）、`menu:station-contracts`（父 `menu:station`，sort 4）、`station-contract:manage`（BUTTON，父 `menu:station`，sort 50）。
   - 角色模板挂载：`MANUFACTURER→capacity-booking`；`STATION→capacity-booking+station-contracts+station-contract:manage`；`PLATFORM_ADMIN→三者`。
   - `roles.grants` 仅回写 `MANUFACTURER/STATION`（避开 PLATFORM_ADMIN 通配降级、CUSTOMER 对象结构两大陷阱）。
2. **`web/src/nav.js`**：supply 组末追加 `capacity-booking`；station 组末追加 `station-contracts`（三语 i18n 键已存在）。

## 验证结果
- 重启 :8080 后端 → Flyway 应用 V77：`now at version v77`。
- DB 核验：3 个权限节点、挂载关系、`MANUFACTURER/STATION` 的 grants 均正确；`PLATFORM_ADMIN` 仍为 `["*"]`（未被降级）。
- Vite(:5173) 实际 served `nav.js` 含两项新菜单。
- 用户刷新管理后台（http://localhost:5173/）即可看到「供应流通 → 容量预订」「服务站 → 服务站合约管理」。

## 注意
- `:8090` 是早前 `-Plocal`（H2 内存库）残留实例；web 经 Vite 代理访问 `:8080`（PostgreSQL），本次修复落在正确的库上。
- 下一步（用户拍板后继续）：② 平台设置归类 + 类别管理 CRUD 新模块；③ 合格证编辑入口 + EAV 模板 + A4 打印。
