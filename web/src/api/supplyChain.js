// 库存 / 流转 / 渠道域（增量 B）与权限骨架（增量 A）的接口封装。
//
// 全部对接 claw-platform/backend 的真实 Controller，无任何 mock 数据。
// Controller 与基路径对照（后端文件位于 web/v1/）：
//   AdminProductionController        /api/v1/admin/production
//   AdminInventoryController         /api/v1/admin/inventory
//   AdminTransferController          /api/v1/admin/transfers
//   AdminFulfillmentController       /api/v1/admin/fulfillment
//   AdminCommissionController        /api/v1/admin/commission
//   AdminRecoveryController          /api/v1/admin/recovery
//   AdminRoleTemplateController      /api/v1/admin/role-templates
//   AdminRoleGroupController         /api/v1/admin/role-groups
//   AdminPrincipalBindingController  /api/v1/admin/principal-bindings
//   AdminMerchantController          /api/v1/admin/merchants
import api from '../api.js';

/**
 * 去掉未填的查询参数，避免拼出 "manufacturerId=undefined" 这类脏 query。
 * @param {Object} [params] 原始查询参数
 * @returns {Object} 清洗后的查询参数
 */
export function cleanParams(params = {}) {
  return Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  );
}

/* ------------------------------ 下拉选项数据源 ------------------------------ */

/** 厂家列表（真实接口）。 */
export const listManufacturers = () => api.get('/v1/admin/manufacturer/manufacturers');

/** 商品（产品模板）列表，可按厂家过滤。 */
export const listProducts = (manufacturerId) =>
  api.get('/v1/admin/manufacturer/products', { params: cleanParams({ manufacturerId }) });

/** 服务站列表：后端暂无 admin 站点列表接口，复用 S1 的 nearby 接口（limit 放大全量取）。 */
export const listStations = () => api.get('/v1/stations/nearby', { params: { limit: 200 } });

/** 权限目录树（角色模板配置权限码时使用）。 */
export const listPermissionCatalog = () => api.get('/v1/admin/permissions/catalog');

/**
 * 把后端权限目录树拍平成 {label, value} 选项（value 为权限码）。
 * @param {Array} nodes 权限树节点
 * @param {Array} [acc] 累加器
 * @returns {Array<{label: string, value: string}>} 选项数组
 */
export function flattenPermissionCatalog(nodes, acc = []) {
  (nodes || []).forEach((n) => {
    if (n && n.code) acc.push({ label: `${n.name || n.code}（${n.code}）`, value: n.code });
    if (n && Array.isArray(n.children) && n.children.length) flattenPermissionCatalog(n.children, acc);
  });
  return acc;
}

/* -------------------------------- 生产管理 -------------------------------- */

export const listProductionTasks = (manufacturerId) =>
  api.get('/v1/admin/production/tasks', { params: cleanParams({ manufacturerId }) });

export const getProductionTask = (id) => api.get(`/v1/admin/production/tasks/${id}`);

export const createProductionTask = (body) => api.post('/v1/admin/production/tasks', body);

export const completeProductionTask = (id, producedQuantity) =>
  api.post(`/v1/admin/production/tasks/${id}/complete`, { producedQuantity: producedQuantity ?? null });

export const getCertificateByDevice = (deviceId) =>
  api.get(`/v1/admin/production/certificates/device/${deviceId}`);

export const reprintCertificate = (deviceId) =>
  api.get(`/v1/admin/production/certificates/device/${deviceId}/reprint`);

/* --------------------------------- 库存台账 -------------------------------- */

export const listInventory = ({ manufacturerId, stationId, ownershipType } = {}) =>
  api.get('/v1/admin/inventory', { params: cleanParams({ manufacturerId, stationId, ownershipType }) });

export const getInventoryByDevice = (deviceId) => api.get(`/v1/admin/inventory/device/${deviceId}`);

/** 发货至服务站：建立寄售占有权（Q2 占有权转移点）。 */
export const shipToStation = (body) => api.post('/v1/admin/inventory/ship', body);

/* --------------------------------- 调拨单 --------------------------------- */

export const listTransfers = (manufacturerId) =>
  api.get('/v1/admin/transfers', { params: cleanParams({ manufacturerId }) });

export const getTransfer = (id) => api.get(`/v1/admin/transfers/${id}`);

export const createTransfer = (body) => api.post('/v1/admin/transfers', body);

/** 源站扫码交接：占有权转出，设备在途。 */
export const handoverTransfer = (id) => api.post(`/v1/admin/transfers/${id}/handover`);

/** 目标站扫码收货：建立新占有权，库存到站。 */
export const receiveTransfer = (id) => api.post(`/v1/admin/transfers/${id}/receive`);

/* -------------------------------- 待履约订单 ------------------------------- */

export const listFulfillmentOrders = ({ stationId, customerUserId } = {}) =>
  api.get('/v1/admin/fulfillment/orders', { params: cleanParams({ stationId, customerUserId }) });

export const getFulfillmentOrder = (id) => api.get(`/v1/admin/fulfillment/orders/${id}`);

export const createFulfillmentOrder = (body) => api.post('/v1/admin/fulfillment/orders', body);

export const payFulfillmentOrder = (id, paymentRef) =>
  api.post(`/v1/admin/fulfillment/orders/${id}/pay`, { paymentRef: paymentRef || null });

export const confirmFulfillmentOrder = (id) => api.post(`/v1/admin/fulfillment/orders/${id}/confirm`);

export const shipFulfillmentOrder = (id) => api.post(`/v1/admin/fulfillment/orders/${id}/ship`);

export const receiveFulfillmentOrder = (id) => api.post(`/v1/admin/fulfillment/orders/${id}/receive`);

/** 取货扫码履约：扣寄售库 + 建用户授权 + 写 outbox 触发异步结算。 */
export const pickupFulfillmentOrder = (id, deviceIds) =>
  api.post(`/v1/admin/fulfillment/orders/${id}/pickup`, { deviceIds: deviceIds || [] });

export const cancelFulfillmentOrder = (id) => api.post(`/v1/admin/fulfillment/orders/${id}/cancel`);

export const expireFulfillmentOrder = (id) => api.post(`/v1/admin/fulfillment/orders/${id}/expire`);

/* -------------------------------- 提成规则 -------------------------------- */

export const listCommissionRules = (manufacturerId) =>
  api.get('/v1/admin/commission/rules', { params: cleanParams({ manufacturerId }) });

export const createCommissionRule = (body) => api.post('/v1/admin/commission/rules', body);

export const updateCommissionRule = (id, body) => api.put(`/v1/admin/commission/rules/${id}`, body);

export const deleteCommissionRule = (id) => api.delete(`/v1/admin/commission/rules/${id}`);

/* --------------------------------- 回收单 --------------------------------- */

export const listRecoveryOrders = (manufacturerId) =>
  api.get('/v1/admin/recovery/orders', { params: cleanParams({ manufacturerId }) });

export const createRecoveryOrder = (body) => api.post('/v1/admin/recovery/orders', body);

export const confirmRecoveryOrder = (id) => api.post(`/v1/admin/recovery/orders/${id}/confirm`);

/* -------------------------------- 角色模板 -------------------------------- */

export const listRoleTemplates = () => api.get('/v1/admin/role-templates');

export const createRoleTemplate = (body) => api.post('/v1/admin/role-templates', body);

export const updateRoleTemplate = (code, body) => api.put(`/v1/admin/role-templates/${code}`, body);

export const listTemplatePermissions = (code) => api.get(`/v1/admin/role-templates/${code}/permissions`);

export const setTemplatePermissions = (code, permissionCodes) =>
  api.put(`/v1/admin/role-templates/${code}/permissions`, { permissionCodes: permissionCodes || [] });

/* --------------------------------- 角色组 --------------------------------- */

export const listRoleGroups = () => api.get('/v1/admin/role-groups');

export const createRoleGroup = (body) => api.post('/v1/admin/role-groups', body);

export const updateRoleGroup = (code, body) => api.put(`/v1/admin/role-groups/${code}`, body);

export const deleteRoleGroup = (code) => api.delete(`/v1/admin/role-groups/${code}`);

export const listGroupTemplates = (code) => api.get(`/v1/admin/role-groups/${code}/templates`);

export const addGroupTemplate = (code, templateCode) =>
  api.post(`/v1/admin/role-groups/${code}/templates`, { templateCode });

export const removeGroupTemplate = (code, templateCode) =>
  api.delete(`/v1/admin/role-groups/${code}/templates/${templateCode}`);

/* -------------------------------- 主体绑定 -------------------------------- */

export const listBindingsByUser = (userId) =>
  api.get('/v1/admin/principal-bindings', { params: { userId } });

export const listBindingsByPrincipal = (principalType, principalId) =>
  api.get('/v1/admin/principal-bindings/principal', { params: { principalType, principalId } });

export const bindPrincipal = (body) => api.post('/v1/admin/principal-bindings', body);

export const unbindPrincipal = (userId, principalType) =>
  api.delete('/v1/admin/principal-bindings', { params: { userId, principalType } });

/* ------------------------------ 商家入驻骨架 ------------------------------ */

export const listMerchants = () => api.get('/v1/admin/merchants');

export const createMerchant = (body) => api.post('/v1/admin/merchants', body);

export const updateMerchant = (id, body) => api.put(`/v1/admin/merchants/${id}`, body);

export const deleteMerchant = (id) => api.delete(`/v1/admin/merchants/${id}`);

export const listMerchantZones = (merchantId) => api.get(`/v1/admin/merchants/${merchantId}/zones`);

export const createMerchantZone = (merchantId, body) =>
  api.post(`/v1/admin/merchants/${merchantId}/zones`, body);

export const listMerchantBooths = (zoneId) => api.get(`/v1/admin/merchants/zones/${zoneId}/booths`);

export const createMerchantBooth = (zoneId, body) =>
  api.post(`/v1/admin/merchants/zones/${zoneId}/booths`, body);

export default {
  listManufacturers,
  listProducts,
  listStations,
  listPermissionCatalog,
  flattenPermissionCatalog,
  listProductionTasks,
  getProductionTask,
  createProductionTask,
  completeProductionTask,
  getCertificateByDevice,
  reprintCertificate,
  listInventory,
  getInventoryByDevice,
  shipToStation,
  listTransfers,
  getTransfer,
  createTransfer,
  handoverTransfer,
  receiveTransfer,
  listFulfillmentOrders,
  getFulfillmentOrder,
  createFulfillmentOrder,
  payFulfillmentOrder,
  confirmFulfillmentOrder,
  shipFulfillmentOrder,
  receiveFulfillmentOrder,
  pickupFulfillmentOrder,
  cancelFulfillmentOrder,
  expireFulfillmentOrder,
  listCommissionRules,
  createCommissionRule,
  updateCommissionRule,
  deleteCommissionRule,
  listRecoveryOrders,
  createRecoveryOrder,
  confirmRecoveryOrder,
  listRoleTemplates,
  createRoleTemplate,
  updateRoleTemplate,
  listTemplatePermissions,
  setTemplatePermissions,
  listRoleGroups,
  createRoleGroup,
  updateRoleGroup,
  deleteRoleGroup,
  listGroupTemplates,
  addGroupTemplate,
  removeGroupTemplate,
  listBindingsByUser,
  listBindingsByPrincipal,
  bindPrincipal,
  unbindPrincipal,
  listMerchants,
  createMerchant,
  updateMerchant,
  deleteMerchant,
  listMerchantZones,
  createMerchantZone,
  listMerchantBooths,
  createMerchantBooth,
};
