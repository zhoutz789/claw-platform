// 库存域共享组件（模块三 · M3-1/2/3）。
//
// 提供三页（库存总览 / 厂家库存 / 服务站寄售库存）共用的：
//   1. useInventoryColumns —— 库存明细表列工厂（唯一列定义来源，防各页漂移）；
//   2. ScopeBanner —— 作用域提示条（显示主体 / 下属站点数 / 未绑定引导）。
//
// 列字段严格对齐后端 InventoryRowView（字段名与实体一致，H-7 后不含货值三字段）。
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert } from 'antd';
import { EnumTag, EMPTY, fmtTime, useSupplyOptions } from './supplyShared';
import { enumLabel, OWNERSHIP_TYPE_LABEL, DEVICE_LIFECYCLE_LABEL, SCOPE_LEVEL_LABEL } from '../enums';

/**
 * 库存明细表列工厂（三页共用，唯一列定义来源）。
 *
 * @param {Object} [opts]
 * @param {'current'|'station'} [opts.variant] 视图变体（current=现有库存 / station=服务站库存）
 * @param {Object} [opts.options] 复用外部 useSupplyOptions 结果（避免重复拉取下拉）
 * @returns {Array} antd Table columns
 */
export function useInventoryColumns({ variant = 'current', options } = {}) {
  const { t } = useTranslation(['common', 'supply']);
  const internal = useSupplyOptions();
  const o = options || internal;

  return useMemo(() => (variant === 'station'
    ? [
      { title: t('supply:inventoryOverview.col.id'), dataIndex: 'id', width: 90 },
      { title: t('supply:inventoryOverview.col.assetId'), dataIndex: 'assetId', width: 100 },
      { title: t('supply:inventoryOverview.col.deviceId'), dataIndex: 'deviceId', width: 100 },
      {
        title: t('supply:inventoryOverview.col.serialNumber'),
        dataIndex: 'serialNumber',
        width: 160,
        render: (v) => v || EMPTY,
      },
      {
        title: t('supply:inventoryOverview.col.productId'),
        dataIndex: 'productId',
        width: 160,
        render: (v) => (v == null ? EMPTY : `${o.productName(v)} (#${v})`),
      },
      {
        title: t('supply:inventoryOverview.col.ownershipType'),
        dataIndex: 'ownershipType',
        width: 130,
        render: (v) => <EnumTag value={v} labelMap={OWNERSHIP_TYPE_LABEL} />,
      },
      {
        title: t('supply:inventoryOverview.col.currentStatus'),
        dataIndex: 'currentStatus',
        width: 140,
        render: (v) => <EnumTag value={v} labelMap={DEVICE_LIFECYCLE_LABEL} />,
      },
      {
        title: t('supply:inventoryOverview.col.ownerManufacturerId'),
        dataIndex: 'ownerManufacturerId',
        width: 150,
        render: (v) => (v == null ? EMPTY : o.manufacturerName(v)),
      },
      {
        title: t('supply:inventoryOverview.col.holderStationId'),
        dataIndex: 'holderStationId',
        width: 160,
        render: (v) => (v == null ? EMPTY : o.stationName(v)),
      },
      {
        title: t('supply:inventoryOverview.col.inboundAt'),
        dataIndex: 'inboundAt',
        width: 160,
        render: (v) => fmtTime(v),
      },
      {
        title: t('supply:inventoryOverview.col.updatedAt'),
        dataIndex: 'updatedAt',
        width: 160,
        render: (v) => fmtTime(v),
      },
    ]
    : [
      { title: t('supply:inventoryOverview.col.id'), dataIndex: 'id', width: 90 },
      { title: t('supply:inventoryOverview.col.assetId'), dataIndex: 'assetId', width: 100 },
      { title: t('supply:inventoryOverview.col.deviceId'), dataIndex: 'deviceId', width: 100 },
      {
        title: t('supply:inventoryOverview.col.serialNumber'),
        dataIndex: 'serialNumber',
        width: 160,
        render: (v) => v || EMPTY,
      },
      {
        title: t('supply:inventoryOverview.col.productId'),
        dataIndex: 'productId',
        width: 160,
        render: (v) => (v == null ? EMPTY : `${o.productName(v)} (#${v})`),
      },
      {
        title: t('supply:inventoryOverview.col.ownershipType'),
        dataIndex: 'ownershipType',
        width: 130,
        render: (v) => <EnumTag value={v} labelMap={OWNERSHIP_TYPE_LABEL} />,
      },
      {
        title: t('supply:inventoryOverview.col.currentStatus'),
        dataIndex: 'currentStatus',
        width: 140,
        render: (v) => <EnumTag value={v} labelMap={DEVICE_LIFECYCLE_LABEL} />,
      },
      {
        title: t('supply:inventoryOverview.col.ownerManufacturerId'),
        dataIndex: 'ownerManufacturerId',
        width: 150,
        render: (v) => (v == null ? EMPTY : o.manufacturerName(v)),
      },
      {
        title: t('supply:inventoryOverview.col.holderStationId'),
        dataIndex: 'holderStationId',
        width: 160,
        render: (v) => (v == null ? EMPTY : o.stationName(v)),
      },
      {
        title: t('supply:inventoryOverview.col.inboundAt'),
        dataIndex: 'inboundAt',
        width: 160,
        render: (v) => fmtTime(v),
      },
    ]), [variant, o, t]);
}

/**
 * 作用域提示条（显示在库存总览页顶部）。
 *
 * @param {Object} props
 * @param {Object} [props.scope] 后端 ScopeInfo（null 时不渲染）
 * @param {Function} [props.manufacturerName] id → 名称
 * @param {Function} [props.stationName] id → 名称
 */
export function ScopeBanner({ scope, manufacturerName, stationName }) {
  const { t } = useTranslation(['common', 'supply']);
  if (!scope) return null;

  if (scope.scopeLevel === 'NONE') {
    return <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('supply:inventoryOverview.banner.none')} />;
  }

  const levelLabel = enumLabel(SCOPE_LEVEL_LABEL, scope.scopeLevel);
  let who = '';
  if (scope.scopeLevel === 'PLATFORM') {
    who = t('supply:inventoryOverview.banner.platform');
  } else if (scope.scopeLevel === 'MANUFACTURER') {
    const name = manufacturerName ? manufacturerName(scope.principalId) : `#${scope.principalId}`;
    who = `${t('supply:inventoryOverview.banner.manufacturer')} #${scope.principalId}（${name}）`;
  } else if (scope.scopeLevel === 'STATION') {
    const name = stationName ? stationName(scope.principalId) : `#${scope.principalId}`;
    who = `${t('supply:inventoryOverview.banner.station')} #${scope.principalId}（${name}）`;
  } else {
    who = `${scope.principalType} #${scope.principalId}`;
  }

  const sub = scope.subordinateStationIds && scope.subordinateStationIds.length
    ? t('supply:inventoryOverview.banner.subordinate', { count: scope.subordinateStationIds.length })
    : '';

  return (
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 12 }}
      message={`${levelLabel} · ${who}${sub ? ` · ${sub}` : ''}`}
    />
  );
}

export default { useInventoryColumns, ScopeBanner };
