import React, { useEffect, useState } from 'react';
import { Table, Drawer, Descriptions, Tag, message, Button, Empty, Form, InputNumber, Input, Card, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { TRANSFER_TYPE, ASSET_TYPE } from '../enums';

const columns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '资产ID', dataIndex: 'assetId', width: 90 },
  { title: '资产类型', dataIndex: 'assetType', width: 110, render: (v) => <Tag>{v}</Tag> },
  { title: '转出方', dataIndex: 'fromUserId', width: 100 },
  { title: '转入方', dataIndex: 'toUserId', width: 100 },
  { title: '转移类型', dataIndex: 'transferType', width: 170 },
  { title: '站点', dataIndex: 'stationId', width: 80 },
  { title: '关联换电单', dataIndex: 'swapOrderId', width: 120 },
  { title: '链上哈希', dataIndex: 'chainHash', ellipsis: true },
  { title: 'SOH', dataIndex: 'assetSoh', width: 80 },
  { title: 'SOC', dataIndex: 'assetSoc', width: 80 },
  { title: '转移时间', dataIndex: 'transferredAt', width: 170 },
  { title: '操作', key: '_a', width: 90, render: (_, r) => <Button size="small" type="link" onClick={() => openDetail(r)}>追溯详情</Button> },
];

export default function CustodyChain() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState(null);
  const [createForm] = Form.useForm();

  const load = () => {
    setLoading(true);
    api.get('/v1/admin/custody/transfers').then(setData).catch((e) => message.error(e.message)).finally(() => setLoading(false));
  };
  useEffect(load, []);

  const openDetail = async (r) => {
    try {
      const res = await api.get(`/v1/admin/custody/transfers/${r.id}`);
      setDetail(res);
      setOpen(true);
    } catch (e) { message.error(`详情加载失败：${e.message}`); }
  };

  return (
    <PageCard title="产权链追溯" subtitle="资产所有权转移链（含审计轨迹）：可追溯 + 可登记转移">
      <Card size="small" title="登记产权转移（形成不可篡改产权链）" style={{ marginBottom: 12 }}>
        <Form form={createForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/custody/transfers', v).then(() => { message.success('已登记转移'); createForm.resetFields(); load(); }).catch((e) => message.error(e.message))}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
            <Form.Item name="assetId" label="资产ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="assetType" label="资产类型" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="transferType" label="转移类型" rules={[{ required: true }]}><Select options={[{ label: '换电交换', value: 'SWAP_EXCHANGE' }, { label: '租赁开始', value: 'RENTAL_START' }, { label: '租赁结束', value: 'RENTAL_END' }, { label: '共享池入', value: 'SHARED_POOL_ENTRY' }, { label: '共享池出', value: 'SHARED_POOL_EXIT' }, { label: '回收', value: 'RECOVERY' }, { label: '以旧换新', value: 'TRADE_IN' }]} /></Form.Item>
            <Form.Item name="fromUserId" label="转出方ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="toUserId" label="转入方ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="stationId" label="站点ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="swapOrderId" label="关联换电单ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="assetSoh" label="SOH(%)"><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="assetSoc" label="SOC(%)"><InputNumber style={{ width: '100%' }} /></Form.Item>
          </div>
          <Form.Item name="assetCycleCount" label="循环次数"><InputNumber style={{ width: 200 }} /></Form.Item>
          <Button type="primary" htmlType="submit">登记转移</Button>
        </Form>
      </Card>
      <Table rowKey="id" loading={loading} dataSource={data} columns={columns} pagination={{ pageSize: 10 }} size="middle" scroll={{ x: 'max-content' }} />
      <Drawer title="产权转移详情" open={open} onClose={() => setOpen(false)} width={640}>
        {detail ? (
          <>
            <Descriptions column={2} bordered size="small" title="转移信息">
              <Descriptions.Item label="ID">{detail.transfer?.id}</Descriptions.Item>
              <Descriptions.Item label="资产ID">{detail.transfer?.assetId}</Descriptions.Item>
              <Descriptions.Item label="资产类型">{detail.transfer?.assetType}</Descriptions.Item>
              <Descriptions.Item label="转移类型">{detail.transfer?.transferType}</Descriptions.Item>
              <Descriptions.Item label="转出方">{detail.transfer?.fromUserId}</Descriptions.Item>
              <Descriptions.Item label="转入方">{detail.transfer?.toUserId}</Descriptions.Item>
              <Descriptions.Item label="站点">{detail.transfer?.stationId}</Descriptions.Item>
              <Descriptions.Item label="关联换电单">{detail.transfer?.swapOrderId}</Descriptions.Item>
              <Descriptions.Item label="链上哈希" span={2}>{detail.transfer?.chainHash}</Descriptions.Item>
              <Descriptions.Item label="SOH">{detail.transfer?.assetSoh}</Descriptions.Item>
              <Descriptions.Item label="SOC">{detail.transfer?.assetSoc}</Descriptions.Item>
              <Descriptions.Item label="循环次数">{detail.transfer?.assetCycleCount}</Descriptions.Item>
              <Descriptions.Item label="转移时间">{detail.transfer?.transferredAt}</Descriptions.Item>
            </Descriptions>
            <div style={{ margin: '16px 0 8px', fontWeight: 600 }}>审计轨迹</div>
            {detail.audits && detail.audits.length > 0 ? (
              <Table rowKey="id" dataSource={detail.audits} pagination={false} size="small"
                columns={[
                  { title: 'ID', dataIndex: 'id', width: 60 },
                  { title: '异常类型', dataIndex: 'anomalyType' },
                  { title: '风险分', dataIndex: 'riskScore', width: 80 },
                  { title: '说明', dataIndex: 'description', ellipsis: true },
                  { title: '用户日转移', dataIndex: 'userDailyTransferCount', width: 100 },
                  { title: '资产日转移', dataIndex: 'assetDailyTransferCount', width: 100 },
                  { title: '检测时间', dataIndex: 'detectedAt', width: 160 },
                  { title: '已复核', dataIndex: 'reviewed', width: 80, render: (v) => (v ? '是' : '否') },
                ]}
              />
            ) : <Empty description="无审计记录" />}
          </>
        ) : '加载中…'}
      </Drawer>
    </PageCard>
  );
}
