// 模块四 · 服务站三层解耦（库存 / 项目 / 结算）前端接口封装。
//
// 全部对接 claw-platform/backend 的真实 Controller（前缀 /v1/station/*）：
//   StationInventoryController    /api/v1/station/inventory
//   StationProjectController      /api/v1/station/projects
//   StationSettlementController   /api/v1/station/settlements
// 无任何 mock 数据，与 supplyChain.js 保持同一风格（cleanParams + api 实例）。
import api from '../api.js';
import { cleanParams } from './supplyChain.js';

/* ------------------------------ ① 库存层 ------------------------------ */

/** 当前库存列表（按 allowedStationIds 过滤，可选 stationId/skuCode）。 */
export const listStationInventory = (p = {}) =>
  api.get('/v1/station/inventory', { params: cleanParams(p) });

/** 出入库流水（消耗数据源）。 */
export const listStationMovements = (p = {}) =>
  api.get('/v1/station/inventory/movements', { params: cleanParams(p) });

/** 作用域视图（复用 InventoryScope，返回 allowedStationIds + level + 引导）。 */
export const getStationScope = (stationId) =>
  api.get('/v1/station/inventory/me', { params: cleanParams({ stationId }) });

/** 服务站入站收货（+delta，写 stock+movements）。 */
export const inboundStationInventory = (body) => api.post('/v1/station/inventory/inbound', body);

/** 服务站盘点调整（±delta，写 stock+movements）。 */
export const adjustStationInventory = (body) => api.post('/v1/station/inventory/adjust', body);

/* ------------------------------ ② 项目层 ------------------------------ */

/** 站下项目树（按 allowedStationIds）。 */
export const listStationProjects = () => api.get('/v1/station/projects');

/** 新建项目（可挂 parentId）。 */
export const createStationProject = (body) => api.post('/v1/station/projects', body);

/** 更新项目（改名/改父/状态）。 */
export const updateStationProject = (id, body) => api.put(`/v1/station/projects/${id}`, body);

/** 删除项目（子项目上提一级，解绑 alloc）。 */
export const deleteStationProject = (id) => api.delete(`/v1/station/projects/${id}`);

/** 项目占用列表（含读时可用量）。 */
export const listStationProjectAllocs = (id) => api.get(`/v1/station/projects/${id}/allocations`);

/** 占用库存（只写 alloc 表，绝不扣库存）。 */
export const allocStationInventory = (id, body) =>
  api.post(`/v1/station/projects/${id}/allocations`, body);

/** 解除占用。 */
export const deallocStationInventory = (allocId) =>
  api.delete(`/v1/station/projects/allocations/${allocId}`);

/* ------------------------------ ③ 结算层 ------------------------------ */

/** 结算单列表（按 allowedStationIds）。 */
export const listStationSettlements = (p = {}) =>
  api.get('/v1/station/settlements', { params: cleanParams(p) });

/** 结算单 + 明细。 */
export const getStationSettlement = (id) => api.get(`/v1/station/settlements/${id}`);

/** 按 (stationId, 周期) 生成草稿。 */
export const generateStationSettlement = (body) => api.post('/v1/station/settlements/generate', body);

/** 确认（DRAFT→CONFIRMED）。 */
export const confirmStationSettlement = (id) => api.post(`/v1/station/settlements/${id}/confirm`);

/** 标记支付（CONFIRMED→PAID）。 */
export const payStationSettlement = (id) => api.post(`/v1/station/settlements/${id}/pay`);

export default {
  listStationInventory,
  listStationMovements,
  getStationScope,
  inboundStationInventory,
  adjustStationInventory,
  listStationProjects,
  createStationProject,
  updateStationProject,
  deleteStationProject,
  listStationProjectAllocs,
  allocStationInventory,
  deallocStationInventory,
  listStationSettlements,
  getStationSettlement,
  generateStationSettlement,
  confirmStationSettlement,
  payStationSettlement,
};
