// 各业务枚举的可选项（与后端 com.claw.server.common.enums 保持一致）。
//
// i18n 说明：*_LABEL 系列现在存放的是 i18n key（而非中文原文）。
// 取显示文案请统一用 enumLabel(映射, 值)，它会经 i18next 翻译并自带「取不到就显示原值」的兜底，
// 保证任何语言下都不会把 key 漏到界面上。
import { tv } from './i18n';

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

/**
 * 取枚举值的本地化显示文案。
 *
 * @param {Object} keyMap i18n key 映射（如 DATA_SCOPE_LABEL）
 * @param {*} value 枚举值
 * @returns {*} 本地化文案；映射中不存在或翻译缺失时原样返回枚举值
 */
export const enumLabel = (keyMap, value) => {
  const key = keyMap && keyMap[value];
  if (!key) return value;
  return tv(key, { defaultValue: value }) || value;
};

// 资产生命周期阶段（生产/流通/使用/维修/回收/销毁）
export const ASSET_LIFECYCLE_STAGE = opts(['PRODUCED', 'IN_TRANSIT', 'IN_USE', 'MAINTENANCE', 'RECYCLED', 'DESTROYED']);
export const LIFECYCLE_LABEL = {
  PRODUCED: 'common:enum.lifecycle.PRODUCED',
  IN_TRANSIT: 'common:enum.lifecycle.IN_TRANSIT',
  IN_USE: 'common:enum.lifecycle.IN_USE',
  MAINTENANCE: 'common:enum.lifecycle.MAINTENANCE',
  RECYCLED: 'common:enum.lifecycle.RECYCLED',
  DESTROYED: 'common:enum.lifecycle.DESTROYED',
};
// 资产运营类型（客运/物流/流动售卖/广告/录像）
export const VEHICLE_OP_TYPE = opts(['PASSENGER', 'LOGISTICS', 'MOBILE_SELL', 'ADVERTISING', 'RECORDING']);
export const OP_LABEL = {
  PASSENGER: 'common:enum.opType.PASSENGER',
  LOGISTICS: 'common:enum.opType.LOGISTICS',
  MOBILE_SELL: 'common:enum.opType.MOBILE_SELL',
  ADVERTISING: 'common:enum.opType.ADVERTISING',
  RECORDING: 'common:enum.opType.RECORDING',
};
// 角色数据范围（与后端角色表 data_scope 列及 T03 扩展契约一致）。
// 取值：SELF 仅本人 / DEPARTMENT 本部门 / DEPARTMENT_AND_BELOW 本部门及下属 /
//       CUSTOM 自定义（指定部门/规则）/ TYPE 按业务类型 / ALL 全部。
export const DATA_SCOPE = opts(['SELF', 'DEPARTMENT', 'DEPARTMENT_AND_BELOW', 'CUSTOM', 'TYPE', 'ALL']);
export const DATA_SCOPE_LABEL = {
  SELF: 'common:enum.dataScope.SELF',
  DEPARTMENT: 'common:enum.dataScope.DEPARTMENT',
  DEPARTMENT_AND_BELOW: 'common:enum.dataScope.DEPARTMENT_AND_BELOW',
  CUSTOM: 'common:enum.dataScope.CUSTOM',
  TYPE: 'common:enum.dataScope.TYPE',
  ALL: 'common:enum.dataScope.ALL',
};
// 特殊授权类型（TYPE 数据范围可看的业务类型集合）。
// token 与 V40 权限资源码一致，便于后端按资源做数据过滤。
export const DATA_SCOPE_TYPE_OPTIONS = [
  'asset', 'customer-order', 'order', 'swap-order', 'station', 'custody', 'recovery',
  'manufacturer', 'product', 'sku', 'iot', 'fee', 'insurance', 'operator', 'risk',
  'settlement', 'profit', 'ledger', 'payment', 'deposit', 'reconciliation',
  'user', 'role', 'permission', 'department', 'setting', 'shared-pool', 'project', 'dashboard',
].map((v) => ({ label: v, value: v }));
// 运营状态标签
export const ASSET_STATUS_LABEL = {
  IN_STOCK: 'common:enum.assetStatus.IN_STOCK',
  IN_USE: 'common:enum.assetStatus.IN_USE',
  SHARED: 'common:enum.assetStatus.SHARED',
  REPAIR: 'common:enum.assetStatus.REPAIR',
  DISABLED: 'common:enum.assetStatus.DISABLED',
  SCRAPPED: 'common:enum.assetStatus.SCRAPPED',
};
// 采购状态
export const PURCHASE_STATUS = opts(['CREATED', 'PAID', 'SHIPPED', 'CANCELLED']);
// 商品状态
export const PRODUCT_STATUS = opts(['ON_SALE', 'OFF_SHELF', 'PREPARE']);
export const SKU_STATUS = opts(['ACTIVE', 'INACTIVE']);
export const MANUFACTURER_STATUS = opts(['ACTIVE', 'INACTIVE']);
