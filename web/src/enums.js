// 各业务枚举的可选项（与后端 com.claw.server.common.enums 保持一致）。
const opts = (arr) => arr.map((v) => ({ label: v, value: v }));

export const RISK_METRIC_TYPE = opts(['BOND_SHORTFALL', 'ASSET_MISMATCH', 'RECONCILIATION_FAIL', 'COMPLAINT_SPIKE', 'TRANSACTION_ANOMALY', 'OFF_HOURS_ACTIVITY']);
export const RISK_MONITOR_STATUS = opts(['NORMAL', 'WARNING', 'CRITICAL', 'CIRCUIT_BREAK']);
export const RISK_EVENT_TYPE = opts(['BOND_SHORTFALL', 'ASSET_MISSING', 'RECONCILIATION_FAIL', 'COMPLAINT_SPIKE', 'UNUSUAL_TRANSACTION']);
export const RISK_SEVERITY = opts(['LOW', 'MEDIUM', 'HIGH']);
export const AUTO_ACTION = opts(['NONE', 'ALERT_ONLY', 'FREEZE_ACCOUNT']);
export const FUND_STATUS = opts(['HEALTHY', 'LOW', 'CRITICAL']);
export const TRANSFER_TYPE = opts(['SWAP_EXCHANGE', 'RENTAL_START', 'RENTAL_END', 'SHARED_POOL_ENTRY', 'SHARED_POOL_EXIT', 'RECOVERY', 'TRADE_IN', 'INITIAL_PURCHASE']);
export const DISPUTE_TYPE = opts(['OWNERSHIP_DISPUTE', 'DAMAGE_CLAIM', 'MISSING_ASSET', 'UNAUTHORIZED_TRANSFER', 'FEE_DISPUTE']);
export const SETTLEMENT_STATUS = opts(['PENDING', 'SETTLED']);
export const REVENUE_SHARE_BASIS = opts(['PER_SWAP', 'PER_DAY']);
export const RENTAL_ORDER_STATUS = opts(['CREATED', 'ACTIVE', 'COMPLETED', 'CANCELLED']);
export const ASSET_TYPE = opts(['VEHICLE', 'BATTERY', 'CHARGER', 'PV_STATION']);
export const SWAP_STATUS = opts(['CREATED', 'FROZEN', 'SWAPPING', 'SETTLED', 'COMPLETED', 'EXCEPTION', 'CANCELLED']);
export const YES_NO = opts([{ label: '是', value: true }, { label: '否', value: false }]);
export const BOOL_STR = opts([{ label: 'true', value: 'true' }, { label: 'false', value: 'false' }]);

// 资产生命周期阶段（生产/流通/使用/维修/回收/销毁）
export const ASSET_LIFECYCLE_STAGE = opts(['PRODUCED', 'IN_TRANSIT', 'IN_USE', 'MAINTENANCE', 'RECYCLED', 'DESTROYED']);
export const LIFECYCLE_LABEL = {
  PRODUCED: '生产出厂', IN_TRANSIT: '流通在途', IN_USE: '使用中', MAINTENANCE: '维修保养', RECYCLED: '回收', DESTROYED: '销毁',
};
// 资产运营类型（客运/物流/流动售卖/广告/录像）
export const VEHICLE_OP_TYPE = opts(['PASSENGER', 'LOGISTICS', 'MOBILE_SELL', 'ADVERTISING', 'RECORDING']);
export const OP_LABEL = { PASSENGER: '客运', LOGISTICS: '物流', MOBILE_SELL: '流动售卖', ADVERTISING: '广告', RECORDING: '录像数据' };
// 角色数据范围
export const DATA_SCOPE = opts(['SELF', 'DEPARTMENT', 'ALL', 'TYPE']);
export const DATA_SCOPE_LABEL = { SELF: '仅本人数据', DEPARTMENT: '本部门', ALL: '全部数据', TYPE: '特殊授权类型' };
// 运营状态标签
export const ASSET_STATUS_LABEL = {
  IN_STOCK: '在库', IN_USE: '使用中', SHARED: '共享中', REPAIR: '维修中', DISABLED: '停用', SCRAPPED: '报废',
};
// 采购状态
export const PURCHASE_STATUS = opts(['CREATED', 'PAID', 'SHIPPED', 'CANCELLED']);
// 商品状态
export const PRODUCT_STATUS = opts(['ON_SALE', 'OFF_SHELF', 'PREPARE']);
export const SKU_STATUS = opts(['ACTIVE', 'INACTIVE']);
export const MANUFACTURER_STATUS = opts(['ACTIVE', 'INACTIVE']);
