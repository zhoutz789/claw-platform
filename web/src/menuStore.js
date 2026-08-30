// 可配置导航存储：在 nav.js 的 BASE_NAV 之上提供「用户自定义覆盖」。
// - 覆盖保存在 localStorage，刷新/重开仍生效；
// - 通过订阅机制让侧边栏、工作台快捷入口在保存后即时刷新；
// - 图标函数无法序列化：覆盖里只存图标名字符串，渲染时经 ICON_REGISTRY / ICON_BY_KEY 还原。
//
// v5（2026-08-29）重大变更：覆盖策略由「整包替换」改为「增量合并」。
//   旧问题：v3 → v4 时靠升级 STORAGE_KEY 版本号作废用户配置，导致周老板在「菜单管理」里
//   保存过的显示/排序设置被整体清空，且以后每加一个新菜单都要再升一次版本号。
//   新策略：STORAGE_KEY 虽升到 v5，但会在首次读取时迁移 v4/v3/v2/v1 的旧覆盖，
//   并通过 mergeWithBase() 与当前 BASE_NAV 做增量合并：
//     · BASE_NAV 是「菜单全集」的权威来源 —— 新增的菜单项一定会出现（追加到本层末尾），
//       用户只能隐藏，不能把菜单从数据源里删没；
//     · 用户在覆盖里的个性化字段（hidden / 改名后的 label / 自定义 path / 自选图标）按 key 合并；
//     · 用户调整过的顺序与跨分组归类原样保留（拖拽、上移/下移继续有效）；
//     · 覆盖里存在但 BASE_NAV 已删除的项，若非用户自建（custom: true）则丢弃，避免旧残留复活。
import { useState, useEffect } from 'react';
import {
  AppstoreOutlined, DashboardOutlined, DeploymentUnitOutlined, AccountBookOutlined,
  SafetyOutlined, SettingOutlined, TeamOutlined, UserOutlined, SafetyCertificateOutlined,
  DatabaseOutlined, ToolOutlined, GoldOutlined, BankOutlined, CarOutlined, ThunderboltOutlined,
  GlobalOutlined, BuildOutlined, FileTextOutlined, BarChartOutlined, BellOutlined,
  CloudServerOutlined, ApiOutlined, ClusterOutlined, WalletOutlined, FundOutlined,
  PieChartOutlined, TagsOutlined, HomeOutlined, FolderOutlined, FlagOutlined, GiftOutlined,
  ReconciliationOutlined, TransactionOutlined, CreditCardOutlined, PercentageOutlined,
  AlertOutlined, LockOutlined, KeyOutlined, IdcardOutlined, ContactsOutlined, SolutionOutlined,
  AuditOutlined, ProfileOutlined, NodeIndexOutlined, MobileOutlined, DesktopOutlined,
  HddOutlined, ExperimentOutlined, EnvironmentOutlined, CompassOutlined, LineChartOutlined,
  TruckOutlined, SwapOutlined, DollarOutlined, ShopOutlined, ProjectOutlined, RocketOutlined,
  ShoppingOutlined, MessageOutlined, WarningOutlined, CalendarOutlined, MenuOutlined,
} from '@ant-design/icons';
import { NAV as BASE_NAV, HOME_GROUP_KEY, HOME_GROUP_LABEL } from './nav';

const STORAGE_KEY = 'claw_menu_override_v5';

// 历史版本键（从新到旧）。首次加载时按序尝试迁移，迁移成功后写回 v5 并清理旧键。
const LEGACY_STORAGE_KEYS = [
  'claw_menu_override_v4',
  'claw_menu_override_v3',
  'claw_menu_override_v2',
  'claw_menu_override_v1',
];

// ---- 图标注册表（必须声明在 loadInitial 之前！） ----
// 注意：sanitizeNav() 在清洗时会读 ICON_REGISTRY 校验图标名，而 sanitizeNav 会被模块级
// 初始化 `let active = loadInitial()` 触发。若把本表声明在下方，模块求值到 loadInitial 时
// ICON_REGISTRY 仍处于 TDZ（暂时性死区），会抛 ReferenceError 并被 readOverride 的 catch
// 静默吞掉 —— 表现为「保存过图标的用户，每次刷新配置全部丢失且页面不报错」。
// 因此图标相关的常量一律前置，下方只允许放函数声明。
//
// 用户在「菜单管理」里为菜单挑图标时，保存的是这里的键名（字符串，可序列化）。
export const ICON_REGISTRY = {
  AppstoreOutlined, DashboardOutlined, DeploymentUnitOutlined, AccountBookOutlined,
  SafetyOutlined, SettingOutlined, TeamOutlined, UserOutlined, SafetyCertificateOutlined,
  DatabaseOutlined, ToolOutlined, GoldOutlined, BankOutlined, CarOutlined, ThunderboltOutlined,
  GlobalOutlined, BuildOutlined, FileTextOutlined, BarChartOutlined, BellOutlined,
  CloudServerOutlined, ApiOutlined, ClusterOutlined, WalletOutlined, FundOutlined,
  PieChartOutlined, TagsOutlined, HomeOutlined, FolderOutlined, FlagOutlined, GiftOutlined,
  ReconciliationOutlined, TransactionOutlined, CreditCardOutlined, PercentageOutlined,
  AlertOutlined, LockOutlined, KeyOutlined, IdcardOutlined, ContactsOutlined, SolutionOutlined,
  AuditOutlined, ProfileOutlined, NodeIndexOutlined, MobileOutlined, DesktopOutlined,
  HddOutlined, ExperimentOutlined, EnvironmentOutlined, CompassOutlined, LineChartOutlined,
  TruckOutlined, SwapOutlined, DollarOutlined, ShopOutlined, ProjectOutlined, RocketOutlined,
  ShoppingOutlined, MessageOutlined, WarningOutlined, CalendarOutlined, MenuOutlined,
};

/** 供菜单管理页图标下拉使用的选项列表：[{ value, label }] */
export const ICON_OPTIONS = Object.keys(ICON_REGISTRY).map((name) => ({ value: name, label: name }));

// ---- 图标回退表（覆盖丢失图标时按 key 取基准图标） ----
export const ICON_BY_KEY = (() => {
  const map = {};
  const walk = (ns) => {
    for (const n of ns) {
      if (n.icon) map[n.key] = n.icon;
      if (n.children) walk(n.children);
    }
  };
  walk(BASE_NAV);
  return map;
})();

// ---- 覆盖状态（模块级单例） ----
// active === null 表示「无自定义覆盖」；否则为已清洗的用户覆盖（不含图标函数）。
let active = loadInitial();
const listeners = new Set();

/**
 * 读取 localStorage 中的覆盖数据；无 v5 数据时迁移历史版本。
 * @returns {Array|null} 清洗后的覆盖数组，无有效数据时返回 null
 */
function loadInitial() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const clean = readOverride(raw);
      if (clean && clean.length) return clean;
    }
  } catch (e) {
    /* localStorage 不可用（隐私模式等），忽略 */
  }
  return migrateLegacy();
}

/**
 * 解析并清洗一段覆盖 JSON 字符串。
 * @param {string} raw JSON 字符串
 * @returns {Array|null} 清洗后的数组；数据损坏或为空时返回 null
 */
function readOverride(raw) {
  try {
    const parsed = JSON.parse(raw);
    const clean = sanitizeNav(parsed);
    return clean && clean.length ? clean : null;
  } catch (e) {
    // 不再静默吞异常：这里一旦出错，用户的整份菜单配置会被无声丢弃、回退默认菜单，
    // 页面却毫无提示（曾导致「明明保存了，刷新就没了」）。留下线索便于定位。
    console.warn('[menuStore] 读取菜单覆盖失败，已回退默认菜单：', e);
    return null;
  }
}

/**
 * 迁移历史版本的覆盖数据：取最新的一个有效旧覆盖，直接沿用为 v5 覆盖（只换存储键，不清空内容）。
 * 不做「合并后落盘」，保持覆盖体积最小 —— 与 BASE_NAV 的合并留给 getNav() 在读取时完成，
 * 这样 nav.js 以后再新增菜单项，仍然能自动出现在用户的菜单里。
 * @returns {Array|null} 迁移后的覆盖数组；无历史数据时返回 null
 */
function migrateLegacy() {
  for (const key of LEGACY_STORAGE_KEYS) {
    let raw = null;
    try {
      raw = localStorage.getItem(key);
    } catch (e) {
      return null;
    }
    if (!raw) continue;
    const old = readOverride(raw);
    if (!old) continue;
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(old));
      // 迁移完成，清理旧键，避免两份数据并存造成歧义
      for (const k of LEGACY_STORAGE_KEYS) localStorage.removeItem(k);
    } catch (e) {
      /* 写入失败不影响本次会话使用 */
    }
    return old;
  }
  return null;
}

// 清洗/修复任意来源的导航结构，保证渲染层永不被坏数据击垮：
// - 必须返回数组；
// - 每个节点必须有字符串 key（无 key 直接丢弃，无法渲染）；
// - 全局去重（相同 key 仅保留首个，避免 antd Menu 报重复 key）；
// - 打断自引用/循环（节点不应出现在自身子树内）；
// - 剥离 icon 函数等不可序列化字段（图标名字符串保留）；空 children 不保留。
export function sanitizeNav(raw, seen = new Set(), trail = new Set()) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const node of raw) {
    if (!node || typeof node !== 'object') continue;
    const key = node.key;
    if (typeof key !== 'string' || !key) continue; // 无 key 丢弃
    if (seen.has(key)) continue; // 重复 key 去重
    seen.add(key);
    const clean = { key, label: typeof node.label === 'string' ? node.label : key };
    if (typeof node.path === 'string') clean.path = node.path;
    if (node.hidden === true) clean.hidden = true;
    // 用户自建项标记：BASE_NAV 中不存在，合并时仍予以保留
    if (node.custom === true) clean.custom = true;
    // 图标：仅接受图标名字符串（函数无法序列化）
    if (typeof node.icon === 'string' && ICON_REGISTRY[node.icon]) clean.icon = node.icon;
    // 防止循环：本节点不应出现在自身子树中（trail 已包含祖先 key）
    if (Array.isArray(node.children) && !trail.has(key)) {
      const kidTrail = new Set(trail);
      kidTrail.add(key);
      const kids = sanitizeNav(node.children, seen, kidTrail);
      if (kids.length) clean.children = kids;
    }
    out.push(clean);
  }
  return out;
}

/**
 * 递归建立 BASE_NAV 的 key -> 节点索引（跨层级），
 * 用于支持用户把菜单项从一个分组拖到另一个分组后仍能找到基准定义。
 * @param {Array} nodes 节点数组
 * @param {Map<string, Object>} [map] 复用的索引表
 * @returns {Map<string, Object>} key 到节点的映射
 */
function indexByKey(nodes, map = new Map()) {
  for (const n of nodes || []) {
    if (!n || typeof n.key !== 'string') continue;
    if (!map.has(n.key)) map.set(n.key, n);
    if (Array.isArray(n.children)) indexByKey(n.children, map);
  }
  return map;
}

/**
 * 单个节点的字段合并：以 BASE_NAV 定义为底，叠加上用户的个性化字段。
 * @param {Object} o 覆盖节点
 * @param {Object} b BASE_NAV 节点
 * @param {Map<string, Object>} baseIndex BASE_NAV 全局索引
 * @param {Set<string>} consumed 已被覆盖消费掉的 key 集合（跨层级共享）
 * @returns {Object} 合并后的节点
 */
function mergeNode(o, b, baseIndex, consumed) {
  const merged = { ...b };
  if (typeof o.label === 'string' && o.label) merged.label = o.label;
  if (typeof o.path === 'string') merged.path = o.path;
  if (o.hidden === true) merged.hidden = true; else delete merged.hidden;
  if (o.custom === true) merged.custom = true;
  if (typeof o.icon === 'string' && ICON_REGISTRY[o.icon]) merged.icon = o.icon;

  const kidsOverride = Array.isArray(o.children) ? o.children : [];
  const kidsBase = Array.isArray(b.children) ? b.children : [];
  if (kidsBase.length || kidsOverride.length) {
    const kids = mergeLevel(kidsOverride, kidsBase, baseIndex, consumed);
    if (kids.length) merged.children = kids;
    else delete merged.children;
  } else {
    delete merged.children;
  }
  return merged;
}

/**
 * 单层合并：以用户覆盖的顺序与层级为准，逐个与 BASE_NAV 同名项合并；
 * BASE_NAV 中未被覆盖消费的项（新增菜单）追加到本层末尾；
 * 覆盖里 BASE_NAV 已删除的项直接丢弃（用户自建项 custom:true 除外）。
 * @param {Array} overrideNodes 用户覆盖的当前层节点
 * @param {Array} baseNodes BASE_NAV 的当前层节点
 * @param {Map<string, Object>} baseIndex BASE_NAV 全局索引
 * @param {Set<string>} consumed 已被消费的 key（跨层级共享，防止同一项重复出现）
 * @returns {Array} 合并后的当前层节点
 */
function mergeLevel(overrideNodes, baseNodes, baseIndex, consumed) {
  const overrideArr = Array.isArray(overrideNodes) ? overrideNodes : [];
  const baseArr = Array.isArray(baseNodes) ? baseNodes : [];
  const out = [];

  // 1) 按用户覆盖的顺序 / 层级重建结构
  for (const o of overrideArr) {
    if (!o || typeof o.key !== 'string') continue;
    const b = baseIndex.get(o.key);
    if (!b) {
      // BASE_NAV 中已无此项：仅保留用户自建项，其余为旧版本残留，丢弃
      if (o.custom === true) {
        consumed.add(o.key);
        const customNode = { ...o };
        const customKids = mergeLevel(
          Array.isArray(o.children) ? o.children : [], [], baseIndex, consumed
        );
        // 空 children 不落库，否则与「无 children」在序列化比对时不等价
        if (customKids.length) customNode.children = customKids;
        else delete customNode.children;
        out.push(customNode);
      }
      continue;
    }
    if (consumed.has(o.key)) continue; // 已在其它层级被消费（用户把它挪走了）
    consumed.add(o.key);
    out.push(mergeNode(o, b, baseIndex, consumed));
  }

  // 2) 补齐 BASE_NAV 中用户覆盖没有的项（导航新增的功能，保证一定出现在侧边栏）
  // 必须深拷贝并沿途登记 consumed：若直接浅拷贝 { ...b }，会把整棵原始子树带回来，
  // 而子树里的某些 key 可能已在别的层级被消费（用户把它拖走了），于是同一项二次出现、
  // 用户的「隐藏」也只作用在副本之一上 —— 表现为「藏了却还在侧边栏」。
  for (const b of baseArr) {
    const cloned = clonePruning(b, consumed);
    if (cloned) out.push(cloned);
  }
  return out;
}

/**
 * 深拷贝一个 BASE_NAV 节点，同时把它和子孙的 key 登记进 consumed。
 * 已在别处出现过的子树直接跳过（返回 null），避免重复 key 与隐藏失效。
 * @param {Object} n BASE_NAV 节点
 * @param {Set<string>} consumed 已被消费的 key（跨层级共享）
 * @returns {Object|null} 拷贝后的节点；该子树已被占用时返回 null
 */
function clonePruning(n, consumed) {
  if (!n || typeof n.key !== 'string') return null;
  if (consumed.has(n.key)) return null;
  consumed.add(n.key);
  const copy = { ...n };
  if (Array.isArray(n.children)) {
    const kids = [];
    for (const kid of n.children) {
      const kidCopy = clonePruning(kid, consumed);
      if (kidCopy) kids.push(kidCopy);
    }
    // 空 children 不保留：与「无 children」序列化等价，避免比对时永远判定为脏
    if (kids.length) copy.children = kids;
    else delete copy.children;
  }
  return copy;
}

/**
 * 增量合并：把用户覆盖合并到 BASE_NAV 之上。
 * @param {Array|null} override 用户覆盖（已清洗），可为 null
 * @param {Array} [base] 基准导航树，默认取 nav.js 的 NAV
 * @returns {Array} 合并后的完整导航树（必含 BASE_NAV 全部项）
 */
export function mergeWithBase(override, base = BASE_NAV) {
  if (!Array.isArray(override) || !override.length) return base;
  const baseIndex = indexByKey(base);
  return mergeLevel(override, base, baseIndex, new Set());
}

/**
 * 取得当前生效的导航树（BASE_NAV 与用户覆盖的合并结果）。
 * @returns {Array} 导航树
 */
export function getNav() {
  return mergeWithBase(active, BASE_NAV);
}

/**
 * 保存用户覆盖并通知所有订阅者。
 * @param {Array} nav 覆盖树（会先经 sanitizeNav 清洗）
 * @returns {void}
 */
export function setNav(nav) {
  const clean = sanitizeNav(nav);
  active = clean.length ? clean : null;
  try {
    if (active) localStorage.setItem(STORAGE_KEY, JSON.stringify(active));
    else localStorage.removeItem(STORAGE_KEY);
  } catch (e) {
    /* 忽略写入失败 */
  }
  emit();
}

/**
 * 清除用户覆盖，回到 nav.js 的默认菜单。
 * @returns {void}
 */
export function resetNav() {
  active = null;
  try {
    localStorage.removeItem(STORAGE_KEY);
    for (const k of LEGACY_STORAGE_KEYS) localStorage.removeItem(k);
  } catch (e) {
    /* 忽略 */
  }
  emit();
}

/**
 * 通知所有订阅者导航已变更。
 * @returns {void}
 */
function emit() {
  listeners.forEach((fn) => fn());
}

/**
 * 订阅导航变更。
 * @param {Function} fn 变更回调
 * @returns {Function} 取消订阅函数
 */
export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/**
 * React hook：组件随导航变更重渲染。
 * @returns {Array} 当前导航树
 */
export function useMenuNav() {
  const [nav, setNavState] = useState(getNav());
  useEffect(() => subscribe(() => setNavState(getNav())), []);
  return nav;
}

/**
 * 解析节点图标为可用的 React 组件。
 * 优先级：节点自带图标（函数或注册表名）> BASE_NAV 同 key 图标 > 兜底图标。
 * @param {Object} node 导航节点
 * @param {Object} [fallback] 兜底图标组件
 * @returns {Object} 图标组件
 */
export function resolveIcon(node, fallback = AppstoreOutlined) {
  const raw = node && node.icon;
  if (typeof raw === 'function') return raw;
  if (typeof raw === 'string' && ICON_REGISTRY[raw]) return ICON_REGISTRY[raw];
  if (node && ICON_BY_KEY[node.key]) return ICON_BY_KEY[node.key];
  return fallback;
}

// 跳过隐藏节点（递归；整组隐藏或无可见子项则剔除）
export function filterHidden(nodes) {
  const out = [];
  for (const n of nodes) {
    if (n.hidden) continue;
    const kids = Array.isArray(n.children) ? filterHidden(n.children) : [];
    if (kids.length) {
      out.push({ ...n, children: kids });
      continue;
    }
    // 无可见子项：有 path 的按叶子渲染（必须先剥掉 children，否则被隐藏的子项会漏出来）；
    // 无 path 的空分组直接剔除，避免出现点了没反应的死菜单
    if (n.path) {
      const leaf = { ...n };
      delete leaf.children;
      out.push(leaf);
    }
  }
  return out;
}

export function getVisibleNav() {
  return filterHidden(getNav());
}

// 拍平为搜索/快捷入口索引（与 nav.js 的 FLAT_NAV 语义一致，但遵循隐藏与覆盖）
// 与 filterHidden 保持一致：分组优先展开子项，子项全部隐藏时才回退成「自身作为叶子」，
// 否则会出现「侧边栏看得到、点下去没反应」的死菜单（索引里没有它）。
//
// i18n 说明：label / group 存的是 nav.js 里的原始值（内置项是 i18n key，用户改过名的是其输入）。
// 展示前必须经 navLabel() / groupLabel() 解析，否则会把 'nav:item.orders' 这样的 key 直接显示给用户。
// groupKey 与 group 成对返回，groupLabel 靠它判断「用户是否改过分组名」。
export function flattenNav(nodes, group, groupKey, out = []) {
  for (const n of nodes) {
    if (n.hidden) continue;
    const kids = [];
    if (n.children && n.children.length) flattenNav(n.children, group || n.label, groupKey || n.key, kids);
    if (kids.length) {
      out.push(...kids);
    } else if (n.path) {
      out.push({
        key: n.key,
        label: n.label,
        path: n.path,
        group: group || HOME_GROUP_LABEL,
        groupKey: groupKey || HOME_GROUP_KEY,
      });
    }
  }
  return out;
}

export function getFlatNav() {
  return flattenNav(getNav());
}

// 收集选中 key 的所有祖先分组 key（用于自动展开）
export function ancestorKeysOfActive(key) {
  const result = [];
  const walk = (nodes, trail) => {
    for (const n of nodes) {
      if (n.key === key) {
        result.push(...trail);
        return true;
      }
      if (n.children && walk(n.children, [...trail, n.key])) return true;
    }
    return false;
  };
  walk(getNav(), []);
  return result;
}

// ---- 后端权威菜单（权限通电 P1-T05） ----
// 登录后由 permStore 下发后端按 menu:{navKey} 过滤过的菜单树；前端在其上叠加本地用户
// 覆盖（隐藏 / 改名 / 排序），从而既服从后端权限，又保留用户个性化布局。
// 未下发（后端未起 / 降级）时回落到本地默认菜单 getVisibleNav()，保证 54 页本地可用。
/** 后端权威菜单树（已按权限过滤），null 表示尚未设置。 */
let remoteNav = null;

/**
 * 设置后端权威菜单树（由 permStore.loadPermissions 成功后调用）。
 * 会经 sanitizeNav 清洗并通知所有订阅者，触发侧边栏 / 工作台即时刷新。
 * @param {Array} nav 后端菜单树（PermissionNode 列表）
 * @returns {void}
 */
export function setRemoteNav(nav) {
  remoteNav = sanitizeNav(nav);
  emit();
}

/**
 * 取得后端权威菜单树（可能为 null）。
 * @returns {Array|null}
 */
export function getRemoteNav() {
  return remoteNav;
}

/**
 * 取得「后端权威 + 本地覆盖」合并后的有效导航树（已剔除隐藏节点）。
 * 无后端菜单时回落到本地默认菜单 getVisibleNav()。
 * @returns {Array} 导航树
 */
export function getEffectiveNav() {
  return filterHidden(mergeWithBase(active, remoteNav || BASE_NAV));
}

/**
 * 拍平有效导航树为搜索 / 快捷入口索引（遵循隐藏与覆盖）。
 * @returns {Array}
 */
export function getEffectiveFlatNav() {
  return flattenNav(getEffectiveNav());
}

/**
 * React hook：组件随「有效导航」变更重渲染（后端菜单下发 / 本地覆盖变更都会触发）。
 * @returns {Array} 当前有效导航树（已剔除隐藏节点）
 */
export function useEffectiveNav() {
  const [nav, setNavState] = useState(() => getEffectiveNav());
  useEffect(() => subscribe(() => setNavState(getEffectiveNav())), []);
  return nav;
}
