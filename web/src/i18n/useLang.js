// 语言持久化 + 与后端 users.locale 的双向同步。
//
// 优先级链（高 → 低）：
//   1. localStorage 'claw_lang'      —— 用户手动切过就固定（游客态也生效）
//   2. users.locale（登录后 /auth/me）—— 跟随账号配置
//   3. 兜底 'zh'                     —— 登录页默认中文
//
// 后端说明（本批次不改后端）：
//   - GET  /api/v1/auth/me 目前只返回 Long userId；T02 会扩成 {userId,phone,locale,...}。
//     这里做前向兼容：响应里没有 locale 字段就静默跳过，等 T02 落地后自动生效。
//   - PUT  /api/v1/users/me/locale 目前尚未提供（T02 新增）。这里做「发后不理」，
//     失败仅打日志，不影响前端切换与本地持久化。
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import api from '../api';
import {
  DEFAULT_LANG, LANG_STORAGE_KEY, getLang, normalizeLang, setLang,
} from './index';

/** 演示态 token：此时没有真实账号，不做后端同步。 */
const DEMO_TOKEN = 'dev-mock-token';

/** 本会话是否已尝试过拉取用户档案（避免重复请求）。 */
let profileFetched = false;
/** 后端是否已明确不支持写回 locale（避免每次切换都打一个 404）。 */
let localeWriteDisabled = false;

/**
 * 是否已存在「用户显式选择」的语言。
 * @returns {boolean} true 表示 localStorage 里存过选择
 */
export function hasExplicitChoice() {
  try {
    return Boolean(localStorage.getItem(LANG_STORAGE_KEY));
  } catch (e) {
    return false;
  }
}

/**
 * 是否处于「真实登录态」（演示 token 不算）。
 * @returns {boolean} true 表示有真实 token
 */
function isReallyLoggedIn() {
  try {
    const t = localStorage.getItem('claw_token');
    return Boolean(t) && t !== DEMO_TOKEN;
  } catch (e) {
    return false;
  }
}

/**
 * 已登录后用账号档案里的 locale 覆盖本地语言（仅当用户从未手动切换过）。
 *
 * 只在「本地无显式选择」时生效，保证用户的手动选择优先级最高。
 * @returns {Promise<string>} 最终生效的语言
 */
export async function applyUserLocale() {
  const currentLang = getLang();
  if (profileFetched || !isReallyLoggedIn() || hasExplicitChoice()) return currentLang;
  profileFetched = true;
  try {
    const me = await api.get('/v1/auth/me');
    const locale = me && typeof me === 'object' ? me.locale : null;
    if (!locale) return currentLang;
    const next = normalizeLang(locale);
    if (next === currentLang) return currentLang;
    // 档案语言不写入 localStorage：它属于「跟随账号」，下次仍可被手动切换覆盖。
    return setLang(next, { persist: false });
  } catch (e) {
    // 后端不可达 / 接口未开放：静默沿用本地语言，绝不影响页面可用性。
    return currentLang;
  }
}

/**
 * 把语言回写到用户档案（发后不理）。
 * @param {string} lang 语言代码
 * @returns {Promise<void>} 永不 reject
 */
export async function syncUserLocale(lang) {
  if (localeWriteDisabled || !isReallyLoggedIn()) return;
  try {
    await api.put('/v1/users/me/locale', { locale: normalizeLang(lang) });
  } catch (e) {
    // 接口尚未提供（T02 才新增）或后端不可达：禁用后续回写，避免日志噪音。
    localeWriteDisabled = true;
    if (import.meta.env && import.meta.env.DEV) {
      console.info('[i18n] 后端尚未提供 PUT /v1/users/me/locale，本次仅本地持久化语言。');
    }
  }
}

/**
 * React hook：读取当前语言并提供切换能力。
 *
 * 组件内使用 useTranslation 订阅语言变更，切换后所有已接入 i18n 的文案自动重渲染。
 * @returns {{lang: string, changeLang: (lang: string) => Promise<void>}} 当前语言与切换函数
 */
export function useLang() {
  const { i18n } = useTranslation();
  const [lang, setLangState] = useState(getLang);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  // 订阅 i18next 语言变更（覆盖「登录成功后应用 users.locale」这种非本组件触发的切换）
  useEffect(() => {
    const onChanged = () => { if (mounted.current) setLangState(getLang()); };
    i18n.on('languageChanged', onChanged);
    return () => i18n.off('languageChanged', onChanged);
  }, [i18n]);

  // 登录后（真实 token）尝试跟随账号档案语言
  useEffect(() => {
    applyUserLocale().then((next) => {
      if (mounted.current) setLangState(next);
    });
  }, []);

  const changeLang = useCallback(async (next) => {
    const applied = await setLang(next, { persist: true });
    if (mounted.current) setLangState(applied);
    await syncUserLocale(applied);
  }, []);

  return { lang, changeLang };
}
