// 前端三语（中 / 英 / 柬）i18n 初始化与公共出口。
//
// 设计要点（对应《技术架构增量设计 v3.0》B2 节）：
//   1. 语言包按「模块命名空间」组织，不按页面：目前 3 个 ns（common / nav / system），
//      后续按 B2.2 追加 ops / finance / risk ... 只需在此处加一行 resources + ns。
//   2. 回退链固定为 km → en → zh。zh 是完整基准包，任何缺失最终都回落中文，
//      因此「迁移到一半的页面」切英文只会是「部分英文部分中文」，绝不白屏、绝不报错。
//   3. 生产环境缺失 key 返回空串，绝不把内部 key 暴露到页面上；开发环境显示 ⚠key 便于补齐。
//
// 关于「柬语留空」的约定：
//   涉及押金 / 分账 / 风控 / 合同 / 法务等法律责任类术语，km 包里一律写空字符串 ""。
//   配合 returnEmptyString:false，空串会自动回退到 en → zh，页面显示英文而非错误译文。
//   这些空串就是「待专业译员补齐」的清单，可直接 grep locales/km 里的 "" 导出。
import i18next from 'i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import { initReactI18next } from 'react-i18next';
import dayjs from 'dayjs';

import zhCN from 'antd/locale/zh_CN';
import enUS from 'antd/locale/en_US';
import kmKH from 'antd/locale/km_KH';

import 'dayjs/locale/zh-cn';
import 'dayjs/locale/en';
import 'dayjs/locale/km';

import zhCommon from './locales/zh/common.json';
import zhNav from './locales/zh/nav.json';
import zhSystem from './locales/zh/system.json';
import zhSupply from './locales/zh/supply.json';
import zhDrone from './locales/zh/drone.json';
import enCommon from './locales/en/common.json';
import enNav from './locales/en/nav.json';
import enSystem from './locales/en/system.json';
import enSupply from './locales/en/supply.json';
import enDrone from './locales/en/drone.json';
import kmCommon from './locales/km/common.json';
import kmNav from './locales/km/nav.json';
import kmSystem from './locales/km/system.json';
import kmSupply from './locales/km/supply.json';
import kmDrone from './locales/km/drone.json';

/** localStorage 中保存用户显式选择的语言所用的键。 */
export const LANG_STORAGE_KEY = 'claw_lang';

/** 平台支持的语言（顺序即切换器展示顺序）。 */
export const SUPPORTED_LANGS = ['zh', 'en', 'km'];

/** 兜底语言：未登录、无历史选择、无账号配置时的默认语言（中文）。 */
export const DEFAULT_LANG = 'zh';

/** 切换器选项。native 为各语言自称，不随当前语言变化，保证任何语言下都能认出自己的母语。 */
export const LANG_OPTIONS = [
  { value: 'zh', native: '中文' },
  { value: 'en', native: 'English' },
  { value: 'km', native: 'ខ្មែរ' },
];

/** antd ConfigProvider locale 映射（antd 5.29.3 内置 km_KH，组件层三语零成本）。 */
export const ANTD_LOCALE = { zh: zhCN, en: enUS, km: kmKH };

/** dayjs locale 映射（日期选择器 / 相对时间文案跟随切换）。 */
export const DAYJS_LOCALE = { zh: 'zh-cn', en: 'en', km: 'km' };

/** 后端 Accept-Language 映射。 */
export const ACCEPT_LANGUAGE = { zh: 'zh-CN', en: 'en-US', km: 'km-KH' };

const isDev = Boolean(import.meta.env && import.meta.env.DEV);

/**
 * 语言包资源。新增命名空间时在此登记即可（键为语言，值为 ns → 词条对象）。
 * 采用静态 import：构建期内联，避免异步加载命名空间导致的「首帧 key 未就绪」闪烁。
 */
const resources = {
  zh: { common: zhCommon, nav: zhNav, system: zhSystem, supply: zhSupply, drone: zhDrone },
  en: { common: enCommon, nav: enNav, system: enSystem, supply: enSupply, drone: enDrone },
  km: { common: kmCommon, nav: kmNav, system: kmSystem, supply: kmSupply, drone: kmDrone },
};

i18next
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources,
    ns: ['common', 'nav', 'system', 'supply', 'drone'],
    defaultNS: 'common',
    supportedLngs: SUPPORTED_LANGS,
    // 回退链：km 缺失 → en，en 缺失 → zh；zh 为完整基准包。
    fallbackLng: { km: ['en', 'zh'], en: ['zh'], zh: ['zh'], default: ['zh'] },
    // 兼容 zh-CN / en-US 等带区域后缀的输入，统一归到主语言。
    nonExplicitSupportedLngs: true,
    interpolation: { escapeValue: false },
    // 关键：值为空字符串时视为「未翻译」，继续走回退链（km 留空的专业术语即依赖此行为）。
    returnEmptyString: false,
    returnNull: false,
    detection: {
      // 只认用户显式选择（localStorage）。不启用 navigator 探测，保证登录页默认中文；
      // 后续若要「跟随浏览器语言」，在 order 里加 'navigator' 即可。
      order: ['localStorage'],
      lookupLocalStorage: LANG_STORAGE_KEY,
      // 不回写缓存：避免浏览器语言被固化后压过 users.locale。
      caches: [],
    },
    // 缺失 key：开发环境显示 ⚠key 便于补齐；生产环境返回空串，绝不暴露内部 key。
    parseMissingKeyHandler: (key) => {
      console.warn('[i18n] 缺失文案 key:', key);
      return isDev ? `⚠${key}` : '';
    },
    saveMissing: false,
  });

/**
 * 规范化语言代码：未知 / 不支持的一律回落默认语言。
 * @param {string} [lang] 待规范化的语言代码
 * @returns {string} 支持的语言代码
 */
export function normalizeLang(lang) {
  const raw = String(lang || '').trim().toLowerCase();
  if (SUPPORTED_LANGS.includes(raw)) return raw;
  const base = raw.split(/[-_]/)[0];
  return SUPPORTED_LANGS.includes(base) ? base : DEFAULT_LANG;
}

/**
 * 取得当前生效语言。
 * @returns {string} 语言代码（zh / en / km）
 */
export function getLang() {
  return normalizeLang(i18next.resolvedLanguage || i18next.language);
}

/**
 * 切换语言并联动 antd / dayjs / <html lang>。
 * 仅负责「本地」部分，后端 users.locale 回写见 useLang.js 的 useLang()。
 * @param {string} lang 目标语言代码
 * @param {Object} [options] 选项
 * @param {boolean} [options.persist=true] 是否写入 localStorage（显式选择才写）
 * @returns {Promise<string>} 实际生效的语言
 */
export async function setLang(lang, options = {}) {
  const { persist = true } = options;
  const next = normalizeLang(lang);
  try {
    if (persist) localStorage.setItem(LANG_STORAGE_KEY, next);
  } catch (e) {
    /* 隐私模式下 localStorage 可能不可写，忽略即可 */
  }
  if (i18next.language !== next) await i18next.changeLanguage(next);
  applyLocaleSideEffects(next);
  return next;
}

/**
 * 语言切换的副作用：dayjs 全局 locale + <html lang> 属性。
 * antd locale 由 ConfigProvider 在 React 层切换（见 main.jsx）。
 * @param {string} lang 语言代码
 * @returns {void}
 */
export function applyLocaleSideEffects(lang) {
  const next = normalizeLang(lang);
  dayjs.locale(DAYJS_LOCALE[next]);
  if (typeof document !== 'undefined' && document.documentElement) {
    document.documentElement.lang = ACCEPT_LANGUAGE[next];
  }
}

/**
 * 在非 React 环境（普通模块、工具函数、nav.js 等）取文案。
 * 组件内请优先使用 useTranslation() 的 t，以便随语言切换自动重渲染。
 * @param {string} key i18n key，支持 ns 前缀（如 'system:users.title'）
 * @param {Object} [vars] 插值变量与 defaultValue
 * @returns {string} 译文，缺失时返回 defaultValue 或空串
 */
export function tv(key, vars) {
  if (!key) return '';
  const out = i18next.t(key, vars);
  return typeof out === 'string' ? out : '';
}

// 首次加载时先落一次副作用，保证 dayjs / <html lang> 与初始语言一致。
applyLocaleSideEffects(getLang());

export default i18next;
