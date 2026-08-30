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

// ==================== 增量 B · 库存 / 流转 / 渠道域 ====================
// 以下枚举与后端 com.claw.server.common.enums 一一对应（V47–V52）。

/** 库存货权类型：自有（厂家）/ 寄售（服务站占有）/ 买断。 */
export const OWNERSHIP_TYPE = opts(['OWNED_BY_MFG', 'CONSIGNED', 'FULL']);
export const OWNERSHIP_TYPE_LABEL = {
  OWNED_BY_MFG: 'supply:enum.ownership.OWNED_BY_MFG',
  CONSIGNED: 'supply:enum.ownership.CONSIGNED',
  FULL: 'supply:enum.ownership.FULL',
};

/** 设备生命周期 7 状态（R4，与 LifecycleStatus 一致）。 */
export const DEVICE_LIFECYCLE_STATUS = opts([
  'PRODUCING', 'IN_FACTORY', 'IN_TRANSIT', 'AT_STATION', 'SOLD', 'IN_USER_PROJECT', 'RECALLED',
]);
export const DEVICE_LIFECYCLE_LABEL = {
  PRODUCING: 'supply:enum.lifecycle.PRODUCING',
  IN_FACTORY: 'supply:enum.lifecycle.IN_FACTORY',
  IN_TRANSIT: 'supply:enum.lifecycle.IN_TRANSIT',
  AT_STATION: 'supply:enum.lifecycle.AT_STATION',
  SOLD: 'supply:enum.lifecycle.SOLD',
  IN_USER_PROJECT: 'supply:enum.lifecycle.IN_USER_PROJECT',
  RECALLED: 'supply:enum.lifecycle.RECALLED',
};

/** 履约订单状态（R6 状态链）。 */
export const FULFILLMENT_STATUS = opts([
  'PENDING_PAYMENT', 'PAID_FROZEN', 'CONFIRMED', 'SHIPPED', 'RECEIVED', 'PICKED_UP', 'SETTLED',
  'CANCELLED', 'EXPIRED',
]);
export const FULFILLMENT_STATUS_LABEL = {
  PENDING_PAYMENT: 'supply:enum.fulfillment.PENDING_PAYMENT',
  PAID_FROZEN: 'supply:enum.fulfillment.PAID_FROZEN',
  CONFIRMED: 'supply:enum.fulfillment.CONFIRMED',
  SHIPPED: 'supply:enum.fulfillment.SHIPPED',
  RECEIVED: 'supply:enum.fulfillment.RECEIVED',
  PICKED_UP: 'supply:enum.fulfillment.PICKED_UP',
  SETTLED: 'supply:enum.fulfillment.SETTLED',
  CANCELLED: 'supply:enum.fulfillment.CANCELLED',
  EXPIRED: 'supply:enum.fulfillment.EXPIRED',
};
/** 履约状态 → Tag 颜色（与运营看板视觉一致）。 */
export const FULFILLMENT_STATUS_COLOR = {
  PENDING_PAYMENT: 'default',
  PAID_FROZEN: 'orange',
  CONFIRMED: 'blue',
  SHIPPED: 'cyan',
  RECEIVED: 'geekblue',
  PICKED_UP: 'purple',
  SETTLED: 'green',
  CANCELLED: 'red',
  EXPIRED: 'volcano',
};

/** 调拨单状态（R5）。 */
export const TRANSFER_STATUS = opts(['DRAFT', 'CREATED', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED']);
export const TRANSFER_STATUS_LABEL = {
  DRAFT: 'supply:enum.transfer.DRAFT',
  CREATED: 'supply:enum.transfer.CREATED',
  IN_TRANSIT: 'supply:enum.transfer.IN_TRANSIT',
  COMPLETED: 'supply:enum.transfer.COMPLETED',
  CANCELLED: 'supply:enum.transfer.CANCELLED',
};
export const TRANSFER_STATUS_COLOR = {
  DRAFT: 'default',
  CREATED: 'blue',
  IN_TRANSIT: 'orange',
  COMPLETED: 'green',
  CANCELLED: 'red',
};

/** 提成规则类型（R7，与 device_sales_commission_rules.commission_type 一致）。 */
export const COMMISSION_TYPE = opts(['RATE', 'AMOUNT']);
export const COMMISSION_TYPE_LABEL = {
  RATE: 'supply:enum.commissionType.RATE',
  AMOUNT: 'supply:enum.commissionType.AMOUNT',
};

/** 回收单状态（R8）。 */
export const RECOVERY_STATUS = opts(['PENDING', 'CONFIRMED', 'MARKED', 'RETURNED', 'CANCELLED']);
export const RECOVERY_STATUS_LABEL = {
  PENDING: 'supply:enum.recovery.PENDING',
  CONFIRMED: 'supply:enum.recovery.CONFIRMED',
  MARKED: 'supply:enum.recovery.MARKED',
  RETURNED: 'supply:enum.recovery.RETURNED',
  CANCELLED: 'supply:enum.recovery.CANCELLED',
};

/** 回收原因（R8，Q1 起算点相关）。 */
export const RECOVERY_REASON = opts(['UNSOLD_TIMEOUT', 'FULFILL_TIMEOUT', 'MANUAL']);
export const RECOVERY_REASON_LABEL = {
  UNSOLD_TIMEOUT: 'supply:enum.recoveryReason.UNSOLD_TIMEOUT',
  FULFILL_TIMEOUT: 'supply:enum.recoveryReason.FULFILL_TIMEOUT',
  MANUAL: 'supply:enum.recoveryReason.MANUAL',
};

/** 回收触发方式。 */
export const RECOVERY_TRIGGER = opts(['AUTO', 'MANUAL']);

// ==================== 增量 A · 权限骨架 ====================

/** 主体类型（4 个业务角色模板，与 role_templates.principal_type 一致）。 */
export const PRINCIPAL_TYPE = opts(['MANUFACTURER', 'STATION', 'CUSTOMER', 'PLATFORM_ADMIN']);
export const PRINCIPAL_TYPE_LABEL = {
  MANUFACTURER: 'supply:enum.principalType.MANUFACTURER',
  STATION: 'supply:enum.principalType.STATION',
  CUSTOMER: 'supply:enum.principalType.CUSTOMER',
  PLATFORM_ADMIN: 'supply:enum.principalType.PLATFORM_ADMIN',
};

/** 主体绑定类型（principal_bindings 目前仅厂家 / 服务站两类，Q5 严格 1:1）。 */
export const BINDING_PRINCIPAL_TYPE = opts(['MANUFACTURER', 'STATION']);

/** 生产任务状态。 */
export const PRODUCTION_TASK_STATUS = opts(['CREATED', 'PRODUCING', 'COMPLETED', 'CANCELLED']);

/** 商家状态（Phase 2 骨架，与 merchants.status 一致）。 */
export const MERCHANT_STATUS = opts(['PENDING', 'ACTIVE', 'SUSPENDED', 'TERMINATED']);
/** 区块状态。 */
export const MERCHANT_ZONE_STATUS = opts(['OPEN', 'CLOSED']);
/** 铺位状态。 */
export const MERCHANT_BOOTH_STATUS = opts(['AVAILABLE', 'LEASED', 'DISABLED']);
