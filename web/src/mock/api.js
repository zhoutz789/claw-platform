// 演示数据兜底层（Option A：前端 mock 兜底）
// 作用：后端不可达 / 鉴权失败(403) / 任意非 2xx 时，按「请求方法 + 路径」回落到内置演示数据，
// 保证管理后台全部页面都能看到真实业务闭环。后端返回 2xx 时仍走真实数据，二者无缝切换。
//
// 数据主题：柬埔寨 · 金边（Phnom Penh）首发站，新能源资产（光伏 / 电池 / 车辆 / 无人机低空经济）。

import { TEMPLATES, PRODUCTS, BRANDS, DEVICES } from '../mockData';

const d = (s) => (s ? `${s}T08:00:00` : null);
const iso = (s) => (s ? `${s} 08:00:00` : null);
const ts = () => new Date().toISOString().slice(0, 19).replace('T', ' ');

/* ----------------------------- 数据集 ----------------------------- */

const stations = [
  { id: 1, code: 'PP-CENTRAL', name: '金边·中央换电站', area: '金边', city: 'Phnom Penh', openHours: '06:00-22:00', distKm: 0.0, totalStock: 120, categories: ['SWAP-BAT', 'EV-BIKE'] },
  { id: 2, code: 'PP-RUSSIA', name: '金边·俄罗斯市场站', area: '金边', city: 'Phnom Penh', openHours: '07:00-21:00', distKm: 1.2, totalStock: 86, categories: ['SWAP-BAT'] },
  { id: 3, code: 'PP-OLYMPIC', name: '金边·奥运市场站', area: '金边', city: 'Phnom Penh', openHours: '06:30-22:30', distKm: 2.4, totalStock: 64, categories: ['SWAP-BAT', 'EV-BIKE'] },
  { id: 4, code: 'PP-AIRPORT', name: '金边·机场快充站', area: '金边', city: 'Phnom Penh', openHours: '00:00-24:00', distKm: 9.8, totalStock: 40, categories: ['SWAP-BAT'] },
  { id: 5, code: 'PP-TAKMAO', name: '干丹·达克茂站', area: '干丹', city: 'Takeo', openHours: '07:00-20:00', distKm: 21.5, totalStock: 32, categories: ['SWAP-BAT', 'EV-BIKE'] },
];

const assets = [
  { id: 1001, assetType: 'VEHICLE', assetNo: 'EV-2026-000001', serialNumber: 'SN-EV-000001', qrCode: 'QR-EV-000001', ownerId: 1005, userId: 1005, status: 'IN_USE', createdAt: iso('2026-02-01') },
  { id: 1002, assetType: 'VEHICLE', assetNo: 'EV-2026-000002', serialNumber: 'SN-EV-000002', qrCode: 'QR-EV-000002', ownerId: 1006, userId: 1006, status: 'SHARED', createdAt: iso('2026-02-10') },
  { id: 2001, assetType: 'BATTERY', assetNo: 'BAT-2026-000001', serialNumber: 'SN-BAT-000001', qrCode: 'QR-BAT-000001', ownerId: 1005, userId: 1005, status: 'IN_USE', createdAt: iso('2026-03-05') },
  { id: 2002, assetType: 'BATTERY', assetNo: 'BAT-2026-000002', serialNumber: 'SN-BAT-000002', qrCode: 'QR-BAT-000002', ownerId: 1007, userId: 1007, status: 'SHARED', createdAt: iso('2026-03-12') },
  { id: 2003, assetType: 'BATTERY', assetNo: 'BAT-2026-000003', serialNumber: 'SN-BAT-000003', qrCode: 'QR-BAT-000003', ownerId: 1007, userId: 1007, status: 'IN_STOCK', createdAt: iso('2026-03-20') },
  { id: 3001, assetType: 'DRONE', assetNo: 'DRN-2026-000001', serialNumber: 'SN-DRN-000001', qrCode: 'QR-DRN-000001', ownerId: 1005, userId: 1005, status: 'IN_USE', createdAt: iso('2026-01-20') },
  { id: 4001, assetType: 'PV_STATION', assetNo: 'PV-2026-000001', serialNumber: 'SN-PV-000001', qrCode: 'QR-PV-000001', ownerId: 1006, userId: 1006, status: 'IN_USE', createdAt: iso('2026-04-01') },
  { id: 1003, assetType: 'VEHICLE', assetNo: 'EV-2026-000003', serialNumber: 'SN-EV-000003', qrCode: 'QR-EV-000003', ownerId: 1006, userId: 1006, status: 'REPAIR', createdAt: iso('2026-02-18') },
  { id: 2004, assetType: 'BATTERY', assetNo: 'BAT-2026-000004', serialNumber: 'SN-BAT-000004', qrCode: 'QR-BAT-000004', ownerId: 1005, userId: 1005, status: 'DISABLED', createdAt: iso('2026-03-22') },
  { id: 3002, assetType: 'DRONE', assetNo: 'DRN-2026-000002', serialNumber: 'SN-DRN-000002', qrCode: 'QR-DRN-000002', ownerId: 1006, userId: 1006, status: 'IN_USE', createdAt: iso('2026-02-02') },
  { id: 1004, assetType: 'VEHICLE', assetNo: 'EV-2026-000004', serialNumber: 'SN-EV-000004', qrCode: 'QR-EV-000004', ownerId: 1007, userId: 1007, status: 'SHARED', createdAt: iso('2026-03-01') },
  { id: 2005, assetType: 'BATTERY', assetNo: 'BAT-2026-000005', serialNumber: 'SN-BAT-000005', qrCode: 'QR-BAT-000005', ownerId: 1007, userId: 1007, status: 'IN_STOCK', createdAt: iso('2026-04-10') },
];

const users = [
  { id: 1001, phone: '85512300001', fullName: '小敏', kycStatus: 'VERIFIED', status: 'ACTIVE', locale: 'km', departmentId: 3, roles: ['CONSUMER'] },
  { id: 1002, phone: '85512300002', fullName: '阿强', kycStatus: 'VERIFIED', status: 'ACTIVE', locale: 'zh', departmentId: 3, roles: ['CONSUMER'] },
  { id: 1003, phone: '85512300003', fullName: 'Sopheak', kycStatus: 'PENDING', status: 'ACTIVE', locale: 'en', departmentId: 3, roles: ['CONSUMER'] },
  { id: 1004, phone: '85512300004', fullName: '站长-王', kycStatus: 'VERIFIED', status: 'ACTIVE', locale: 'zh', departmentId: 2, roles: ['STATION_MANAGER'] },
  { id: 1005, phone: '85512300005', fullName: '李工(资产主)', kycStatus: 'VERIFIED', status: 'ACTIVE', locale: 'zh', departmentId: 1, roles: ['ASSET_OWNER', 'BRAND_ADMIN'] },
  { id: 1006, phone: '85512300006', fullName: '陈工(资产主)', kycStatus: 'VERIFIED', status: 'ACTIVE', locale: 'zh', departmentId: 1, roles: ['ASSET_OWNER'] },
  { id: 1007, phone: '85512300007', fullName: '站长-林', kycStatus: 'VERIFIED', status: 'ACTIVE', locale: 'km', departmentId: 2, roles: ['STATION_MANAGER'] },
  { id: 1, phone: '85510000001', fullName: '周老板(超级管理员)', kycStatus: 'VERIFIED', status: 'ACTIVE', locale: 'zh', departmentId: 1, roles: ['SUPER_ADMIN'] },
];

const roles = [
  { id: 1, roleCode: 'SUPER_ADMIN', nameI18n: 'role.super_admin', name: '超级管理员', dataScope: 'ALL', grants: '["*:*"]', autoGrant: false, grantRule: '', status: 'ACTIVE' },
  { id: 2, roleCode: 'BRAND_ADMIN', nameI18n: 'role.brand_admin', name: '品牌方管理员', dataScope: 'TYPE', grants: '["brand:read","brand:write","product:*"]', autoGrant: false, grantRule: '', status: 'ACTIVE' },
  { id: 3, roleCode: 'STATION_MANAGER', nameI18n: 'role.station_manager', name: '服务站站长', dataScope: 'DEPARTMENT', grants: '["station:read","swap:write","rental:write"]', autoGrant: false, grantRule: '', status: 'ACTIVE' },
  { id: 4, roleCode: 'ASSET_OWNER', nameI18n: 'role.asset_owner', name: '资产所有人', dataScope: 'SELF', grants: '["asset:read","shared-pool:write"]', autoGrant: true, grantRule: '注册即授予', status: 'ACTIVE' },
  { id: 5, roleCode: 'CONSUMER', nameI18n: 'role.consumer', name: '普通用户', dataScope: 'SELF', grants: '["swap:read","rental:read"]', autoGrant: true, grantRule: '注册即授予', status: 'ACTIVE' },
];

const userRoles = [
  { id: 1, userId: 1, userName: '周老板', roleCode: 'SUPER_ADMIN', roleName: '超级管理员', source: 'SEED', grantedAt: iso('2026-01-01') },
  { id: 2, userId: 1005, userName: '李工', roleCode: 'BRAND_ADMIN', roleName: '品牌方管理员', source: 'MANUAL', grantedAt: iso('2026-02-01') },
  { id: 3, userId: 1004, userName: '站长-王', roleCode: 'STATION_MANAGER', roleName: '服务站站长', source: 'MANUAL', grantedAt: iso('2026-02-15') },
  { id: 4, userId: 1001, userName: '小敏', roleCode: 'CONSUMER', roleName: '普通用户', source: 'AUTO', grantedAt: iso('2026-03-10') },
];

const departments = [
  { id: 1, name: '平台运营中心' },
  { id: 2, name: '金边区域驿站' },
  { id: 3, name: '个人用户组' },
];

const reconciliation = {
  runDate: '2026-08-25',
  status: 'MATCHED',
  platformTotal: 184520.36,
  bankTotal: 184520.36,
  matchedCount: 1284,
  mismatchCount: 0,
  createdAt: iso('2026-08-25'),
};

const elecPrices = [
  { id: 1, effectiveDate: '2026-08-01', pvPrice: 0.062, gridPrice: 0.118 },
  { id: 2, effectiveDate: '2026-07-01', pvPrice: 0.058, gridPrice: 0.121 },
];

const feeRules = [
  { id: 1, ruleCode: 'SWAP_BASIC', name: '基础换电服务费', unit: '次', price: 0.6, shareJson: '{"station":0.40,"platform":0.20}', effectiveFrom: '2026-02-01', effectiveTo: null, status: 'ACTIVE' },
  { id: 2, ruleCode: 'GRID_ELEC', name: '电网补电电费', unit: 'kWh', price: 0.118, shareJson: '{"grid":1.00}', effectiveFrom: '2026-02-01', effectiveTo: null, status: 'ACTIVE' },
  { id: 3, ruleCode: 'SHARED_DAILY', name: '共享池日使用费', unit: '天', price: 2.5, shareJson: '{"owner":0.70,"station":0.15,"platform":0.10,"insurance":0.05}', effectiveFrom: '2026-03-01', effectiveTo: null, status: 'ACTIVE' },
];

const complaints = [
  { complaintNo: 'C20260801', userId: 1001, channel: 'APP', subject: '换电站电池未满电', status: 'OPEN', createdAt: iso('2026-08-24') },
  { complaintNo: 'C20260802', userId: 1003, channel: 'APP', subject: '押金退还延迟', status: 'OPEN', createdAt: iso('2026-08-25') },
  { complaintNo: 'C20260803', userId: 1002, channel: 'HOTLINE', subject: '共享车辆定位偏差', status: 'OPEN', createdAt: iso('2026-08-25') },
];

const pendingClaims = [
  { id: 1, insuranceId: 1, assetId: 1003, status: 'REPORTED', damageAmount: 320.0 },
  { id: 2, insuranceId: 2, assetId: 2002, status: 'REPORTED', damageAmount: 180.0 },
  { id: 3, insuranceId: 3, assetId: 3001, status: 'ASSESSED', damageAmount: 960.0 },
];

const manufacturers = [
  { id: 1, code: 'KH-DP', name: '金边电科', contact: '李工', country: 'KH', status: 'ACTIVE' },
  { id: 2, code: 'KH-MG', name: '湄公电源', contact: '陈工', country: 'KH', status: 'ACTIVE' },
  { id: 3, code: 'KH-SK', name: '暹粒能源', contact: '周工', country: 'KH', status: 'INACTIVE' },
];

const products = [
  { id: 1, manufacturerId: 1, name: '金边电科 电动两轮车 X1', assetType: 'VEHICLE', model: 'X1', status: 'ON_SALE' },
  { id: 2, manufacturerId: 1, name: '金边电科 共享电池 S2', assetType: 'BATTERY', model: 'S2', status: 'ON_SALE' },
  { id: 3, manufacturerId: 1, name: '金边电科 植保无人机 A6', assetType: 'DRONE', model: 'A6', status: 'ON_SALE' },
  { id: 4, manufacturerId: 2, name: '湄公电源 储能电池 P1', assetType: 'BATTERY', model: 'P1', status: 'ON_SALE' },
  { id: 5, manufacturerId: 3, name: '暹粒能源 光伏组件 V1', assetType: 'PV_STATION', model: 'V1', status: 'PREPARE' },
];

const skus = [
  { id: 1, productId: 1, skuCode: 'EV-X1-STD', price: 3999, currency: 'USD', status: 'ACTIVE' },
  { id: 2, productId: 2, skuCode: 'BAT-S2-STD', price: 1999, currency: 'USD', status: 'ACTIVE' },
  { id: 3, productId: 3, skuCode: 'DRN-A6-STD', price: 2600, currency: 'USD', status: 'ACTIVE' },
  { id: 4, productId: 4, skuCode: 'BAT-P1-STD', price: 1499, currency: 'USD', status: 'ACTIVE' },
  { id: 5, productId: 5, skuCode: 'PV-V1-STD', price: 5200, currency: 'USD', status: 'INACTIVE' },
];

const purchaseOrders = [
  { id: 1, productId: 2, skuId: 2, buyerId: 1007, qty: 20, totalAmount: 39980, currency: 'USD', status: 'SHIPPED' },
  { id: 2, productId: 3, skuId: 3, buyerId: 1005, qty: 5, totalAmount: 13000, currency: 'USD', status: 'PAID' },
  { id: 3, productId: 1, skuId: 1, buyerId: 1001, qty: 2, totalAmount: 7998, currency: 'USD', status: 'COMPLETED' },
  { id: 4, productId: 4, skuId: 4, buyerId: 1006, qty: 10, totalAmount: 14990, currency: 'USD', status: 'CREATED' },
];

const swapOrders = [
  { id: 1, orderNo: 'SW20260825001', userId: 1001, stationId: 1, status: 'COMPLETED', batteryDeposit: 60, estKwh: 1.2, estTotal: 1.32, actualTotal: 1.32, settleStatus: 'SETTLED', createdAt: iso('2026-08-25') },
  { id: 2, orderNo: 'SW20260825002', userId: 1002, stationId: 2, status: 'COMPLETED', batteryDeposit: 60, estKwh: 1.1, estTotal: 1.26, actualTotal: 1.26, settleStatus: 'SETTLED', createdAt: iso('2026-08-25') },
  { id: 3, orderNo: 'SW20260825003', userId: 1003, stationId: 1, status: 'SWAPPING', batteryDeposit: 60, estKwh: 1.3, estTotal: 1.38, actualTotal: null, settleStatus: 'PENDING', createdAt: iso('2026-08-25') },
  { id: 4, orderNo: 'SW20260824001', userId: 1001, stationId: 3, status: 'COMPLETED', batteryDeposit: 60, estKwh: 1.0, estTotal: 1.18, actualTotal: 1.18, settleStatus: 'SETTLED', createdAt: iso('2026-08-24') },
  { id: 5, orderNo: 'SW20260824002', userId: 1002, stationId: 1, status: 'EXCEPTION', batteryDeposit: 60, estKwh: 1.2, estTotal: 1.32, actualTotal: null, settleStatus: 'PENDING', createdAt: iso('2026-08-24') },
];

const swapOrdersPublic = [
  { orderNo: 'SW20260825001', userId: 1001, stationName: '金边·中央换电站', stationId: 1, status: 'COMPLETED', batteryDeposit: 60, estElecFee: 1.2, estServiceFee: 0.6, settleStatus: 'SETTLED', createdAt: iso('2026-08-25') },
  { orderNo: 'SW20260825003', userId: 1003, stationName: '金边·中央换电站', stationId: 1, status: 'SWAPPING', batteryDeposit: 60, estElecFee: 1.3, estServiceFee: 0.6, settleStatus: 'PENDING', createdAt: iso('2026-08-25') },
  { orderNo: 'SW20260824001', userId: 1001, stationName: '金边·奥运市场站', stationId: 3, status: 'COMPLETED', batteryDeposit: 60, estElecFee: 1.0, estServiceFee: 0.6, settleStatus: 'SETTLED', createdAt: iso('2026-08-24') },
  { orderNo: 'SW20260823001', userId: 1002, stationName: '金边·俄罗斯市场站', stationId: 2, status: 'COMPLETED', batteryDeposit: 60, estElecFee: 1.1, estServiceFee: 0.6, settleStatus: 'SETTLED', createdAt: iso('2026-08-23') },
];

const rentalOrders = [
  { id: 1, orderNo: 'RN20260820001', assetId: 1002, renterUserId: 1001, stationId: 1, rentalType: 'VEHICLE_RENTAL', status: 'COMPLETED', totalFee: 9.0, ownerShare: 6.3, stationShare: 1.35, platformShare: 0.9, insuranceShare: 0.45 },
  { id: 2, orderNo: 'RN20260821001', assetId: 2002, renterUserId: 1002, stationId: 2, rentalType: 'BATTERY_EXCHANGE', status: 'ACTIVE', totalFee: 2.5, ownerShare: 1.75, stationShare: 0.375, platformShare: 0.25, insuranceShare: 0.125 },
  { id: 3, orderNo: 'RN20260822001', assetId: 3001, renterUserId: 1003, stationId: 1, rentalType: 'VEHICLE_RENTAL', status: 'COMPLETED', totalFee: 35.0, ownerShare: 24.5, stationShare: 5.25, platformShare: 3.5, insuranceShare: 1.75 },
];

const riskEvents = [
  { id: 1, operatorId: 1007, stationId: 2, eventType: 'BOND_SHORTFALL', severity: 'HIGH', description: '站点保证金缺口 12%，低于阈值', detectedValue: 8800, expectedValue: 10000, autoAction: 'FREEZE_ACCOUNT', resolved: false, createdAt: iso('2026-08-25') },
  { id: 2, operatorId: 1004, stationId: 1, eventType: 'COMPLAINT_SPIKE', severity: 'MEDIUM', description: '近 24h 投诉激增 5 起', detectedValue: 5, expectedValue: 2, autoAction: 'ALERT_ONLY', resolved: false, createdAt: iso('2026-08-25') },
  { id: 3, operatorId: 1007, stationId: 2, eventType: 'UNUSUAL_TRANSACTION', severity: 'MEDIUM', description: '单账户短时高频换电', detectedValue: 18, expectedValue: 10, autoAction: 'ALERT_ONLY', resolved: true, createdAt: iso('2026-08-24') },
  { id: 4, operatorId: 1006, stationId: 5, eventType: 'RECONCILIATION_FAIL', severity: 'LOW', description: '对账差异已自动冲正', detectedValue: 2, expectedValue: 0, autoAction: 'ALERT_ONLY', resolved: true, createdAt: iso('2026-08-23') },
];

const insuranceFunds = [
  { id: 1, totalBalance: 64200.0, totalCollected: 128400.0, totalClaimed: 64200.0, coverageRatio: 98.6, totalAssetValue: 520000.0, status: 'HEALTHY' },
];

const riskMonitors = [
  { id: 1, stationId: 1, operatorId: 1004, metricType: 'BOND_SHORTFALL', metricValue: 0, threshold: 10000, riskScore: 5, status: 'NORMAL', baseline: 10000, triggeredReason: '', resolutionNote: '' },
  { id: 2, stationId: 2, operatorId: 1007, metricType: 'BOND_SHORTFALL', metricValue: 8800, threshold: 10000, riskScore: 72, status: 'CRITICAL', baseline: 10000, triggeredReason: '保证金缺口', resolutionNote: '' },
  { id: 3, stationId: 3, operatorId: 1004, metricType: 'COMPLAINT_SPIKE', metricValue: 5, threshold: 2, riskScore: 48, status: 'WARNING', baseline: 2, triggeredReason: '投诉激增', resolutionNote: '' },
];

const permCatalog = [
  { code: 'dashboard', name: '工作台', ptype: 'MENU', children: [{ code: 'dashboard:view', name: '查看', ptype: 'BUTTON', children: [] }] },
  {
    code: 'asset', name: '资产管理', ptype: 'MENU', children: [
      { code: 'asset:read', name: '查看', ptype: 'BUTTON', children: [] },
      { code: 'asset:write', name: '编辑', ptype: 'BUTTON', children: [] },
      { code: 'asset:trace', name: '溯源', ptype: 'BUTTON', children: [] },
    ],
  },
  {
    code: 'swap', name: '换电运营', ptype: 'MENU', children: [
      { code: 'swap:read', name: '查看', ptype: 'BUTTON', children: [] },
      { code: 'swap:write', name: '确认订单', ptype: 'BUTTON', children: [] },
    ],
  },
  {
    code: 'shared', name: '共享池', ptype: 'MENU', children: [
      { code: 'shared:read', name: '查看', ptype: 'BUTTON', children: [] },
      { code: 'shared:write', name: '入池/租赁', ptype: 'BUTTON', children: [] },
    ],
  },
  {
    code: 'risk', name: '风控中心', ptype: 'MENU', children: [
      { code: 'risk:read', name: '查看', ptype: 'BUTTON', children: [] },
      { code: 'risk:resolve', name: '处置', ptype: 'BUTTON', children: [] },
    ],
  },
  { code: 'system', name: '系统中心', ptype: 'MENU', children: [
    { code: 'system:role', name: '角色权限', ptype: 'BUTTON', children: [] },
    { code: 'system:user', name: '用户管理', ptype: 'BUTTON', children: [] },
  ] },
];

const permRole = [
  { permissionCode: 'dashboard:view', canRead: true, canCreate: false, canUpdate: false, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'asset:read', canRead: true, canCreate: false, canUpdate: false, canDelete: false, canExport: true, buttonsJson: '{"export":true}' },
  { permissionCode: 'asset:write', canRead: true, canCreate: true, canUpdate: true, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'asset:trace', canRead: true, canCreate: false, canUpdate: false, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'swap:read', canRead: true, canCreate: false, canUpdate: false, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'swap:write', canRead: true, canCreate: false, canUpdate: true, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'shared:read', canRead: true, canCreate: false, canUpdate: false, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'shared:write', canRead: true, canCreate: true, canUpdate: true, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'risk:read', canRead: true, canCreate: false, canUpdate: false, canDelete: false, canExport: true, buttonsJson: '{"export":true}' },
  { permissionCode: 'risk:resolve', canRead: true, canCreate: false, canUpdate: true, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'system:role', canRead: true, canCreate: true, canUpdate: true, canDelete: false, canExport: false, buttonsJson: '{}' },
  { permissionCode: 'system:user', canRead: true, canCreate: true, canUpdate: true, canDelete: false, canExport: true, buttonsJson: '{"export":true}' },
];

const opRiskEvents = [
  { id: 1, operatorId: 1007, eventType: 'BOND_SHORTFALL', severity: 'HIGH', description: '站点保证金缺口 12%' },
  { id: 2, operatorId: 1007, eventType: 'ASSET_MISSING', severity: 'MEDIUM', description: '共享池资产 1 台离线超 24h' },
  { id: 3, operatorId: 1004, eventType: 'COMPLAINT_SPIKE', severity: 'MEDIUM', description: '投诉激增' },
  { id: 4, operatorId: 1006, eventType: 'RECONCILIATION_FAIL', severity: 'LOW', description: '对账差异已冲正' },
];

const operatorAccounts = [
  { id: 1, operatorId: 1007, stationId: 2, type: 'MANAGEMENT_FEE', balance: 12480.5 },
  { id: 2, operatorId: 1007, stationId: 2, type: 'SERVICE_FEE', balance: 6420.0 },
  { id: 3, operatorId: 1004, stationId: 1, type: 'PV_REVENUE', balance: 3320.8 },
  { id: 4, operatorId: 1006, stationId: 5, type: 'RECOVERY', balance: 980.0 },
];

const sharedPoolRows = [
  { id: 1, assetId: 1002, ownerUserId: 1006, status: 'AVAILABLE' },
  { id: 2, assetId: 2002, ownerUserId: 1007, status: 'AVAILABLE' },
  { id: 3, assetId: 1004, ownerUserId: 1007, status: 'RENTED' },
  { id: 4, assetId: 2003, ownerUserId: 1007, status: 'AVAILABLE' },
  { id: 5, assetId: 2005, ownerUserId: 1007, status: 'AVAILABLE' },
];

const ledgerAccounts = [
  { id: 1, userId: 0, accountType: 'MASTER', currency: 'USD', balance: 184520.36, frozen: 0 },
  { id: 2, userId: 1001, accountType: 'ASSET', currency: 'USD', balance: 1264.0, frozen: 0 },
  { id: 3, userId: 1005, accountType: 'ASSET', currency: 'USD', balance: 8420.5, frozen: 0 },
  { id: 4, userId: 0, accountType: 'DEPOSIT_LOCKED', currency: 'USD', balance: 128400.0, frozen: 128400.0 },
  { id: 5, userId: 0, accountType: 'RESIDUAL_RESERVE', currency: 'USD', balance: 96350.0, frozen: 0 },
  { id: 6, userId: 0, accountType: 'BATTERY_FUND', currency: 'USD', balance: 42180.0, frozen: 0 },
  { id: 7, userId: 0, accountType: 'VEHICLE_RISK', currency: 'USD', balance: 21800.0, frozen: 0 },
  { id: 8, userId: 1006, accountType: 'ASSET', currency: 'USD', balance: 5310.2, frozen: 0 },
  { id: 9, userId: 1007, accountType: 'ASSET', currency: 'USD', balance: 2990.75, frozen: 0 },
  { id: 10, userId: 1002, accountType: 'ASSET', currency: 'USD', balance: 432.0, frozen: 0 },
];

const deposits = [
  { id: 1, depositNo: 'DP20260801001', userId: 1001, assetId: 2001, amount: 60, status: 'PAID', payOrderNo: 'PO20260801001', createdAt: iso('2026-08-01') },
  { id: 2, depositNo: 'DP20260801002', userId: 1002, assetId: 2002, amount: 60, status: 'PAID', payOrderNo: 'PO20260801002', createdAt: iso('2026-08-01') },
  { id: 3, depositNo: 'DP20260801003', userId: 1003, assetId: 2003, amount: 60, status: 'PAID', payOrderNo: 'PO20260801003', createdAt: iso('2026-08-02') },
  { id: 4, depositNo: 'DP20260200004', userId: 1005, assetId: 1001, amount: 380, status: 'RELEASED', payOrderNo: 'PO20260200004', createdAt: iso('2026-02-01') },
  { id: 5, depositNo: 'DP20260300005', userId: 1006, assetId: 1002, amount: 380, status: 'FORFEITED', payOrderNo: 'PO20260300005', createdAt: iso('2026-03-10') },
];

const blacklist = [
  { id: 1, type: 'USER_BANNED', targetId: 1009, reason: '恶意刷单 / 虚假交易' },
  { id: 2, type: 'USER_BANNED', targetId: 1012, reason: '资产破坏未赔偿' },
];

const txns = [
  { id: 1, txnNo: 'TXN20260825001', userId: 1001, type: 'SWAP_FEE', amount: 1.32, currency: 'USD', status: 'SUCCESS', createdAt: iso('2026-08-25') },
  { id: 2, txnNo: 'TXN20260825002', userId: 1002, type: 'SWAP_FEE', amount: 1.26, currency: 'USD', status: 'SUCCESS', createdAt: iso('2026-08-25') },
  { id: 3, txnNo: 'TXN20260824001', userId: 1005, type: 'SHARED_INCOME', amount: 6.3, currency: 'USD', status: 'SETTLED', createdAt: iso('2026-08-24') },
  { id: 4, txnNo: 'TXN20260823001', userId: 1003, type: 'RENTAL_FEE', amount: 35.0, currency: 'USD', status: 'SUCCESS', createdAt: iso('2026-08-23') },
  { id: 5, txnNo: 'TXN20260822001', userId: 1007, type: 'DEPOSIT', amount: 60, currency: 'USD', status: 'SUCCESS', createdAt: iso('2026-08-22') },
  { id: 6, txnNo: 'TXN20260821001', userId: 1006, type: 'PV_REVENUE', amount: 52.4, currency: 'USD', status: 'SETTLED', createdAt: iso('2026-08-21') },
];

const transfers = [
  { id: 1, assetId: 1001, assetType: 'VEHICLE', fromUserId: 1, toUserId: 1005, transferType: 'INITIAL_PURCHASE', stationId: 1, swapOrderId: null, chainHash: '0x9f2a...c41', assetSoh: 98, assetSoc: 100, transferredAt: iso('2026-02-01') },
  { id: 2, assetId: 1002, assetType: 'VEHICLE', fromUserId: 1006, toUserId: 1001, transferType: 'SHARED_POOL_ENTRY', stationId: 1, swapOrderId: null, chainHash: '0x3b71...e09', assetSoh: 96, assetSoc: 88, transferredAt: iso('2026-03-15') },
  { id: 3, assetId: 2002, assetType: 'BATTERY', fromUserId: 1007, toUserId: 1002, transferType: 'RENTAL_START', stationId: 2, swapOrderId: null, chainHash: '0x77c2...1ab', assetSoh: 94, assetSoc: 72, transferredAt: iso('2026-08-21') },
  { id: 4, assetId: 3001, assetType: 'DRONE', fromUserId: 1, toUserId: 1005, transferType: 'INITIAL_PURCHASE', stationId: 1, swapOrderId: null, chainHash: '0xa15d...77f', assetSoh: 92, assetSoc: 100, transferredAt: iso('2026-01-20') },
  { id: 5, assetId: 2001, assetType: 'BATTERY', fromUserId: 1005, toUserId: 1003, transferType: 'SWAP_EXCHANGE', stationId: 1, swapOrderId: 4, chainHash: '0xeed4...33c', assetSoh: 90, assetSoc: 60, transferredAt: iso('2026-08-24') },
  { id: 6, assetId: 1004, assetType: 'VEHICLE', fromUserId: 1007, toUserId: 1002, transferType: 'RENTAL_START', stationId: 3, swapOrderId: null, chainHash: '0x501b...8d2', assetSoh: 95, assetSoc: 80, transferredAt: iso('2026-08-22') },
];

const settlements = [
  { id: 1, settlementNo: 'ST20260825001', settlementDate: '2026-08-25', stationId: 1, totalRevenue: 2860.0, ownerShare: 2002.0, stationShare: 429.0, platformShare: 286.0, insuranceShare: 143.0, status: 'PENDING' },
  { id: 2, settlementNo: 'ST20260824001', settlementDate: '2026-08-24', stationId: 2, totalRevenue: 1980.0, ownerShare: 1386.0, stationShare: 297.0, platformShare: 198.0, insuranceShare: 99.0, status: 'SETTLED' },
  { id: 3, settlementNo: 'ST20260823001', settlementDate: '2026-08-23', stationId: 1, totalRevenue: 3120.0, ownerShare: 2184.0, stationShare: 468.0, platformShare: 312.0, insuranceShare: 156.0, status: 'SETTLED' },
  { id: 4, settlementNo: 'ST20260822001', settlementDate: '2026-08-22', stationId: 3, totalRevenue: 1540.0, ownerShare: 1078.0, stationShare: 231.0, platformShare: 154.0, insuranceShare: 77.0, status: 'SETTLED' },
];

const splitRules = [
  { id: 1, assetId: 1002, poolEntryId: 1, ownerRate: 0.70, stationRate: 0.15, platformRate: 0.10, insuranceRate: 0.05, shareBasis: 'PER_DAY', effectiveFrom: '2026-03-15', effectiveTo: null, status: 'ACTIVE' },
  { id: 2, assetId: 2002, poolEntryId: 2, ownerRate: 0.70, stationRate: 0.15, platformRate: 0.10, insuranceRate: 0.05, shareBasis: 'PER_SWAP', effectiveFrom: '2026-03-12', effectiveTo: null, status: 'ACTIVE' },
  { id: 3, assetId: 1004, poolEntryId: 3, ownerRate: 0.70, stationRate: 0.15, platformRate: 0.10, insuranceRate: 0.05, shareBasis: 'PER_DAY', effectiveFrom: '2026-03-01', effectiveTo: null, status: 'ACTIVE' },
];

const settings = [
  { configKey: 'site.title', configValue: 'Claw 新能源资产运营平台', category: 'UI', description: '站点标题', dataType: 'STRING', editable: true },
  { configKey: 'site.locale', configValue: 'zh-KH', category: 'UI', description: '默认语言(中/柬)', dataType: 'STRING', editable: true },
  { configKey: 'risk.bond.threshold', configValue: '10000', category: 'RISK', description: '站点保证金预警阈值(USD)', dataType: 'NUMBER', editable: true },
  { configKey: 'swap.deposit', configValue: '60', category: 'BIZ', description: '换电押金(USD)', dataType: 'NUMBER', editable: false },
  { configKey: 'asset.residual.rate', configValue: '0.30', category: 'BIZ', description: '残值率(归资产所有人)', dataType: 'NUMBER', editable: false },
  { configKey: 'feature.cross.border', configValue: 'false', category: 'BIZ', description: '跨境结算开关(v2 已砍全球化)', dataType: 'BOOLEAN', editable: false },
];

const countries = [
  { id: 1, name: '柬埔寨', code: 'KH', currency: 'USD', flag: '🇰🇭' },
  { id: 2, name: '中国', code: 'CN', currency: 'CNY', flag: '🇨🇳' },
];

const dashboard = {
  totalSwapOrders: 1284,
  readyBatteries: 326,
  chargingBatteries: 58,
  assetCount: assets.length,
  purchaseTotal: 542600.0,
  manufacturerCount: manufacturers.length,
  productCount: products.length,
  skuCount: skus.length,
  escrowAccounts: [
    { escrowType: 'DEPOSIT_LOCKED', balance: 128400.0 },
    { escrowType: 'RESIDUAL_RESERVE', balance: 96350.0 },
    { escrowType: 'BATTERY_FUND', balance: 42180.0 },
  ],
  assetByStage: { IN_STOCK: 42, IN_USE: 96, SHARED: 28, REPAIR: 9, DISABLED: 5 },
  sources: [
    { key: 'swap', label: '累计换电订单', sourceTable: 'claw.swap_orders', note: '换电订单总数' },
    { key: 'battery', label: '在线电池', sourceTable: 'claw.batteries', note: 'status=READY/CHARGING' },
    { key: 'asset', label: '资产总数', sourceTable: 'claw.assets', note: '全部资产' },
    { key: 'purchase', label: '累计采购额', sourceTable: 'claw.purchase_orders', note: '已支付采购单' },
  ],
};

/* ----------------------------- 动态构造 ----------------------------- */

function buildTrace(assetId) {
  const a = assets.find((x) => x.id === Number(assetId)) || assets[0];
  return {
    assetType: a.assetType,
    status: a.status,
    asset: {
      assetNo: a.assetNo, serialNumber: a.serialNumber, manufacturerId: 1, productId: 1, skuId: 1, qrCode: a.qrCode,
    },
    lifecycle: [
      { id: 1, stage: 'PRODUCED', location: '金边电科工厂', operatorId: 1, note: '生产出厂', occurredAt: iso('2026-02-01') },
      { id: 2, stage: 'IN_USE', location: '金边·中央换电站', operatorId: a.ownerId, note: '投入运营', occurredAt: iso('2026-02-05') },
    ],
    maintenance: [
      { id: 1, mtype: '轮胎更换', vendor: '中央站', cost: '¥48', servicedAt: iso('2026-05-12'), note: '' },
      { id: 2, mtype: '固件升级', vendor: '厂家', cost: '¥0', servicedAt: iso('2026-07-03'), note: '控制器' },
    ],
    usage: [
      { id: 1, periodStart: iso('2026-07'), periodEnd: iso('2026-08'), mileageKm: 320, cycles: 18, energyKwh: 96.5, note: '' },
    ],
    vehicleOps: [
      { id: 1, opType: 'PASSENGER', startedAt: iso('2026-08-10'), endedAt: iso('2026-08-10'), revenue: 128.0, note: '客运' },
    ],
    totalRevenue: 128.0,
  };
}

function transferDetail(id) {
  const t = transfers.find((x) => x.id === Number(id)) || transfers[0];
  return {
    transfer: t,
    audits: [
      { id: 1, anomalyType: 'NONE', riskScore: 0, description: '转移合规', userDailyTransferCount: 1, assetDailyTransferCount: 1, detectedAt: t.transferredAt, reviewed: true },
    ],
  };
}

function scoreFor(userId) {
  return {
    userId: Number(userId),
    score: 812,
    level: 'A',
    totalRentals: 23,
    onTimeRate: 0.98,
    disputeCount: 1,
    updatedAt: ts(),
  };
}

/* ----------------------------- 路由解析 ----------------------------- */

const GET_HANDLERS = [
  [/^\/v1\/admin\/dashboard$/, () => dashboard],
  [/^\/v1\/stations\/(nearby|search)$/, () => stations],
  [/^\/v1\/assets$/, () => assets],
  [/^\/v1\/admin\/users$/, () => users],
  [/^\/v1\/admin\/roles\/user-roles$/, () => userRoles],
  [/^\/v1\/admin\/roles$/, () => roles],
  [/^\/v1\/admin\/departments$/, () => departments],
  [/^\/v1\/reconciliations\/latest$/, () => reconciliation],
  [/^\/v1\/admin\/fee\/elec-prices$/, () => elecPrices],
  [/^\/v1\/admin\/fee\/rules$/, () => feeRules],
  [/^\/v1\/admin\/complaints$/, () => complaints],
  [/^\/v1\/admin\/insurance\/claims\/pending$/, () => pendingClaims],
  [/^\/v1\/admin\/manufacturer\/manufacturers$/, () => manufacturers],
  [/^\/v1\/admin\/manufacturer\/products$/, () => products],
  [/^\/v1\/admin\/manufacturer\/skus$/, () => skus],
  [/^\/v1\/admin\/manufacturer\/purchase-orders$/, () => purchaseOrders],
  [/^\/v1\/admin\/manufacturer\/assets\/(\d+)\/trace$/, (m) => buildTrace(m[1])],
  [/^\/v1\/admin\/orders\/swap$/, () => swapOrders],
  [/^\/v1\/admin\/orders\/rental$/, () => rentalOrders],
  [/^\/v1\/admin\/risk\/events$/, () => riskEvents],
  [/^\/v1\/swap-orders$/, () => swapOrdersPublic],
  [/^\/v1\/admin\/risk\/insurance-fund$/, () => insuranceFunds],
  [/^\/v1\/admin\/risk\/monitors$/, () => riskMonitors],
  [/^\/v1\/admin\/permissions\/catalog$/, () => permCatalog],
  [/^\/v1\/admin\/permissions\/role\/\w+$/, () => permRole],
  [/^\/v1\/admin\/operator\/risk-events\/unresolved$/, () => opRiskEvents],
  [/^\/v1\/admin\/operator\/accounts$/, () => operatorAccounts],
  [/^\/v1\/admin\/shared-pool\/(available|owner|renter)$/, () => sharedPoolRows],
  [/^\/v1\/ledger\/accounts$/, () => ledgerAccounts],
  [/^\/v1\/deposits$/, () => deposits],
  [/^\/v1\/admin\/recovery\/blacklist$/, () => blacklist],
  [/^\/v1\/admin\/recovery\/scores\/(\d+)$/, (m) => scoreFor(m[1])],
  [/^\/v1\/payments\/txns$/, () => txns],
  [/^\/v1\/admin\/custody\/transfers\/(\d+)$/, (m) => transferDetail(m[1])],
  [/^\/v1\/admin\/custody\/transfers$/, () => transfers],
  [/^\/v1\/admin\/profit\/settlements$/, () => settlements],
  [/^\/v1\/admin\/profit\/split-rules$/, () => splitRules],
  [/^\/v1\/admin\/settings\/config$/, () => settings],
  [/^\/v1\/countries$/, () => countries],
];

// 写操作（POST/PUT/DELETE）回显请求体，保证表单"操作结果"有反馈（演示不落库）
function echoWrite(config) {
  let body = config.data;
  try { body = typeof body === 'string' ? JSON.parse(body) : body; } catch (e) { body = undefined; }
  const isQr = /register-qr$/.test(config.url || '');
  return {
    ok: true,
    id: 'MOCK-' + Date.now(),
    message: '演示：已受理（未落库，接真实后端后生效）',
    ...(isQr ? { bornCount: (body && body.items ? body.items.length : 1) } : {}),
    echoed: body || {},
  };
}

// 根据请求配置返回演示数据；无匹配时 GET 返回空数组（避免整页报错），写操作返回回显。
export function resolveMock(config) {
  if (!config || !config.url) return undefined;
  const method = (config.method || 'get').toLowerCase();
  const url = config.url.split('?')[0];
  if (method === 'get') {
    for (const [re, fn] of GET_HANDLERS) {
      const m = url.match(re);
      if (m) return fn(m);
    }
    return [];
  }
  return echoWrite(config);
}
