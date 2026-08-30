import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Select, Space, Tag } from 'antd';
import CrudTable from '../components/CrudTable';
import { EMPTY, fmtTime, useSupplyOptions } from '../components/supplyShared';
import { COMMISSION_TYPE } from '../enums';

/**
 * 把表单里的日期文本补全成后端 Instant 能解析的 ISO-8601 字符串。
 * 「2026-01-01」→「2026-01-01T00:00:00Z」；已是 ISO 串或空值则原样返回。
 * @param {string|null|undefined} v 输入值
 * @returns {string|null} ISO 时间串或 null
 */
function toInstant(v) {
  if (v === null || v === undefined || String(v).trim() === '') return null;
  const s = String(v).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return `${s}T00:00:00Z`;
  return s;
}

/**
 * 设备销售提成规则配置页（增量 B · R7/B-结算）。
 *
 * 表 device_sales_commission_rules，与光伏分成 revenue_split_rules 解耦：
 * 支持 RATE（按比例）/ AMOUNT（定额）、优先级、生效期，厂家+商品可留空表示平台默认规则。
 * 对接后端 AdminCommissionController（/api/v1/admin/commission/rules）。
 */
export default function CommissionRules() {
  const { t } = useTranslation(['common', 'supply']);
  const { manufacturerOptions, productOptions, manufacturerName, productName } = useSupplyOptions();
  const [manufacturerId, setManufacturerId] = useState(undefined);

  // CrudTable 的 query 会作为 useCallback 依赖，必须保持引用稳定，否则会无限重载。
  const query = useMemo(
    () => (manufacturerId == null ? {} : { manufacturerId }),
    [manufacturerId]
  );

  const columns = [
    { title: t('supply:commission.col.id'), dataIndex: 'id', width: 80 },
    { title: t('supply:commission.col.ruleName'), dataIndex: 'ruleName', width: 160 },
    {
      title: t('supply:commission.col.manufacturerId'),
      dataIndex: 'manufacturerId',
      width: 140,
      render: (v) => (v == null ? t('supply:commission.tip.global') : manufacturerName(v)),
    },
    {
      title: t('supply:commission.col.productId'),
      dataIndex: 'productId',
      width: 160,
      render: (v) => (v == null ? EMPTY : `${productName(v)} (#${v})`),
    },
    {
      title: t('supply:commission.col.commissionType'),
      dataIndex: 'commissionType',
      width: 110,
      render: (v) => (v ? <Tag color={v === 'RATE' ? 'blue' : 'purple'}>{t(`supply:enum.commissionType.${v}`)}</Tag> : EMPTY),
    },
    {
      title: t('supply:commission.col.rate'),
      dataIndex: 'rate',
      width: 100,
      render: (v) => (v == null ? EMPTY : `${(Number(v) * 100).toFixed(2)}%`),
    },
    {
      title: t('supply:commission.col.amount'),
      dataIndex: 'amount',
      width: 110,
      render: (v) => (v == null ? EMPTY : Number(v).toFixed(2)),
    },
    {
      title: t('supply:commission.col.minAmount'),
      dataIndex: 'minAmount',
      width: 110,
      render: (v) => (v == null ? EMPTY : Number(v).toFixed(2)),
    },
    {
      title: t('supply:commission.col.maxAmount'),
      dataIndex: 'maxAmount',
      width: 110,
      render: (v) => (v == null ? EMPTY : Number(v).toFixed(2)),
    },
    { title: t('supply:commission.col.priority'), dataIndex: 'priority', width: 90 },
    {
      title: t('supply:commission.col.effectiveFrom'),
      dataIndex: 'effectiveFrom',
      width: 150,
      render: (v) => fmtTime(v),
    },
    {
      title: t('supply:commission.col.effectiveTo'),
      dataIndex: 'effectiveTo',
      width: 150,
      render: (v) => fmtTime(v),
    },
    {
      title: t('supply:commission.col.enabled'),
      dataIndex: 'enabled',
      width: 90,
      render: (v) => (v ? <Tag color="green">{t('action.yes')}</Tag> : <Tag>{t('action.no')}</Tag>),
    },
  ];

  const fields = [
    { name: 'ruleName', label: t('supply:commission.field.ruleName') },
    {
      name: 'manufacturerId', label: t('supply:commission.field.manufacturerId'), type: 'select',
      options: manufacturerOptions,
    },
    {
      name: 'productId', label: t('supply:commission.field.productId'), type: 'select',
      options: productOptions,
    },
    {
      name: 'commissionType', label: t('supply:commission.field.commissionType'), type: 'select',
      options: COMMISSION_TYPE.map((o) => ({ ...o, label: t(`supply:enum.commissionType.${o.value}`) })),
      initialValue: COMMISSION_TYPE[0].value,
    },
    {
      name: 'rate', label: t('supply:commission.field.rate'), type: 'number', precision: 4,
      placeholder: t('supply:commission.tip.rate'),
    },
    { name: 'amount', label: t('supply:commission.field.amount'), type: 'number', precision: 2 },
    { name: 'minAmount', label: t('supply:commission.field.minAmount'), type: 'number', precision: 2 },
    { name: 'maxAmount', label: t('supply:commission.field.maxAmount'), type: 'number', precision: 2 },
    { name: 'priority', label: t('supply:commission.field.priority'), type: 'number', initialValue: 0 },
    {
      name: 'effectiveFrom', label: t('supply:commission.field.effectiveFrom'),
      placeholder: t('supply:commission.ph.effective'),
    },
    {
      name: 'effectiveTo', label: t('supply:commission.field.effectiveTo'),
      placeholder: t('supply:commission.ph.effective'),
    },
    {
      name: 'enabled', label: t('supply:commission.field.enabled'), type: 'select',
      options: [{ label: t('action.yes'), value: true }, { label: t('action.no'), value: false }],
      initialValue: true,
    },
  ];

  /** 提交前把日期文本补全为 Instant，并把空串统一成 null。 */
  const normalize = (values) => ({
    ...values,
    effectiveFrom: toInstant(values.effectiveFrom),
    effectiveTo: toInstant(values.effectiveTo),
    rate: values.rate === '' || values.rate === undefined ? null : values.rate,
    amount: values.amount === '' || values.amount === undefined ? null : values.amount,
  });

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Select
        allowClear
        showSearch
        optionFilterProp="label"
        placeholder={t('supply:common.selectManufacturer')}
        style={{ width: 260 }}
        options={manufacturerOptions}
        value={manufacturerId}
        onChange={setManufacturerId}
      />
      <CrudTable
        key={manufacturerId == null ? '__all__' : manufacturerId}
        title={t('supply:commission.title')}
        subtitle={t('supply:commission.subtitle')}
        endpoint="/v1/admin/commission/rules"
        columns={columns}
        fields={fields}
        rowKey="id"
        query={query}
        perm="config:commission:manage"
        transformCreate={normalize}
        transformUpdate={normalize}
      />
    </Space>
  );
}
