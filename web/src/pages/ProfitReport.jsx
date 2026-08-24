import React, { useEffect, useState } from 'react';
import { Tabs, Table, Button, Tag, message, Space, Modal, Form, InputNumber, Input } from 'antd';
import CrudTable from '../components/CrudTable';
import PageCard from '../components/PageCard';
import api from '../api';
import { REVENUE_SHARE_BASIS, SETTLEMENT_STATUS } from '../enums';

const settlementColumns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '结算单号', dataIndex: 'settlementNo', width: 160 },
  { title: '结算日期', dataIndex: 'settlementDate', width: 130 },
  { title: '站点', dataIndex: 'stationId', width: 80 },
  { title: '总收入', dataIndex: 'totalRevenue', render: (v) => `$${v}` },
  { title: '资产方', dataIndex: 'ownerShare', render: (v) => `$${v}` },
  { title: '站点方', dataIndex: 'stationShare', render: (v) => `$${v}` },
  { title: '平台', dataIndex: 'platformShare', render: (v) => `$${v}` },
  { title: '保险', dataIndex: 'insuranceShare', render: (v) => `$${v}` },
  {
    title: '状态', dataIndex: 'status', width: 100,
    render: (v) => <Tag color={v === 'SETTLED' ? 'green' : 'orange'}>{v}</Tag>,
  },
  {
    title: '操作', key: '_a', width: 100,
    render: (_, r) => (r.status !== 'SETTLED'
      ? <Button size="small" type="primary" onClick={() => settle(r)}>结算</Button>
      : <span style={{ color: '#999' }}>已结算</span>),
  },
];

const splitColumns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '资产ID', dataIndex: 'assetId', width: 90 },
  { title: '池条目', dataIndex: 'poolEntryId', width: 90 },
  { title: '资产方%', dataIndex: 'ownerRate', width: 90 },
  { title: '站点%', dataIndex: 'stationRate', width: 90 },
  { title: '平台%', dataIndex: 'platformRate', width: 90 },
  { title: '保险%', dataIndex: 'insuranceRate', width: 90 },
  { title: '计费基准', dataIndex: 'shareBasis', width: 120 },
  { title: '生效起', dataIndex: 'effectiveFrom', width: 120 },
  { title: '生效止', dataIndex: 'effectiveTo', width: 120 },
  { title: '状态', dataIndex: 'status', width: 100 },
];

const splitFields = [
  { name: 'assetId', label: '资产ID', type: 'number', required: true },
  { name: 'poolEntryId', label: '池条目ID', type: 'number' },
  { name: 'ownerRate', label: '资产方比例(≥0.50)', type: 'number', precision: 2, required: true, initialValue: 0.70 },
  { name: 'stationRate', label: '站点比例(≥0.15)', type: 'number', precision: 2, required: true, initialValue: 0.15 },
  { name: 'platformRate', label: '平台比例(固定0.10)', type: 'number', precision: 2, required: true, initialValue: 0.10, disabled: true },
  { name: 'insuranceRate', label: '保险比例(固定0.05)', type: 'number', precision: 2, required: true, initialValue: 0.05, disabled: true },
  { name: 'shareBasis', label: '计费基准', type: 'select', options: REVENUE_SHARE_BASIS, required: true },
  { name: 'effectiveFrom', label: '生效起', type: 'date' },
  { name: 'effectiveTo', label: '生效止', type: 'date' },
  { name: 'status', label: '状态', type: 'select', options: [{ label: 'ACTIVE', value: 'ACTIVE' }, { label: 'INACTIVE', value: 'INACTIVE' }] },
];

function SettlementPanel() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  const load = () => {
    setLoading(true);
    api.get('/v1/admin/profit/settlements').then(setData).catch((e) => message.error(e.message)).finally(() => setLoading(false));
  };
  useEffect(load, []);
  const settle = async (r) => {
    try {
      await api.post(`/v1/admin/profit/settlements/${r.id}/settle`);
      message.success('已结算');
      load();
    } catch (e) { message.error(`结算失败：${e.message}`); }
  };
  return <Table rowKey="id" loading={loading} dataSource={data} columns={settlementColumns} pagination={{ pageSize: 10 }} size="middle" />;
}

export default function ProfitReport() {
  return (
    <Tabs defaultActiveKey="settlements" items={[
      {
        key: 'settlements', label: '分账结算',
        children: (
          <PageCard title="分账结算" subtitle="按周期对各站/共享池收入进行分账结算">
            <SettlementPanel />
          </PageCard>
        ),
      },
      {
        key: 'rules', label: '分账规则',
        children: (
          <CrudTable title="分账规则" subtitle="资产收益分配比例：资产方≥50%、站点≥15%、平台固定10%、保险固定5%，四者合计=100%" endpoint="/v1/admin/profit/split-rules" columns={splitColumns} fields={splitFields} rowKey="id" />
        ),
      },
    ]} />
  );
}
