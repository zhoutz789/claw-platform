// 前端权限内核：登录后从后端拉取「我的权限」并集中管理。
//
// 设计要点（对应《技术架构增量设计 v3.0》权限通电 P1-T05）：
//   1. 单一数据源：登录后调用 GET /v1/admin/permissions/mine 取得
//      { userId, roles, permissions: string[], menu: [...] }，全前端共享。
//   2. 降级（degradation）：当拉取失败（后端未起 / dev-open-access 模式 / 接口缺失）
//      时，置 allGranted=true，即「所有权限视为已授予」。这是硬性约束 ——
//      绝不能因为权限接口挂了，就让现有 54 个页面被权限闸门卡死、白屏。
//      此时菜单走本地默认（getVisibleNav），页面全部可用。
//   3. 缓存：成功结果写入 localStorage，刷新后先水合（hydration）避免闪烁，
//      同时仍会在 bootstrap 再拉一次以对齐服务端最新授权。
//   4. 订阅：store 变更通过 listeners 通知 React（usePerm / useEffectiveNav 等 hook 重渲染）。
//
// 权限码约定：{resource}:{action}，如 asset:create / asset:update / asset:delete / asset:export；
// 菜单可见性用 menu:{navKey}；超级管理员持有通配符 "*"。
import { useEffect, useState } from 'react';
import api from './api';
import { isAuthed } from './auth';
import { setRemoteNav } from './menuStore';

const STORAGE_KEY = 'claw_perms_v1';

// ---- 模块级单例状态 ----
/** 已解析的权限码集合（不含通配符，单独判断 "*"）。 */
let permissions = new Set();
/** 角色列表（展示用）。 */
let roles = [];
/** 后端权威菜单树（已按 menu:{key} 过滤），null 表示尚未设置。 */
let menu = null;
/** 降级开关：true 时 hasPerm 对任意码返回 true。 */
let allGranted = false;
/** 是否已至少尝试过一次加载（用于区分「未登录」与「加载中」）。 */
let loaded = false;
/** 是否正在加载中（防重入）。 */
let loading = false;
/** 版本号：每次状态变更 +1，作为 useSyncExternalStore 快照与 hook 强制重渲染依据。 */
let version = 0;

const listeners = new Set();

function emit() {
  version += 1;
  listeners.forEach((fn) => fn());
}

/**
 * 订阅权限状态变更。
 * @param {Function} fn 变更回调（无参）
 * @returns {Function} 取消订阅函数
 */
export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

// ---- 水合：刷新后从 localStorage 恢复，避免首帧无权限闪烁 ----
function hydrate() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return;
    const obj = JSON.parse(raw);
    permissions = new Set(Array.isArray(obj.permissions) ? obj.permissions : []);
    roles = Array.isArray(obj.roles) ? obj.roles : [];
    allGranted = Boolean(obj.allGranted);
    loaded = Boolean(obj.loaded);
    // menu 不落盘：体积大且服务端权威，刷新后重新拉取。
  } catch (e) {
    /* 损坏数据忽略 */
  }
}
hydrate();

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      permissions: [...permissions],
      roles,
      allGranted,
      loaded,
    }));
  } catch (e) {
    /* 隐私模式等忽略 */
  }
}

/**
 * 清空权限状态（登出时调用）。
 * @returns {void}
 */
export function resetPermissions() {
  permissions = new Set();
  roles = [];
  menu = null;
  allGranted = false;
  loaded = false;
  loading = false;
  try { localStorage.removeItem(STORAGE_KEY); } catch (e) { /* ignore */ }
  emit();
}

/**
 * 从后端加载「我的权限」。
 * 成功：写入权限码 / 角色 / 后端权威菜单，并下发到 menuStore。
 * 失败（任何异常）：降级为 allGranted=true，保证页面不被卡死。
 * 未登录：直接清空。
 * @returns {Promise<void>} 永不 reject
 */
export async function loadPermissions() {
  if (loading) return;
  if (!isAuthed()) { resetPermissions(); return; }
  loading = true;
  try {
    const resp = await api.get('/v1/admin/permissions/mine');
    const perms = Array.isArray(resp.permissions) ? resp.permissions : [];
    const remoteRoles = Array.isArray(resp.roles) ? resp.roles : [];
    const remoteMenu = Array.isArray(resp.menu) ? resp.menu : null;
    permissions = new Set(perms);
    roles = remoteRoles;
    menu = remoteMenu;
    allGranted = false;
    loaded = true;
    if (remoteMenu && remoteMenu.length) {
      setRemoteNav(remoteMenu);
    }
    persist();
  } catch (e) {
    // 降级：后端不可达 / 接口缺失 / 其它异常 —— 全部视为已授权。
    allGranted = true;
    loaded = true;
    if (import.meta.env && import.meta.env.DEV) {
      console.warn('[permStore] 拉取 /permissions/mine 失败，已降级为「全部放行」：', e);
    }
  } finally {
    loading = false;
    emit();
  }
}

// ---- 查询 API ----

/** 是否处于降级（全部放行）模式。 */
export function isAllGranted() { return allGranted; }
/** 是否已加载过。 */
export function hasLoaded() { return loaded; }
/** 当前角色列表。 */
export function getRoles() { return roles; }
/** 后端权威菜单树（可能为 null）。 */
export function getMenu() { return menu; }
/** 当前权限码数组（降级模式返回空数组，请用 isAllGranted 判定语义）。 */
export function getPermissions() { return [...permissions]; }

/**
 * 是否持有某权限码。降级模式 / 通配符 / 空码均视为通过。
 * @param {string} code 权限码
 * @returns {boolean}
 */
export function hasPerm(code) {
  if (allGranted) return true;
  if (!code) return true;
  if (permissions.has(code)) return true;
  if (permissions.has('*')) return true;
  return false;
}

/**
 * 持有指定码中的任意一个即算通过（OR 语义）。
 * @param {string[]} codes 权限码列表
 * @returns {boolean}
 */
export function hasAnyPerm(codes = []) {
  if (allGranted) return true;
  if (!codes || !codes.length) return true;
  return codes.some((c) => hasPerm(c));
}

/**
 * 必须持有指定的全部权限码（AND 语义）。
 * @param {string[]} codes 权限码列表
 * @returns {boolean}
 */
export function hasAllPerm(codes = []) {
  if (allGranted) return true;
  if (!codes || !codes.length) return true;
  return codes.every((c) => hasPerm(c));
}

// ---- React hooks ----

/**
 * 订阅权限状态变更的强制重渲染 hook（无需返回值，仅用于触发重渲染）。
 * @returns {number} 当前版本号
 */
export function usePermVersion() {
  const [, force] = useState(0);
  useEffect(() => subscribe(() => force((v) => v + 1)), []);
  return version;
}

/**
 * 查询某权限码是否持有（响应式）。
 * @param {string} code 权限码
 * @returns {boolean}
 */
export function usePerm(code) {
  usePermVersion();
  return hasPerm(code);
}

/**
 * 响应式判断「持有任意 / 全部」权限码。
 * @param {Object} [opts]
 * @param {string[]} [opts.any] 任意其一即可
 * @param {string[]} [opts.all] 必须全部
 * @returns {boolean}
 */
export function usePerms({ any = [], all = [] } = {}) {
  usePermVersion();
  if (any && any.length) return hasAnyPerm(any);
  if (all && all.length) return hasAllPerm(all);
  return true;
}

export default {
  loadPermissions,
  resetPermissions,
  hasPerm,
  hasAnyPerm,
  hasAllPerm,
  isAllGranted,
  hasLoaded,
  getRoles,
  getPermissions,
  getMenu,
  usePerm,
  usePerms,
  usePermVersion,
  subscribe,
};
