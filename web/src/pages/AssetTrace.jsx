import React, { useState } from 'react';
import { Tabs, Card, InputNumber, Button, Input, Space, Table, Descriptions, Tag, message, Spin } from 'antd';
import { useSearchParams } from 'react-router-dom';
import api from '../api';
import { LIFECYCLE_LABEL, OP_LABEL, ASSET_STATUS_LABEL, ASSET_TYPE } from '../enums';

export default function AssetTrace() {
  const [params] = useSearchParams();
  const [assetId, setAssetId] = useState(params.get('id') ? Number(params.get('id')) : null);
  const [trace, setTrace] = useState(null);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    if (!assetId) { message.warning('请输入资产 ID'); return; }
    setLoading(true);
    try { setTrace(await api.get(`/v1/admin/manufacturer/assets/${assetId}/trace`)); }
    catch (e) { message.error(e.message); setTrace(null); }
    finally { setLoading(false); }
  };

  const lifecycleCols = [
    { title: '阶段', dataIndex: 'stage', render: (v) => <Tag color="blue">{LIFECYCLE_LABEL[v] || v}</Tag> },
    { title: '地点', dataIndex: 'location' },
    { title: '操作人', dataIndex: 'operatorId' },
    { title: '备注', dataIndex: 'note' },
    { title: '时间', dataIndex: 'occurredAt' },
  ];
  const maintCols = [
    { title: '类型', dataIndex: 'mtype' }, { title: '服务商', dataIndex: 'vendor' },
    { title: '费用', dataIndex: 'cost' }, { title: '时间', dataIndex: 'servicedAt' }, { title: '备注', dataIndex: 'note' },
  ];
  const usageCols = [
    { title: '周期起', dataIndex: 'periodStart' }, { title: '周期止', dataIndex: 'periodEnd' },
    { title: '里程(km)', dataIndex: 'mileageKm' }, { title: '循环', dataIndex: 'cycles' },
    { title: '能耗(kWh)', dataIndex: 'energyKwh' }, { title: '备注', dataIndex: 'note' },
  ];
  const vopsCols = [
    { title: '运营类型', dataIndex: 'opType', render: (v) => OP_LABEL[v] || v },
    { title: '开始', dataIndex: 'startedAt' }, { title: '结束', dataIndex: 'endedAt' },
    { title: '收益', dataIndex: 'revenue' }, { title: '备注', dataIndex: 'note' },
  ];

  return (
    <Card title="资产溯源（序列号 / 二维码识别）" extra={
      <Space>
        <InputNumber placeholder="资产 ID" value={assetId} onChange={setAssetId} style={{ width: 160 }} />
        <Button type="primary" onClick={load}>查询溯源</Button>
      </Space>
    }>
      <Spin spinning={loading}>
        {!trace && <div style={{ color: 'var(--muted)', padding: 24 }}>输入资产 ID 查询其全生命周期数据（出厂 / 生命周期 / 维修 / 使用 / 车辆运营 / 收益 / 二维码）。</div>}
        {trace && (
          <Tabs items={[
            { key: 'factory', label: '出厂数据', children: <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="资产编号">{trace.asset?.assetNo}</Descriptions.Item>
              <Descriptions.Item label="类型">{ASSET_TYPE.find((x) => x.value === trace.assetType)?.label || trace.assetType}</Descriptions.Item>
              <Descriptions.Item label="序列号">{trace.asset?.serialNumber || '-'}</Descriptions.Item>
              <Descriptions.Item label="状态">{ASSET_STATUS_LABEL[trace.status] || trace.status}</Descriptions.Item>
              <Descriptions.Item label="厂家ID">{trace.asset?.manufacturerId || '-'}</Descriptions.Item>
              <Descriptions.Item label="商品ID">{trace.asset?.productId || '-'}</Descriptions.Item>
              <Descriptions.Item label="SKU ID">{trace.asset?.skuId || '-'}</Descriptions.Item>
              <Descriptions.Item label="二维码">{trace.asset?.qrCode || '-'}</Descriptions.Item>
            </Descriptions> },
            { key: 'life', label: `生命周期(${trace.lifecycle?.length || 0})`, children: <Table rowKey="id" size="small" dataSource={trace.lifecycle || []} columns={lifecycleCols} pagination={false} /> },
            { key: 'maint', label: `维修(${trace.maintenance?.length || 0})`, children: <Table rowKey="id" size="small" dataSource={trace.maintenance || []} columns={maintCols} pagination={false} /> },
            { key: 'usage', label: `使用(${trace.usage?.length || 0})`, children: <Table rowKey="id" size="small" dataSource={trace.usage || []} columns={usageCols} pagination={false} /> },
            { key: 'ops', label: `车辆运营(${trace.vehicleOps?.length || 0})`, children: <Table rowKey="id" size="small" dataSource={trace.vehicleOps || []} columns={vopsCols} pagination={false} /> },
            { key: 'rev', label: '收益数据', children: <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="车辆运营累计收益">{trace.totalRevenue}</Descriptions.Item>
              <Descriptions.Item label="说明">收益取自资产「车辆运营」记录（客运/物流/流动售卖/广告/录像）的营收汇总。</Descriptions.Item>
            </Descriptions> },
            { key: 'qr', label: '二维码', children: <div>
              <p style={{ color: 'var(--muted)' }}>序列号二维码为资产全链路溯源的唯一标识，扫码即可定位上述全部数据。</p>
              <Input.TextArea value={trace.asset?.qrCode || ''} rows={3} readOnly />
            </div> },
          ]} />
        )}
      </Spin>
    </Card>
  );
}
