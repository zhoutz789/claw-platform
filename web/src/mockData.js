// 本地 mock 数据：承载新增模块（产品管理/物联网、任务发布、品牌方、系统设置扩展、App 门户）
// 不依赖后端，保证整合进 web 后打开即可点测。后端就绪后可平滑替换为 api 调用。

export const ASSET_PARAMS = {
  defaultRate: 0.10,
  method: 'compound', // compound 复利 / simple 单利
  note: '产权转让现值为系统定值，双方不可议价；利率与计息方式可在系统设置-资产参数调整。',
};

// 产权现值：购入原值 × (1+利率)^持有年数（复利）
export const calcPresent = (costPrice, holdYears, rate = ASSET_PARAMS.defaultRate, method = ASSET_PARAMS.method) => {
  const v = method === 'simple' ? costPrice * (1 + rate * holdYears) : costPrice * Math.pow(1 + rate, holdYears);
  return Math.round(v * 100) / 100;
};

// 系统设置：菜单树（RuoYi 式勾选）
export const MENU_TREE = [
  { id: 'dash', label: '工作台', children: [{ id: 'dash-home', label: '概览' }] },
  {
    id: 'iot', label: '产品管理 / 物联网', children: [
      { id: 'iot-tpl', label: '产品模板' },
      { id: 'iot-prod', label: '品牌产品' },
      { id: 'iot-dev', label: '设备' },
      { id: 'iot-cert', label: '合格证' },
    ],
  },
  {
    id: 'goods', label: '商品 / 品牌方', children: [
      { id: 'goods-brand', label: '品牌方管理' },
      { id: 'goods-pub', label: '商品发布' },
      { id: 'goods-list', label: '商品列表' },
      { id: 'goods-order', label: '订单中心' },
    ],
  },
  {
    id: 'task', label: '任务发布', children: [
      { id: 'task-rent', label: '资产出租 / 共享' },
      { id: 'task-drone', label: '无人机任务' },
    ],
  },
  {
    id: 'sys', label: '系统设置', children: [
      { id: 'sys-user', label: '用户管理' },
      { id: 'sys-role', label: '角色管理' },
      { id: 'sys-menu', label: '菜单权限' },
      { id: 'sys-asset', label: '资产参数' },
    ],
  },
];

export const ROLES = [
  { id: 'R1', name: '超级管理员', users: 1, dataScope: '全部数据', desc: '平台最高权限' },
  { id: 'R2', name: '品牌方管理员', users: 8, dataScope: '本品牌数据', desc: '管理自有品牌产品与商品' },
  { id: 'R3', name: '服务站站长', users: 24, dataScope: '本服务站', desc: '线下换电/租赁/维修' },
  { id: 'R4', name: '普通用户', users: 1280, dataScope: '个人数据', desc: '资产使用与任务接单' },
];

export const USERS = [
  { id: 'U1', name: '周老板', org: '平台运营', role: '超级管理员', status: '正常' },
  { id: 'U2', name: '李工', org: '金边电科', role: '品牌方管理员', status: '正常' },
  { id: 'U3', name: '王站长', org: '金边·中央站', role: '服务站站长', status: '正常' },
  { id: 'U4', name: '用户小敏', org: '个人', role: '普通用户', status: '正常' },
];

// 产品模板（平台级，无品牌）
export const TEMPLATES = [
  {
    id: 'TPL-1', name: '电动两轮车', code: 'EV-BIKE',
    fields: [
      { key: 'battery', label: '电池容量(kWh)', type: 'number', unit: 'kWh', example: '1.2' },
      { key: 'range', label: '续航里程(km)', type: 'number', unit: 'km', example: '80' },
      { key: 'motor', label: '电机功率(W)', type: 'number', unit: 'W', example: '400' },
      { key: 'color', label: '外观颜色', type: 'text', example: '松绿' },
    ],
    productsCount: 3, updatedAt: '2026-08-20',
  },
  {
    id: 'TPL-2', name: '共享电池', code: 'SWAP-BAT',
    fields: [
      { key: 'capacity', label: '电芯容量(Ah)', type: 'number', unit: 'Ah', example: '40' },
      { key: 'cycles', label: '循环寿命', type: 'number', unit: '次', example: '2000' },
      { key: 'voltage', label: '标称电压(V)', type: 'number', unit: 'V', example: '48' },
    ],
    productsCount: 2, updatedAt: '2026-08-21',
  },
  {
    id: 'TPL-3', name: '植保无人机', code: 'DRONE-AGRI',
    fields: [
      { key: 'wheelbase', label: '轴距(mm)', type: 'number', unit: 'mm', example: '1520' },
      { key: 'payload', label: '最大载荷(kg)', type: 'number', unit: 'kg', example: '40' },
      { key: 'endurance', label: '单电续航(min)', type: 'number', unit: 'min', example: '18' },
      { key: 'payloadType', label: '载荷类型', type: 'text', example: 'SPRAY 喷洒' },
      { key: 'remoteId', label: 'Remote ID 码', type: 'text', example: 'KH-DRONE-88001' },
    ],
    productsCount: 1, updatedAt: '2026-08-24',
  },
];

// 品牌方（带名下公司 + 商标注册证书验证状态）
export const BRANDS = [
  {
    id: 'B1', name: '金边电科', company: '柬埔寨新能源科技有限公司',
    creditCode: 'KH-912034567', trademark: '金边电科 / Trademark No. KH-TR-2025-0881',
    status: '已验证', owner: '李工', products: 3,
  },
  {
    id: 'B2', name: '湄公电源', company: '湄公河能源股份', creditCode: 'KH-923456789',
    trademark: '湄公电源 / Trademark No. KH-TR-2025-0912',
    status: '已验证', owner: '陈工', products: 1,
  },
];

// 品牌产品（品牌实例，统领设备）
export const PRODUCTS = [
  {
    id: 'P1', name: '金边电科 电动两轮车 X1', templateId: 'TPL-1', brandId: 'B1',
    category: 'EV-BIKE', price: 380, depositSafe: true, devicesCount: 42,
    spec: { battery: '1.2 kWh', range: '80 km', motor: '400W', color: '松绿' },
  },
  {
    id: 'P2', name: '金边电科 共享电池 S2', templateId: 'TPL-2', brandId: 'B1',
    category: 'SWAP-BAT', price: 280, depositSafe: true, devicesCount: 120,
    spec: { capacity: '40 Ah', cycles: '2000', voltage: '48 V' },
  },
  {
    id: 'P3', name: '金边电科 植保无人机 A6', templateId: 'TPL-3', brandId: 'B1',
    category: 'DRONE-AGRI', price: 2600, depositSafe: true, devicesCount: 18,
    spec: { wheelbase: '1520 mm', payload: '40 kg', endurance: '18 min', payloadType: 'SPRAY 喷洒' },
  },
];

// 设备（对象，归属 一个产品 + 一个品牌方）
export const DEVICES = [
  {
    id: 'DEV-2026-000001', productId: 'P1', brandId: 'B1', owner: '李工',
    status: '在网', location: { lat: '11.5624', lng: '104.9160', addr: '金边·俄罗斯市场站 1.2km' },
    capabilities: ['换电', '共享', '出租'],
    costPrice: 380, holdYears: 1.3,
    earn: { 换电收益: 210, 共享出租: 312 },
    repair: [
      { date: '2026-05-12', item: '轮胎更换', by: '中央站', cost: '¥48' },
      { date: '2026-07-03', item: '控制器固件升级', by: '厂家', cost: '¥0' },
    ],
    transfer: [{ date: '2026-02-01', type: '出厂', from: '厂家', to: '李工', price: 380 }],
  },
  {
    id: 'DEV-2026-000002', productId: 'P2', brandId: 'B1', owner: '王站长',
    status: '在网', location: { lat: '11.5712', lng: '104.9155', addr: '金边·中央站' },
    capabilities: ['换电'], costPrice: 280, holdYears: 0.6,
    earn: { 换电: 210 },
    repair: [{ date: '2026-06-20', item: '外壳清洁', by: '中央站', cost: '¥12' }],
    transfer: [{ date: '2026-03-10', type: '出厂', from: '厂家', to: '王站长', price: 280 }],
  },
  {
    id: 'DEV-2026-D001', productId: 'P3', brandId: 'B1', owner: '李工', assetClass: 'DRONE',
    status: '在网', location: { lat: '11.5489', lng: '104.9210', addr: '金边·郊农作业区' },
    capabilities: ['植保', '巡检', '救援'], costPrice: 2600, holdYears: 0.8,
    remoteId: 'KH-DRONE-88001', payloadType: 'SPRAY', maxFlightTimeMin: 18, maxPayloadKg: 40,
    airworthinessCertNo: 'SSCA-AW-2026-0012', flightMinutes: 2680,
    earn: { 植保: 980, 巡检: 260, 救援: 260 },
    repair: [{ date: '2026-07-15', item: '桨叶更换', by: '中央站', cost: '¥60' }],
    transfer: [{ date: '2026-01-20', type: '出厂', from: '厂家', to: '李工', price: 2600 }],
  },
];

// 商品
export const GOODS = [
  { id: 'G1', title: '金边电科 X1 整车', brandId: 'B1', productId: 'P1', status: '在售', price: 3999, stock: 36, orders: 58 },
  { id: 'G2', title: '共享电池 S2（押金制）', brandId: 'B1', productId: 'P2', status: '在售', price: 1999, stock: 200, orders: 130 },
];

// 订单
export const ORDERS = [
  { id: 'O20260801', buyer: '用户小敏', item: '金边电科 X1', amount: 3999, status: '已支付', date: '2026-08-01' },
  { id: 'O20260728', buyer: '服务站批量', item: '共享电池 S2 x20', amount: 39980, status: '已完成', date: '2026-07-28' },
];

// 任务大厅（资产相关：共享出租 + 无人机低空作业；按能力标签 + 地理位置匹配）
export const TASKS = [
  { id: 'T4', type: '植保', title: '郊农 20 公顷水稻喷洒', reward: '¥320', dist: '6.0km', status: '待接单' },
  { id: 'T5', type: '巡检', title: '湄公河岸线巡检 1 架次', reward: '¥180', dist: '4.5km', status: '待接单' },
  { id: 'T6', type: '救援', title: '洪水点物资投送', reward: '¥260', dist: '8.0km', status: '待接单' },
  { id: 'T7', type: '共享出租', title: '电动两轮车 X1 日租（金边·中央站）', reward: '¥9/天', dist: '0.3km', status: '待接单' },
];

// 低空经济专属：空域分区（地理围栏）
export const AIRSPACE_ZONES = [
  { id: 'Z1', name: '金边郊农作业区', level: 'OPERATIONAL', center: '11.5489,104.9210', radiusM: 3000, country: 'KH', note: '植保/物流可飞' },
  { id: 'Z2', name: '湄公河岸线巡检带', level: 'OPERATIONAL', center: '11.5650,104.9300', radiusM: 2000, country: 'KH', note: '巡检可飞' },
  { id: 'Z3', name: '金边国际机场净空', level: 'NFZ', center: '11.5466,104.8443', radiusM: 8000, country: 'KH', note: '禁飞区' },
  { id: 'Z4', name: '市中心商圈', level: 'RESTRICTED', center: '11.5564,104.9253', radiusM: 1500, country: 'KH', note: '限飞·需审批' },
];

// 飞手资质（SSCA 签发）
export const PILOT_LICENSES = [
  { id: 'L1', licenseNo: 'SSCA-AG-2026-0031', holder: '李工', ltype: 'AGRICULTURE', issuer: 'SSCA', expiry: '2027-06-30' },
  { id: 'L2', licenseNo: 'SSCA-LOG-2026-0045', holder: '王站长', ltype: 'LOGISTICS', issuer: 'SSCA', expiry: '2027-12-31' },
  { id: 'L3', licenseNo: 'SSCA-IN-2026-0022', holder: '陈工', ltype: 'INSPECTION', issuer: 'SSCA', expiry: '2027-03-15' },
];

// 无人机作业计量（分账/任务计量依据）
export const DRONE_MISSIONS = [
  { id: 'M1', deviceId: 'DEV-2026-D001', missionType: 'SPRAY', payload: '农药 40L', areaHa: 18.5, flightMinutes: 96, pilot: '李工', executedAt: '2026-08-18' },
  { id: 'M2', deviceId: 'DEV-2026-D001', missionType: 'LOGISTICS', payload: '货箱 20kg', trips: 3, flightMinutes: 54, pilot: '王站长', executedAt: '2026-08-20' },
  { id: 'M3', deviceId: 'DEV-2026-D001', missionType: 'INSPECTION', payload: '热成像', areaHa: 0, flightMinutes: 38, pilot: '陈工', executedAt: '2026-08-22' },
];

// 飞行计划（合规前置：须落在 OPERATIONAL 空域 + 有效飞手资质）
export const FLIGHT_PLANS = [
  { id: 'F1', deviceId: 'DEV-2026-D001', zoneId: 'Z1', pilotId: 'L1', plannedAt: '2026-08-26 08:30', status: 'APPROVED' },
  { id: 'F2', deviceId: 'DEV-2026-D001', zoneId: 'Z2', pilotId: 'L3', plannedAt: '2026-08-27 15:00', status: 'APPROVED' },
];
