// 缺口⑥「我的容量预订」+ 缺口②「服务站合约管理」前端接口封装。
//
// 全部对接 claw-platform/backend 的真实 Controller：
//   CapacityPlanAdminController   /api/v1/admin/capacity/plans
//   CapacitySubscribeController    /api/v1/capacity/subscribe | /subscriptions | /plans/open | /rebates
//   StationContractAdminController /api/v1/admin/station-contracts/*
// 风格与 station.js / supplyChain.js 保持一致：直接基于 src/api.js 的 axios 实例。
import api from '../api.js';

/* ----------------------- 缺口⑥ · 容量预订（Capacity） ----------------------- */

/** 创建容量计划（资产入池后对外预售容量）。 */
export const createPlan = (body) => api.post('/v1/admin/capacity/plans', body);

/** 按业主用户查询其名下的容量计划列表。 */
export const listPlans = (ownerUserId) =>
  api.get('/v1/admin/capacity/plans?ownerUserId=' + ownerUserId);

/** 订阅（定购）某一容量计划的单位数。 */
export const subscribe = (body) => api.post('/v1/capacity/subscribe', body);

/** 按订阅用户查询其预订记录。 */
export const listSubscriptions = (subscriberUserId) =>
  api.get('/v1/capacity/subscriptions?subscriberUserId=' + subscriberUserId);

/** 查询某资产对外开放的可订阅容量计划（用于看板进度关联）。 */
export const listOpenPlans = (assetId) =>
  api.get('/v1/capacity/plans/open?assetId=' + assetId);

/** 按订阅用户查询其回佣结算记录。 */
export const listRebates = (subscriberUserId) =>
  api.get('/v1/capacity/rebates?subscriberUserId=' + subscriberUserId);

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
  listPlans,
  subscribe,
  listSubscriptions,
  listOpenPlans,
  listRebates,
  listStationContracts,
  exitContract,
  refundContract,
  listPendingRefunds,
};
