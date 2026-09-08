// 缺口⑥「我的容量预订」+ 缺口②「服务站合约管理」前端接口封装。
//
// 全部对接 claw-platform/backend 的真实 Controller：
//   AdminCapacityController         /api/v1/admin/capacity/plans
//   CapacityController              /api/v1/capacity/subscribe | /subscriptions | /rebates
//   AdminStationContractController  /api/v1/admin/station-contracts/*
// 风格与 station.js / supplyChain.js 保持一致：直接基于 src/api.js 的 axios 实例。
import api from '../api.js';

/* ----------------------- 缺口⑥ · 容量预订（Capacity） ----------------------- */

/** 创建容量计划（资产入池后对外预售容量）。 */
export const createPlan = (body) => api.post('/v1/admin/capacity/plans', body);

/**
 * 查询「我发布的」容量计划列表（无参）。
 * 后端 GET /v1/admin/capacity/plans：productId 为可选参数，不传即按当前登录用户返回其发布的计划。
 * 注意：后端早已不支持 ownerUserId 入参，前端一律不要拼任何查询条件。
 */
export const listMyPlans = () => api.get('/v1/admin/capacity/plans');

/**
 * V81：按商品查询容量计划（「商品列表 → 容量预定」按钮点开即查）。
 * 后端 GET /v1/admin/capacity/plans?productId=，返回 List，前端取第一条展示。
 */
export const listPlansByProduct = (productId) =>
  api.get('/v1/admin/capacity/plans?productId=' + encodeURIComponent(productId));

/**
 * V81：查询某容量计划下的预定订单（抽屉内只读表格用）。
 * 后端 GET /v1/admin/capacity/plans/{id}/subscriptions。
 */
export const listPlanSubscriptions = (planId) =>
  api.get('/v1/admin/capacity/plans/' + encodeURIComponent(planId) + '/subscriptions');

/** 订阅（定购）某一容量计划的单位数。 */
export const subscribe = (body) => api.post('/v1/capacity/subscribe', body);

/**
 * 查询当前登录用户的预订记录（无参）。
 * 后端 GET /v1/capacity/subscriptions：订户由登录态带出，前端不得传 subscriberUserId。
 */
export const listSubscriptions = () => api.get('/v1/capacity/subscriptions');

/**
 * 查询当前登录用户的回佣结算记录（无参）。
 * 后端 GET /v1/capacity/rebates：同 subscriptions，由登录态带出。
 */
export const listRebates = () => api.get('/v1/capacity/rebates');

/* ----------------------- 缺口② · 服务站合约（Station Contract） ----------------------- */

/** 按站点查询其名下合约列表。 */
export const listStationContracts = (stationId) =>
  api.get('/v1/admin/station-contracts?stationId=' + stationId);

/** 申请合约退出（body 内可选 remark）。 */
export const exitContract = (contractId, remark) =>
  api.post('/v1/admin/station-contracts/' + contractId + '/exit', remark ? { remark } : {});

/** 保证金清算：refunded=是否全额退款，refundAmount=清算金额。 */
export const refundContract = (contractId, refunded, refundAmount) =>
  api.post(
    '/v1/admin/station-contracts/' +
      contractId +
      '/refund?refunded=' +
      refunded +
      '&refundAmount=' +
      refundAmount
  );

/** 退款看板：所有 EXIT_REQUESTED 待退款合约。 */
export const listPendingRefunds = () => api.get('/v1/admin/station-contracts/pending-refunds');

export default {
  createPlan,
  listMyPlans,
  listPlansByProduct,
  listPlanSubscriptions,
  subscribe,
  listSubscriptions,
  listRebates,
  listStationContracts,
  exitContract,
  refundContract,
  listPendingRefunds,
};
