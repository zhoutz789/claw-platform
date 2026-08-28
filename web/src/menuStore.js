// 可配置导航存储：在 nav.js 的 BASE_NAV 之上提供「用户自定义覆盖」。
// - 覆盖保存在 localStorage，刷新/重开仍生效；
// - 通过订阅机制让侧边栏、工作台快捷入口在保存后即时刷新；
// - 图标函数无法序列化，渲染时按 key 从 ICON_BY_KEY 回退，新分组兜底 AppstoreOutlined。
import { useState, useEffect } from 'react';
import { NAV as BASE_NAV } from './nav';

// v4（2026-08-28）：周老板要求彻底移除旧四大中心（运营/财务/风控/系统）菜单项。
// 若用户此前在「菜单管理」保存过 v3 覆盖（含旧中心），会持续遮蔽本次 nav.js 的清理结果。
// 升级到 v4 作废任何 v3 覆盖，强制菜单回退到当前 BASE_NAV（仅含 A/B/C/D 期新设计模块）。
const STORAGE_KEY = 'claw_menu_override_v4';

// ---- 覆盖状态（模块级单例） ----
let active = loadInitial();
const listeners = new Set();

function loadInitial() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      const clean = sanitizeNav(parsed);
      // 清洗后为空或不合法 => 退回默认（不使用损坏的覆盖，避免击垮侧边栏）
      if (clean && clean.length) return clean;
    }
  } catch (e) {
    /* 忽略损坏数据 */
  }
  return null; // null => 使用 BASE_NAV
}

// 清洗/修复任意来源的导航结构，保证渲染层永不被坏数据击垮：
// - 必须返回数组；
// - 每个节点必须有字符串 key（无 key 直接丢弃，无法渲染）；
// - 全局去重（相同 key 仅保留首个，避免 antd Menu 报重复 key）；
// - 打断自引用/循环（节点不应出现在自身子树内）；
// - 剥离 icon 等不可序列化字段；空 children 不保留。
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

export function getNav() {
  return active || BASE_NAV;
}

export function setNav(nav) {
  active = nav;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(nav));
  } catch (e) {
    /* 忽略写入失败 */
  }
  emit();
}

export function resetNav() {
  active = null;
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch (e) {
    /* 忽略 */
  }
  emit();
}

function emit() {
  listeners.forEach((fn) => fn());
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

// React hook：组件随导航变更重渲染
export function useMenuNav() {
  const [nav, setNavState] = useState(getNav());
  useEffect(() => subscribe(() => setNavState(getNav())), []);
  return nav;
}

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

// 跳过隐藏节点（递归；整组隐藏或无可见子项则剔除）
export function filterHidden(nodes) {
  const out = [];
  for (const n of nodes) {
    if (n.hidden) continue;
    if (n.children) {
      const kids = filterHidden(n.children);
      if (kids.length) out.push({ ...n, children: kids });
    } else {
      out.push(n);
    }
  }
  return out;
}

export function getVisibleNav() {
  return filterHidden(getNav());
}

// 拍平为搜索/快捷入口索引（与 nav.js 的 FLAT_NAV 语义一致，但遵循隐藏与覆盖）
export function flattenNav(nodes, group, out = []) {
  for (const n of nodes) {
    if (n.children && n.children.length) {
      flattenNav(n.children, group || n.label, out);
    } else if (n.path && !n.hidden) {
      out.push({ key: n.key, label: n.label, path: n.path, group: group || '首页' });
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
