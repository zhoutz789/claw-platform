// 无人机 / 低空经济域（增量 D）页面共享的取数 hook、错误映射与展示组件。
//
// 依赖方向铁律：本模块只向下依赖 api/drone.js、hooks、enums、components/supplyShared，
// **不得反向 import 任何 page**，否则会形成 page ↔ shared 的环。
import { useCallback, useMemo } from 'react';
import { App, Empty, Tag } from 'antd';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useFetch } from '../hooks';
import {
  listZones, listFlightPlans, listLicenses, listMissions, listDroneAssets,
} from '../api/drone';
import {
  AIRSPACE_LEVEL_FILL, AIRSPACE_LEVEL_LABEL, AIRSPACE_LEVEL_COLOR,
  licenseValidity,
} from '../enums';
import { asArray, EMPTY } from './supplyShared';

/* ------------------------------ 错误码 → i18n 映射 ------------------------------ */

/**
 * 无人机域业务错误码 → { i18n key, 提示级别 }。
 *
 * 这些码由后端 BizException 产出（增量 D · T05），经 api.js 附加到 Error.bizCode 上。
 * ⚠️ 级别纪律：40961「无未解除事件」是**正常业务提示**走 info，其余走 error。
 *
 * @type {Object<number, {key: string, level: 'error'|'info'}>}
 */
export const DRONE_ERROR_KEYS = {
  40960: { key: 'drone:flightPlan.err.notOperational', level: 'error' },
  40470: { key: 'drone:flightPlan.err.zoneNotFound', level: 'error' },
  40961: { key: 'drone:safety.msg.nothingToResolve', level: 'info' },
  40962: { key: 'drone:license.err.duplicate', level: 'error' },
  // C9：飞行计划提交时资产处于锁机态（v1.2 后端兜底，仅 createFlightPlan），前端权威拦截并提示
  40963: { key: 'drone:flightPlan.err.assetLocked', level: 'error' },
  // 设备围栏 WKT / 坐标入参非法（GeofenceService 4 处裸 IAE 已改 BizException.invalidParam → code 10001）
  10001: { key: 'drone:fence.err.invalid', level: 'error' },
};

/* ------------------------------ 取数 hook ------------------------------ */

/**
 * 加载无人机域五类数据（空域 / 飞行计划 / 资质 / 作业 / 无人机资产），
 * 并提供下拉选项与 id → 名称 的解析函数。
 *
 * 五个请求并发发出（各自独立的 useFetch），任一失败由该 useFetch 自行 message.error，
 * 对应数组降级为 []，页面其余部分照常渲染，不整页白屏。
 * 刻意不做「按需加载 mask」——条件 hook 会引入调用顺序不稳定的风险，收益不抵风险。
 *
 * @returns {{
 *   zones: Array<Object>, flightPlans: Array<Object>, licenses: Array<Object>,
 *   missions: Array<Object>, droneAssets: Array<Object>,
 *   zoneOptions: Array<{label: string, value: number}>,
 *   operationalZoneOptions: Array<{label: string, value: number}>,
 *   pilotOptions: Array<{label: string, value: number, disabled: boolean}>,
 *   assetOptions: Array<{label: string, value: number}>,
 *   zoneName: (id: number|string|null|undefined) => string,
 *   pilotName: (id: number|string|null|undefined) => string,
 *   assetName: (id: number|string|null|undefined) => string,
 *   loading: boolean,
 *   reload: () => void
 * }} 数据、选项与解析函数
 */
export function useDroneOptions() {
  const { t } = useTranslation(['drone']);
  const z = useFetch(() => listZones());
  const fp = useFetch(() => listFlightPlans());
  const lic = useFetch(() => listLicenses());
  const mis = useFetch(() => listMissions());
  const ast = useFetch(() => listDroneAssets());

  const zones = asArray(z.data);
  const flightPlans = asArray(fp.data);
  const licenses = asArray(lic.data);
  const missions = asArray(mis.data);
  const droneAssets = asArray(ast.data);

  const zoneOptions = useMemo(
    () => zones.map((x) => ({ label: x.name || `#${x.id}`, value: x.id })),
    [zones]
  );

  /** 仅「可飞作业区」，供飞行计划下拉使用（第 1 层合规拦截）。 */
  const operationalZoneOptions = useMemo(
    () => zones
      .filter((x) => x.level === 'OPERATIONAL')
      .map((x) => ({ label: x.name || `#${x.id}`, value: x.id })),
    [zones]
  );

  /** 飞手下拉：姓名 · 执照号；已过期的项 disabled + 标注，杜绝给过期资质派活。 */
  const pilotOptions = useMemo(
    () => licenses.map((x) => {
      const expired = licenseValidity(x.expiryDate) === 'EXPIRED';
      const suffix = expired ? ` ${t('drone:flightPlan.msg.expired')}` : '';
      return {
        label: `${x.holderName || `#${x.id}`} · ${x.licenseNo || ''}${suffix}`,
        value: x.id,
        disabled: expired,
      };
    }),
    [licenses, t]
  );

  const assetOptions = useMemo(
    () => droneAssets.map((x) => ({ label: `${x.assetNo} · #${x.id}`, value: x.id })),
    [droneAssets]
  );

  const zoneName = useMemo(() => {
    const map = new Map(zones.map((x) => [Number(x.id), x.name || `#${x.id}`]));
    return (id) => {
      if (id === null || id === undefined || id === '') return EMPTY;
      return map.get(Number(id)) || `#${id}`;
    };
  }, [zones]);

  const pilotName = useMemo(() => {
    const map = new Map(licenses.map((x) => [
      Number(x.id),
      `${x.holderName || `#${x.id}`} · ${x.licenseNo || ''}`.trim(),
    ]));
    return (id) => {
      if (id === null || id === undefined || id === '') return EMPTY;
      return map.get(Number(id)) || `#${id}`;
    };
  }, [licenses]);

  const assetName = useMemo(() => {
    const map = new Map(droneAssets.map((x) => [Number(x.id), `${x.assetNo} · #${x.id}`]));
    return (id) => {
      if (id === null || id === undefined || id === '') return EMPTY;
      return map.get(Number(id)) || `#${id}`;
    };
  }, [droneAssets]);

  const reload = useCallback(() => {
    z.reload();
    fp.reload();
    lic.reload();
    mis.reload();
    ast.reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [z.reload, fp.reload, lic.reload, mis.reload, ast.reload]);

  return {
    zones,
    flightPlans,
    licenses,
    missions,
    droneAssets,
    zoneOptions,
    operationalZoneOptions,
    pilotOptions,
    assetOptions,
    zoneName,
    pilotName,
    assetName,
    loading: z.loading || fp.loading || lic.loading || mis.loading || ast.loading,
    reload,
  };
}

/* ------------------------------ 错误上报 ------------------------------ */

/**
 * 统一错误上报：按 err.bizCode 查 DRONE_ERROR_KEYS 决定文案与提示级别。
 *
 * 用法：const { report } = useDroneError(); ... catch (e) { report(e); }
 * 禁止在页面里裸写 message.error(e.message) —— 会把英文 internal error 漏给用户。
 *
 * @returns {{report: (err: Error, fallbackKey?: string) => void}} 上报函数
 */
export function useDroneError() {
  const { t } = useTranslation(['common', 'drone']);
  const { message } = App.useApp();

  return useMemo(() => ({
    /**
     * @param {Error} err api 抛出的错误（已附加 status / bizCode）
     * @param {string} [fallbackKey] 未命中业务码时使用的 i18n key
     * @returns {void}
     */
    report(err, fallbackKey = 'common:msg.opFailed') {
      const hit = err && DRONE_ERROR_KEYS[err.bizCode];
      const key = hit ? hit.key : fallbackKey;
      const level = hit ? hit.level : 'error';
      const text = t(key, { msg: err && err.message, defaultValue: err && err.message });
      if (level === 'info') message.info(text);
      else message.error(text);
    },
  }), [t, message]);
}

/* ------------------------------ 展示组件 ------------------------------ */

/** 空域鸟瞰图 SVG 画布边长。 */
const MAP_SIZE = 400;
/** 画布内边距。 */
const MAP_PAD = 20;
/** 可用跨度 = SIZE - 2 * PAD。 */
const MAP_SPAN = MAP_SIZE - 2 * MAP_PAD;
/** 纬度 1° 对应的米数（WGS84 平均）。 */
const METERS_PER_DEG = 111320;
/** 比例尺线段像素长度。 */
const SCALE_PX = 70;
/** 绘制顺序：NFZ 最后画，保证禁飞区压在最上层不被可飞区覆盖。 */
const DRAW_ORDER = ['OPERATIONAL', 'RESTRICTED', 'NFZ'];

/**
 * 把输入安全地解析为有限数字。
 * @param {*} v 任意值
 * @returns {number|null} 有限数字，否则 null
 */
const num = (v) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

/**
 * 空域鸟瞰图（纯 SVG，不引地图库 —— Q3 裁定）。
 *
 * 算法：各向同性缩放（degPerPx 取经 / 纬度跨度的较大者），保证圆是圆、相对距离诚实；
 * 纬度向北为屏幕上方；半径按 pxPerMeter 换算并 clamp 到 [2, 180]；
 * 右下角带比例尺，否则用户会误读圆的大小。
 *
 * @param {Object} props 组件属性
 * @param {Array<Object>} props.zones 空域分区列表（用响应字段 centerLat / centerLng）
 * @returns {JSX.Element} 鸟瞰图
 */
export function AirspaceOverview({ zones }) {
  const { t } = useTranslation(['drone']);
  const list = asArray(zones);

  if (list.length === 0) {
    return <Empty description={t('drone:airspace.map.empty')} />;
  }

  const lats = list.map((x) => num(x.centerLat)).filter((n) => n !== null);
  const lngs = list.map((x) => num(x.centerLng)).filter((n) => n !== null);

  // 退化保护：全部坐标缺失或全部相同时，跨度置 1，避免除以 0 产生 NaN / Infinity。
  if (lats.length === 0 && lngs.length === 0) {
    return <Empty description={t('drone:airspace.map.empty')} />;
  }
  const latMin = lats.length ? Math.min(...lats) : 0;
  const latMax = lats.length ? Math.max(...lats) : 0;
  const lngMin = lngs.length ? Math.min(...lngs) : 0;
  const lngMax = lngs.length ? Math.max(...lngs) : 0;
  const latSpan = latMax - latMin > 0 ? latMax - latMin : 1;
  const lngSpan = lngMax - lngMin > 0 ? lngMax - lngMin : 1;

  const degPerPx = Math.max(latSpan, lngSpan) / MAP_SPAN;
  const pxPerMeter = 1 / (METERS_PER_DEG * degPerPx);
  const offX = (MAP_SPAN - lngSpan / degPerPx) / 2;
  const offY = (MAP_SPAN - latSpan / degPerPx) / 2;
  const scaleKm = Math.round((SCALE_PX * degPerPx * METERS_PER_DEG) / 100) / 10;

  const drawable = list
    .map((x) => {
      const lat = num(x.centerLat);
      const lng = num(x.centerLng);
      if (lat === null || lng === null) return null;
      const cx = MAP_PAD + offX + (lng - lngMin) / degPerPx;
      const cy = MAP_PAD + offY + (latMax - lat) / degPerPx;
      const rRaw = (num(x.radiusM) ?? 0) * pxPerMeter;
      const r = Math.min(180, Math.max(2, rRaw));
      return { zone: x, cx, cy, r };
    })
    .filter(Boolean);

  if (drawable.length === 0) {
    return <Empty description={t('drone:airspace.map.empty')} />;
  }

  const grouped = DRAW_ORDER
    .map((level) => drawable.filter((d) => (d.zone.level || 'OPERATIONAL') === level))
    .flat();

  return (
    <svg viewBox={`0 0 ${MAP_SIZE} ${MAP_SIZE}`} width="100%" role="img"
      aria-label={t('drone:airspace.map.title')}
      style={{ maxWidth: 420, display: 'block', margin: '0 auto', background: '#fafafa', borderRadius: 8 }}>
      {grouped.map(({ zone, cx, cy, r }) => {
        const color = AIRSPACE_LEVEL_FILL[zone.level] || '#8a9099';
        const levelText = t(AIRSPACE_LEVEL_LABEL[zone.level] || '', { defaultValue: zone.level });
        return (
          <g key={zone.id}>
            <circle cx={cx} cy={cy} r={r} fill={color} fillOpacity={0.15} stroke={color} strokeWidth={1} />
            <title>{`${zone.name || `#${zone.id}`} · ${levelText} · ${zone.radiusM ?? EMPTY} m`}</title>
          </g>
        );
      })}
      {/* 比例尺：无比例尺用户会误读圆的大小 */}
      <line x1={MAP_SIZE - 10 - SCALE_PX} x2={MAP_SIZE - 10} y1={MAP_SIZE - 14} y2={MAP_SIZE - 14}
        stroke="#8a9099" strokeWidth={1} />
      <line x1={MAP_SIZE - 10 - SCALE_PX} x2={MAP_SIZE - 10 - SCALE_PX} y1={MAP_SIZE - 18} y2={MAP_SIZE - 10}
        stroke="#8a9099" strokeWidth={1} />
      <line x1={MAP_SIZE - 10} x2={MAP_SIZE - 10} y1={MAP_SIZE - 18} y2={MAP_SIZE - 10}
        stroke="#8a9099" strokeWidth={1} />
      <text x={MAP_SIZE - 10 - SCALE_PX / 2} y={MAP_SIZE - 20} fill="#8a9099" fontSize={11}
        textAnchor="middle">
        {`≈ ${scaleKm} km`}
      </text>
    </svg>
  );
}

/**
 * 空域等级标签（带颜色）。
 * @param {Object} props 组件属性
 * @param {string} props.value 等级枚举值
 * @returns {JSX.Element} 标签
 */
export function AirspaceLevelTag({ value }) {
  const { t } = useTranslation(['drone']);
  if (value === null || value === undefined || value === '') return <span>{EMPTY}</span>;
  const key = AIRSPACE_LEVEL_LABEL[value];
  const text = key ? t(key, { defaultValue: value }) : value;
  return <Tag color={AIRSPACE_LEVEL_COLOR[value]}>{text || value}</Tag>;
}

/**
 * 把 LocalDate（YYYY-MM-DD）安全格式化，空 / 非法值返回占位符。
 *
 * ⚠️ 与 fmtTime() 不同：expiryDate 是 LocalDate 而非 Instant，
 *    用 fmtTime() 的默认格式会多出 "00:00"，且跨时区时可能差一天（§7.7）。
 *
 * @param {string|null|undefined} v 日期值
 * @returns {string} 格式化后的日期文本
 */
export function fmtDate(v) {
  if (v === null || v === undefined || v === '') return EMPTY;
  const d = dayjs(String(v));
  return d.isValid() ? d.format('YYYY-MM-DD') : EMPTY;
}

export default {
  DRONE_ERROR_KEYS,
  useDroneOptions,
  useDroneError,
  AirspaceOverview,
  AirspaceLevelTag,
  fmtDate,
};
