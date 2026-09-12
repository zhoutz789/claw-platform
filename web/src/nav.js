// 全局导航配置：侧边栏枢纽 + 全局搜索共用，单一数据源避免漂移。
//
// v6（2026-08-29）：应周老板要求「恢复系统管理与权限相关菜单」。
//   背景：v5 曾按指令移除旧四大中心（运营/财务/风控/系统），导致用户管理、角色管理（权限组）、
//   权限矩阵、菜单权限、菜单管理、系统配置等入口整体从侧边栏消失（页面文件与路由一直完好，只是入口被摘掉）。
//   本次将四大中心全部加回，并保留 v5 新增的产品管理 / 项目管理 / 商品管理 / 任务发布四个模块。
//
// v7（2026-08-30）：接入三语 i18n。
//   内置菜单项的 label 由中文原文改为 i18n key（形如 'nav:item.orders' / 'nav:group.ops'），
//   显示时统一经 navLabel() 翻译。
//   ⚠️ 兼容性：老用户 localStorage 里的覆盖项存的仍是中文原文（如 '工作台'），
//      navLabel() 会判定「与内置默认值不一致」从而原样显示，用户改过的名字不会丢、也不会变成 key。
//
// 重要约定：
//   1. 本文件是「完整默认菜单」，不再做隐藏式删除。菜单的显示 / 隐藏 / 排序 / 改名 / 归类，
//      一律由「系统中心 → 菜单管理（/menu-manager）」页面自主配置，配置持久化在本地，不写代码。
//   2. 每一个 path 必须与 App.jsx 中真实注册的 Route 一一对应（见下方 ROUTES 常量）。
//      叶子节点 key 默认与路由段一致，保证选中态与跳转正确；若用户在菜单管理里改了 path，
//      跳转以 path 为准（见 AdminLayout 的 onClick）。
//   3. 菜单数据源新增 / 删除项时，用户的自定义覆盖采用「增量合并」策略（见 menuStore.js 的 mergeWithBase），
//      不再通过升级 STORAGE_KEY 版本号清空用户配置。
import { tv } from './i18n';

// 说明：icon 组件不能直接放进 i18n 语言包，仍在本地引用。
import {
  DashboardOutlined, AppstoreOutlined, ShoppingOutlined, ProjectOutlined, RocketOutlined,
  DeploymentUnitOutlined, AccountBookOutlined, SafetyOutlined, SettingOutlined, InboxOutlined,
  IdcardOutlined, SendOutlined, ShopOutlined, ControlOutlined, ThunderboltOutlined, CarOutlined,
} from '@ant-design/icons';

// 九大模块（工作台 + A/B/C/D 期新设计 + 四大中心）；children 为各模块下的页面。
// 说明：v5 的五个模块（工作台/产品管理/项目管理/商品管理/任务发布）保持原有顺序与内容不变，
// 恢复的四大中心追加在其后，顺序可在「菜单管理」页拖拽调整。
export const NAV = [
  { key: 'workbench', label: 'nav:item.workbench', icon: DashboardOutlined, path: '/workbench' },
  {
    key: 'prod', label: 'nav:group.prod', icon: AppstoreOutlined,
    children: [
      { key: 'product-center', label: 'nav:item.product-center', path: '/product-center' },
      { key: 'certificate', label: 'nav:item.certificate', path: '/certificate' },
      { key: 'bind-ownership', label: 'nav:item.bind-ownership', path: '/bind-ownership' },
      { key: 'product-template', label: 'nav:item.product-template', path: '/product-template' },
      { key: 'data-binding', label: 'nav:item.data-binding', path: '/device-data-access' },
      { key: 'authorization', label: 'nav:item.authorization', path: '/authorization' },
    ],
  },
  {
    key: 'project', label: 'nav:group.project', icon: ProjectOutlined,
    children: [
      { key: 'project-management', label: 'nav:item.project-management', path: '/project-management' },
    ],
  },
  {
    key: 'goods', label: 'nav:group.goods', icon: ShoppingOutlined,
    children: [
      { key: 'goods-list', label: 'nav:item.goods-list', path: '/goods-list' },
      { key: 'product-wizard', label: 'nav:item.product-wizard', path: '/product-wizard' },
      { key: 'product-publish', label: 'nav:item.product-publish', path: '/product-publish' },
      { key: 'brand-onboarding', label: 'nav:item.brand-onboarding', path: '/brand-onboarding' },
      { key: 'manufacturer', label: 'nav:item.manufacturer', path: '/manufacturer' },
      { key: 'order-manage', label: 'nav:item.order-manage', path: '/order-manage' },
      { key: 'merchants', label: 'nav:item.merchants', path: '/merchants' },
      // 类别管理（通用多级分类树，发布商品时选用；menu:categories 权限码见 V78 迁移）
      { key: 'categories', label: 'nav:item.categories', path: '/categories' },
    ],
  },
  // 供应流通（增量 B：生产 / 库存 / 调拨 / 履约 / 结算）
  {
    key: 'supply', label: 'nav:group.supply', icon: InboxOutlined,
    children: [
      // 模块三 · 库存总览：供应流通组首位默认入口（sort_no=450，见 V67 迁移）
      { key: 'inventory-overview', label: 'nav:item.inventory-overview', path: '/inventory-overview' },
      { key: 'production', label: 'nav:item.production', path: '/production' },
      { key: 'mfg-inventory', label: 'nav:item.mfg-inventory', path: '/mfg-inventory' },
      { key: 'station-consignment', label: 'nav:item.station-consignment', path: '/station-consignment' },
      { key: 'transfers', label: 'nav:item.transfers', path: '/transfers' },
      { key: 'fulfillment-orders', label: 'nav:item.fulfillment-orders', path: '/fulfillment-orders' },
      { key: 'pickup-scan', label: 'nav:item.pickup-scan', path: '/pickup-scan' },
      { key: 'commission-rules', label: 'nav:item.commission-rules', path: '/commission-rules' },
      // 容量预订（menu:capacity-booking 权限码见 V77 迁移）
      { key: 'capacity-booking', label: 'nav:item.capacity-booking', path: '/capacity-booking' },
    ],
  },
  // 模块四 · 服务站功能（库存 / 项目 / 结算三层解耦，menu:* 权限码见 V68 迁移）
  {
    key: 'station', label: 'nav:group.station', icon: ShopOutlined,
    children: [
      { key: 'station-inventory', label: 'nav:item.station-inventory', path: '/station-inventory' },
      { key: 'station-projects', label: 'nav:item.station-projects', path: '/station-projects' },
      { key: 'station-settlements', label: 'nav:item.station-settlements', path: '/station-settlements' },
      // 服务站合约（menu:station-contracts 权限码见 V77 迁移）
      { key: 'station-contracts', label: 'nav:item.station-contracts', path: '/station-contracts' },
    ],
  },
  // 增量 D · 无人机 / 低空经济域（menu:* 权限码见 V66 迁移）
  {
    key: 'drone', label: 'nav:group.drone', icon: SendOutlined,
    children: [
      { key: 'airspace-zones', label: 'nav:item.airspace-zones', path: '/airspace-zones' },
      { key: 'flight-plans', label: 'nav:item.flight-plans', path: '/flight-plans' },
      { key: 'pilot-licenses', label: 'nav:item.pilot-licenses', path: '/pilot-licenses' },
      { key: 'drone-ops', label: 'nav:item.drone-ops', path: '/drone-ops' },
    ],
  },
  {
    key: 'task', label: 'nav:group.task', icon: RocketOutlined,
    children: [
      { key: 'task-drone', label: 'nav:item.task-drone', path: '/task-drone' },
      { key: 'task-rent', label: 'nav:item.task-rent', path: '/task-rent' },
      { key: 'task-logi', label: 'nav:item.task-logi', path: '/task-logi' },
      { key: 'task-ad', label: 'nav:item.task-ad', path: '/task-ad' },
      { key: 'task-video', label: 'nav:item.task-video', path: '/task-video' },
      { key: 'task-near', label: 'nav:item.task-near', path: '/task-near' },
      { key: 'task-vehicle', label: 'nav:item.task-vehicle', path: '/task-vehicle' },
    ],
  },
  // T8 · 车辆（地面自动驾驶 / 换电）域（menu:* 权限码见 V120 迁移）
  {
    key: 'vehicle', label: 'nav:group.vehicle', icon: CarOutlined,
    children: [
      { key: 'vehicle-product-classes', label: 'nav:item.vehicle-product-classes', path: '/vehicle-product-classes' },
      { key: 'vehicle-trajectory', label: 'nav:item.vehicle-trajectory', path: '/vehicle-trajectory' },
    ],
  },
  // ——— 以下为 2026-08-29 恢复的旧四大中心 ———
  {
    key: 'ops', label: 'nav:group.ops', icon: DeploymentUnitOutlined,
    children: [
      { key: 'orders', label: 'nav:item.orders', path: '/orders' },
      { key: 'swap-orders', label: 'nav:item.swap-orders', path: '/swap-orders' },
      { key: 'stations', label: 'nav:item.stations', path: '/stations' },
      { key: 'assets', label: 'nav:item.assets', path: '/assets' },
      { key: 'asset-trace', label: 'nav:item.asset-trace', path: '/asset-trace' },
      // 数据回放（摄像头域）：按资产编号检索其下摄像头，实时/历史回放（menu:camera-playback 权限码见 V91）
      { key: 'camera-playback', label: 'nav:item.camera-playback', path: '/camera-playback' },
      { key: 'custody', label: 'nav:item.custody', path: '/custody' },
      { key: 'product-iot', label: 'nav:item.product-iot', path: '/product-iot' },
      { key: 'shared-pool', label: 'nav:item.shared-pool', path: '/shared-pool' },
      { key: 'recovery', label: 'nav:item.recovery', path: '/recovery' },
    ],
  },
  {
    key: 'fin', label: 'nav:group.fin', icon: AccountBookOutlined,
    children: [
      { key: 'ledger', label: 'nav:item.ledger', path: '/ledger' },
      { key: 'payments', label: 'nav:item.payments', path: '/payments' },
      { key: 'deposits', label: 'nav:item.deposits', path: '/deposits' },
      { key: 'settlements', label: 'nav:item.settlements', path: '/settlements' },
      { key: 'reconciliations', label: 'nav:item.reconciliations', path: '/reconciliations' },
      { key: 'profit', label: 'nav:item.profit', path: '/profit' },
      { key: 'fee', label: 'nav:item.fee', path: '/fee' },
      { key: 'operator', label: 'nav:item.operator', path: '/operator' },
    ],
  },
  {
    key: 'risk-center', label: 'nav:group.risk-center', icon: SafetyOutlined,
    children: [
      { key: 'risk', label: 'nav:item.risk', path: '/risk' },
      { key: 'alerts', label: 'nav:item.alerts', path: '/alerts' },
      { key: 'insurance', label: 'nav:item.insurance', path: '/insurance' },
      { key: 'arbitration', label: 'nav:item.arbitration', path: '/arbitration' },
      { key: 'complaints', label: 'nav:item.complaints', path: '/complaints' },
    ],
  },
  // 入驻管理（增量 C：入驻说明 / 申请 / 审核 / 保证金 / 组织治理 / 子账号）
  {
    key: 'onboarding', label: 'nav:group.onboarding', icon: IdcardOutlined,
    children: [
      { key: 'onboarding-apply', label: 'nav:item.onboarding-apply', path: '/onboarding-apply' },
      { key: 'onboarding-review', label: 'nav:item.onboarding-review', path: '/onboarding-review' },
      { key: 'onboarding-content', label: 'nav:item.onboarding-content', path: '/onboarding-content' },
      { key: 'onboarding-deposit-tiers', label: 'nav:item.onboarding-deposit-tiers', path: '/onboarding-deposit-tiers' },
      { key: 'onboarding-deposit-confirm', label: 'nav:item.onboarding-deposit-confirm', path: '/onboarding-deposit-confirm' },
      { key: 'org-manage', label: 'nav:item.org-manage', path: '/org-manage' },
      { key: 'sub-accounts', label: 'nav:item.sub-accounts', path: '/sub-accounts' },
    ],
  },
  // ——— 平台设置 / 约定：从「系统中心」剥离出的独立分组，收口平台级配置类入口，避免误看 ———
  {
    key: 'platform-settings', label: 'nav:group.platform-settings', icon: ControlOutlined,
    children: [
      { key: 'settings', label: 'nav:item.settings', path: '/settings' },
      { key: 'countries', label: 'nav:item.countries', path: '/countries' },
      { key: 'asset-params', label: 'nav:item.asset-params', path: '/asset-params' },
      { key: 'departments', label: 'nav:item.departments', path: '/departments' },
    ],
  },
  {
    key: 'sys', label: 'nav:group.sys', icon: SettingOutlined,
    children: [
      { key: 'users', label: 'nav:item.users', path: '/users' },
      { key: 'app-portal', label: 'nav:item.app-portal', path: '/app-portal' },
      { key: 'roles', label: 'nav:item.roles', path: '/roles' },
      { key: 'role-templates', label: 'nav:item.role-templates', path: '/role-templates' },
      { key: 'role-groups', label: 'nav:item.role-groups', path: '/role-groups' },
      { key: 'principal-bindings', label: 'nav:item.principal-bindings', path: '/principal-bindings' },
      { key: 'permission', label: 'nav:item.permission', path: '/permission' },
      { key: 'menu-manager', label: 'nav:item.menu-manager', path: '/menu-manager' },
    ],
  },
  // 能源运营（光伏 / 虚拟电厂 / 追溯 / 能源品类，menu:* 权限码见 V116 / V117 迁移）
  {
    key: 'energy', label: 'nav:group.energy', icon: ThunderboltOutlined,
    children: [
      { key: 'pv-station', label: 'nav:item.pv-station', path: '/pv-station' },
      { key: 'pv-trace', label: 'nav:item.pv-trace', path: '/pv-trace' },
      { key: 'vpp', label: 'nav:item.vpp', path: '/vpp' },
      { key: 'product-energy', label: 'nav:item.product-energy', path: '/product-energy' },
    ],
  },
];

/** 顶级叶子（无分组）在搜索结果里显示的分组名。 */
export const HOME_GROUP_KEY = '__home__';
export const HOME_GROUP_LABEL = 'nav:group.home';

// 内置默认 label 快照：key → 默认 label（i18n key）。
// 用于判断「用户是否改过名」：改过就原样显示用户输入，没改过才走翻译。
const BASE_LABEL = new Map();
const collectBaseLabel = (nodes) => {
  for (const n of nodes) {
    BASE_LABEL.set(n.key, n.label);
    if (n.children) collectBaseLabel(n.children);
  }
};
collectBaseLabel(NAV);
BASE_LABEL.set(HOME_GROUP_KEY, HOME_GROUP_LABEL);

/**
 * 取得菜单节点的显示名（随当前语言变化）。
 *
 * 规则：
 *   - 节点 label 与内置默认值一致 → 视为未改名，走 i18n 翻译；
 *   - 不一致（用户在菜单管理里改过名，或老版本遗留的中文覆盖项）→ 原样显示，
 *     既不丢失用户自定义，也不会把 i18n key 漏到界面上。
 *
 * @param {Object} node 菜单节点（至少含 key 与 label）
 * @param {string} [fallback] 取不到时的兜底文案
 * @returns {string} 显示名
 */
export function navLabel(node, fallback = '') {
  if (!node || typeof node.label !== 'string') return fallback;
  if (BASE_LABEL.get(node.key) === node.label) {
    return tv(node.label, { defaultValue: node.label }) || node.label;
  }
  return node.label;
}

/**
 * 取得分组的显示名（随当前语言变化）。用法同 navLabel。
 * @param {string} groupKey 分组 key
 * @param {string} groupLabel 分组 label
 * @returns {string} 显示名
 */
export function groupLabel(groupKey, groupLabel) {
  return navLabel({ key: groupKey, label: groupLabel }, groupLabel || '');
}

// App.jsx 中真实注册的全部路由（HashRouter 下即 `#/xxx`）。
// 菜单管理页新增 / 编辑菜单项时用它校验 path，避免配出点击后空白的死链。
// 新增页面路由时，请同步在这里补一行。
export const ROUTES = [
  '/workbench', '/dashboard',
  '/orders', '/stations', '/assets', '/asset-trace', '/manufacturer', '/recovery', '/insurance',
  '/operator', '/shared-pool', '/swap-orders', '/ledger', '/users', '/complaints', '/deposits',
  '/capacity-booking', '/station-contracts',
  '/settlements', '/payments', '/reconciliations', '/countries', '/risk', '/alerts', '/custody',
  '/arbitration', '/profit', '/fee', '/roles', '/permission', '/settings', '/product-iot',
  '/product-center', '/project-management', '/certificate', '/bind-ownership', '/device-data-access',
  '/task-drone', '/task-logi', '/task-ad', '/task-video', '/task-rent', '/task-near',
  '/authorization',   '/goods-list', '/product-wizard', '/product-publish',
  '/order-manage', '/brand-onboarding', '/app-portal', '/menu-manager',
  '/asset-params', '/departments', '/categories',
  // 增量 B · 库存 / 流转 / 渠道域
  '/production', '/mfg-inventory', '/station-consignment', '/transfers', '/fulfillment-orders',
  '/pickup-scan', '/commission-rules',
  // 模块三 · 库存总览（首位默认入口，sort_no=450）
  '/inventory-overview',
  // 模块四 · 服务站功能（库存 / 项目 / 结算三层解耦，menu:* 权限码见 V68 迁移）
  '/station-inventory', '/station-projects', '/station-settlements',
  // 增量 A · 权限骨架
  '/role-templates', '/role-groups', '/principal-bindings',
  // Phase 2 骨架
  '/merchants',
  // 增量 C · 入驻管理（7 个新页面，menu:* 权限码见 V62 迁移）
  '/onboarding-apply', '/onboarding-review', '/onboarding-content', '/onboarding-deposit-tiers',
  '/onboarding-deposit-confirm', '/org-manage', '/sub-accounts',
  // 增量 D · 无人机 / 低空经济域（menu:* 权限码见 V66 迁移）
  '/airspace-zones', '/flight-plans', '/pilot-licenses', '/drone-ops',
  // 摄像头 / 录像域（数据回放，menu:camera-playback 权限码见 V91 迁移）
  '/camera-playback',
  // 能源运营（光伏 / 虚拟电厂 / 追溯 / 能源品类，menu:* 权限码见 V116 / V117 迁移）
  '/pv-station', '/pv-trace', '/vpp', '/product-energy',
  // T8 · 车辆（地面自动驾驶 / 换电）域（menu:* 权限码见 V120 迁移）
  '/vehicle-product-classes', '/vehicle-trajectory', '/task-vehicle',
];

// 拍平为「带父级标签」的搜索索引：[{ key,label,path,group,groupKey }]，递归展开所有叶子。
// 注意：label / group 存的是原始值（i18n key 或用户自定义文案），
// 展示前请经 navLabel() / groupLabel() 解析，否则语言切换不会刷新。
export const FLAT_NAV = (() => {
  const out = [];
  const walk = (nodes, groupKey, groupLabel) => {
    for (const n of nodes) {
      if (n.children) walk(n.children, groupKey, groupLabel);
      else if (n.path) out.push({ key: n.key, label: n.label, path: n.path, group: groupLabel, groupKey });
    }
  };
  for (const g of NAV) {
    if (g.children) walk(g.children, g.key, g.label);
    else if (g.path) {
      out.push({ key: g.key, label: g.label, path: g.path, group: HOME_GROUP_LABEL, groupKey: HOME_GROUP_KEY });
    }
  }
  return out;
})();

// 收集某选中 key 的所有祖先分组 key（用于自动展开二级菜单）
export const ancestorKeysOf = (key) => {
  const result = [];
  const walk = (nodes, trail) => {
    for (const n of nodes) {
      if (n.key === key) { result.push(...trail); return true; }
      if (n.children && walk(n.children, [...trail, n.key])) return true;
    }
    return false;
  };
  walk(NAV, []);
  return result;
};
