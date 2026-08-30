// 库存 / 流转 / 渠道域（增量 B）页面共享的取数与展示工具。
//
// 只做三件事，避免 9 个新页各写一遍：
//   1. 下拉选项（厂家 / 服务站 / 商品）统一从真实后端拉取，并提供 id → 名称 的解析函数；
//   2. 后端 Instant（ISO-8601 UTC 字符串）统一格式化，空值统一显示占位符；
//   3. 状态枚举统一渲染为带颜色的 Tag（文案走 i18n，取不到时原样显示枚举值）。
import { useMemo } from 'react';
import { Tag } from 'antd';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useFetch } from '../hooks';
import { listManufacturers, listStations, listProducts } from '../api/supplyChain';
import { enumLabel } from '../enums';

/** 空值占位符。 */
export const EMPTY = '—';

/**
 * 格式化后端返回的时间（Instant，ISO-8601 UTC 字符串）。
 * 空 / 非法值统一返回占位符，避免页面出现 "Invalid Date"。
 * @param {string|number|null|undefined} v 时间值
 * @param {string} [pattern] dayjs 输出格式，默认 'YYYY-MM-DD HH:mm'
 * @returns {string} 格式化后的时间文本
 */
export function fmtTime(v, pattern = 'YYYY-MM-DD HH:mm') {
  if (v === null || v === undefined || v === '') return EMPTY;
  const d = dayjs(v);
  return d.isValid() ? d.format(pattern) : EMPTY;
}

/**
 * 安全地把输入解析为整数（用于下拉里手动输入的 ID）。
 * @param {*} v 输入值
 * @returns {number|undefined} 解析成功返回数字，否则 undefined
 */
export function toInt(v) {
  if (v === null || v === undefined || v === '') return undefined;
  const n = Number.parseInt(String(v).trim(), 10);
  return Number.isNaN(n) ? undefined : n;
}

/**
 * 把后端数组统一成数组（防御后端返回 null）。
 * @param {*} v 任意值
 * @returns {Array} 数组
 */
export function asArray(v) {
  return Array.isArray(v) ? v : [];
}

/**
 * 加载「厂家 / 服务站 / 商品」三类下拉选项，并提供 id → 名称 的解析函数。
 *
 * 三个请求并发发出，任一失败由 useFetch 统一提示，对应下拉退化为空，
 * 不影响页面其它部分渲染。
 *
 * @returns {{
 *   manufacturers: Array<Object>,
 *   stations: Array<Object>,
 *   products: Array<Object>,
 *   manufacturerOptions: Array<{label: string, value: number}>,
 *   stationOptions: Array<{label: string, value: number}>,
 *   productOptions: Array<{label: string, value: number}>,
 *   manufacturerName: (id: number|string|null|undefined) => string,
 *   stationName: (id: number|string|null|undefined) => string,
 *   productName: (id: number|string|null|undefined) => string,
 *   loading: boolean
 * }} 选项与解析函数
 */
export function useSupplyOptions() {
  const mfg = useFetch(() => listManufacturers());
  const st = useFetch(() => listStations());
  const prod = useFetch(() => listProducts());

  const manufacturerOptions = useMemo(
    () => asArray(mfg.data).map((m) => ({ label: m.name || m.code || `#${m.id}`, value: m.id })),
    [mfg.data]
  );
  const stationOptions = useMemo(
    () => asArray(st.data).map((s) => ({ label: s.name || s.code || `#${s.id}`, value: s.id })),
    [st.data]
  );
  const productOptions = useMemo(
    () => asArray(prod.data).map((p) => ({ label: p.name || p.model || `#${p.id}`, value: p.id })),
    [prod.data]
  );

  const manufacturerName = useMemo(() => {
    const map = new Map(asArray(mfg.data).map((m) => [m.id, m.name || m.code || `#${m.id}`]));
    return (id) => (id === null || id === undefined || id === '' ? EMPTY : (map.get(Number(id)) || `#${id}`));
  }, [mfg.data]);

  const stationName = useMemo(() => {
    const map = new Map(asArray(st.data).map((s) => [s.id, s.name || s.code || `#${s.id}`]));
    return (id) => (id === null || id === undefined || id === '' ? EMPTY : (map.get(Number(id)) || `#${id}`));
  }, [st.data]);

  const productName = useMemo(() => {
    const map = new Map(asArray(prod.data).map((p) => [p.id, p.name || p.model || `#${p.id}`]));
    return (id) => (id === null || id === undefined || id === '' ? EMPTY : (map.get(Number(id)) || `#${id}`));
  }, [prod.data]);

  return {
    manufacturers: asArray(mfg.data),
    stations: asArray(st.data),
    products: asArray(prod.data),
    manufacturerOptions,
    stationOptions,
    productOptions,
    manufacturerName,
    stationName,
    productName,
    loading: mfg.loading || st.loading || prod.loading,
  };
}

/**
 * 枚举状态标签：文案经 i18n 翻译（缺失时回退显示枚举原值），颜色可自定义。
 * @param {Object} props 组件属性
 * @param {string} props.value 枚举值
 * @param {Object} props.labelMap i18n key 映射
 * @param {Object} [props.colorMap] 枚举值 → antd Tag 颜色
 * @returns {JSX.Element} 标签
 */
export function EnumTag({ value, labelMap, colorMap }) {
  const { t } = useTranslation(['common', 'supply']);
  if (value === null || value === undefined || value === '') return <span>{EMPTY}</span>;
  const text = enumLabel(labelMap, value);
  return <Tag color={colorMap && colorMap[value]}>{text}</Tag>;
}

export default { fmtTime, toInt, asArray, useSupplyOptions, EnumTag, EMPTY };
