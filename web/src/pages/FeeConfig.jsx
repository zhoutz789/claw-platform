import React, { useEffect, useState } from 'react';
import { Tag, Card, Descriptions } from 'antd';
import CrudTable from '../components/CrudTable';
import api from '../api';

const columns = [
  { title: '规则编码', dataIndex: 'ruleCode', width: 160 },
  { title: '名称', dataIndex: 'name', width: 180 },
  { title: '单位', dataIndex: 'unit', width: 80 },
  { title: '单价', dataIndex: 'price', width: 100, render: (v) => (v == null ? '-' : `$${v}`) },
  { title: '分账JSON', dataIndex: 'shareJson', ellipsis: true },
  { title: '生效起', dataIndex: 'effectiveFrom', width: 120 },
  { title: '生效止', dataIndex: 'effectiveTo', width: 120, render: (v) => v || '长期' },
  {
    title: '状态', dataIndex: 'status', width: 100,
    render: (v) => <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v || '-'}</Tag>,
  },
];

const fields = [
  { name: 'ruleCode', label: '规则编码', required: true, placeholder: '如 GRID_ELEC' },
  { name: 'name', label: '名称', required: true },
  { name: 'unit', label: '计费单位', placeholder: 'kWh / 次' },
  { name: 'price', label: '单价', type: 'number', precision: 4 },
  { name: 'shareJson', label: '分账配置(JSON)', type: 'textarea', placeholder: '{"station":0.29,"platform":0.03}' },
  { name: 'effectiveFrom', label: '生效起始日', type: 'date', placeholder: 'YYYY-MM-DD' },
  { name: 'effectiveTo', label: '生效截止日', type: 'date', placeholder: 'YYYY-MM-DD（留空=长期）' },
  { name: 'status', label: '状态', type: 'select', options: [{ label: 'ACTIVE', value: 'ACTIVE' }, { label: 'INACTIVE', value: 'INACTIVE' }] },
];

export default function FeeConfig() {
  const [prices, setPrices] = useState([]);
  useEffect(() => {
    api.get('/v1/admin/fee/elec-prices').then(setPrices).catch(() => {});
  }, []);

  return (
    <>
      <CrudTable
        title="费率配置"
        subtitle="换电 / 电费 / 服务费 等计费规则的新增、编辑与停用"
        endpoint="/v1/admin/fee/rules"
        columns={columns}
        fields={fields}
        rowKey="id"
      />
      <Card title="电价快照（只读）" style={{ marginTop: 16 }}>
        {prices.length === 0 ? (
          <span style={{ color: '#999' }}>暂无电价快照数据</span>
        ) : (
          <Descriptions column={3} bordered size="small">
            {prices.map((p) => (
              <Descriptions.Item key={p.id} label={`${p.effectiveDate} PV/电网`}>
                PV ${p.pvPrice} / 电网 ${p.gridPrice}
              </Descriptions.Item>
            ))}
          </Descriptions>
        )}
      </Card>
    </>
  );
}
