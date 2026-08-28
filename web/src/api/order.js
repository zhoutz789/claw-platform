// 客户订单 / 逐台登记 / 主部件更换 接口封装（接真实后端，对应 V38 增量）。
import api from '../api.js';

// 创建客户订单（支持多 SKU 下单：lines=[{skuId,assetType,quantity,unitPrice}]）
export function createCustomerOrder(req) {
  return api.post('/v1/admin/customer-orders', req);
}

// 支付（冻押金 + 发事件；历史单资产订单在此建产权）
export function payCustomerOrder(id, req) {
  return api.post(`/v1/admin/customer-orders/${id}/pay`, req);
}

// 选择使用模式
export function chooseMode(id, req) {
  return api.post(`/v1/admin/customer-orders/${id}/choose-mode`, req);
}

// 订单列表
export function listCustomerOrders(params) {
  return api.get('/v1/admin/customer-orders', { params });
}

// 订单项列表
export function listOrderItems(id) {
  return api.get(`/v1/admin/customer-orders/${id}/items`);
}

// 批量登记某订单项的 N 台设备（生成资产 + 入运营闭环）
// batch = { units: [{ qrCode, vin, frameNo, motorNo, serialNumber, componentNosJson, manufacturerId, productId, remoteId, model, capacityKwh }] }
export function registerUnits(orderId, itemId, batch) {
  return api.post(`/v1/admin/customer-orders/${orderId}/items/${itemId}/registrations`, batch);
}

// 登记进度（各 SKU N/total + 资产列表 + 是否可发货）
export function getRegistrationProgress(orderId) {
  return api.get(`/v1/admin/customer-orders/${orderId}/registrations`);
}

// 发货前纠错：删除登记行（资产回滚 RETIRED）
export function deleteRegistration(orderId, regId) {
  return api.delete(`/v1/admin/customer-orders/${orderId}/registrations/${regId}`);
}

// 发货（登记守卫：每项登记齐全才放行）
export function shipOrder(orderId) {
  return api.post(`/v1/admin/customer-orders/${orderId}/ship`);
}

// 主部件更换留痕（F7.4 / F16.5）
// req = { componentType, oldComponentNo, newComponentNo }
export function replaceComponent(assetId, req) {
  return api.post(`/v1/assets/${assetId}/components/replace`, req);
}
