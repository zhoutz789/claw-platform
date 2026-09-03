import React, { useEffect, useState } from 'react';
import { Table, Drawer, Descriptions, Tag, message, Button, Empty, Form, InputNumber, Input, Card, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { TRANSFER_TYPE, ASSET_TYPE } from '../enums';
import { useTranslation } from 'react-i18next';

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
  { title: '操作', key: '_a', width: 90, render: (_, r) => {
  const { t } = useTranslation('common');
  return (<Button size="small" type="link" onClick={() => openDetail(r)}>{t('common:m707')}</Button>);
} },
];

export default function CustodyChain() {  const { t } = useTranslation('common');

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
    <PageCard title={t('common:m708')} subtitle="资产所有权转移链（含审计轨迹）：可追溯 + 可登记转移">
      <Card size="small" title={t('common:m709')} style={{ marginBottom: 12 }}>
        <Form form={createForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/custody/transfers', v).then(() => { message.success(t('common:m710')); createForm.resetFields(); load(); }).catch((e) => message.error(e.message))}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
            <Form.Item name="assetId" label={t('common:m213')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="assetType" label={t('common:m92')} rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="transferType" label={t('common:m711')} rules={[{ required: true }]}><Select options={[{ label: t('common:m712'), value: 'SWAP_EXCHANGE' }, { label: t('common:m713'), value: 'RENTAL_START' }, { label: t('common:m714'), value: 'RENTAL_END' }, { label: t('common:m715'), value: 'SHARED_POOL_ENTRY' }, { label: t('common:m716'), value: 'SHARED_POOL_EXIT' }, { label: t('common:m492'), value: 'RECOVERY' }, { label: t('common:m231'), value: 'TRADE_IN' }]} /></Form.Item>
            <Form.Item name="fromUserId" label={t('common:m717')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="toUserId" label={t('common:m718')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="stationId" label={t('common:m503')}><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="swapOrderId" label={t('common:m719')}><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="assetSoh" label="SOH(%)"><InputNumber style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="assetSoc" label="SOC(%)"><InputNumber style={{ width: '100%' }} /></Form.Item>
          </div>
          <Form.Item name="assetCycleCount" label={t('common:m216')}><InputNumber style={{ width: 200 }} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m720')}</Button>
        </Form>
      </Card>
      <Table rowKey="id" loading={loading} dataSource={data} columns={columns} pagination={{ pageSize: 10 }} size="middle" scroll={{ x: 'max-content' }} />
      <Drawer title={t('common:m721')} open={open} onClose={() => setOpen(false)} width={640}>
        {detail ? (
          <>
            <Descriptions column={2} bordered size="small" title={t('common:m722')}>
              <Descriptions.Item label="ID">{detail.transfer?.id}</Descriptions.Item>
              <Descriptions.Item label={t('common:m213')}>{detail.transfer?.assetId}</Descriptions.Item>
              <Descriptions.Item label={t('common:m92')}>{detail.transfer?.assetType}</Descriptions.Item>
              <Descriptions.Item label={t('common:m711')}>{detail.transfer?.transferType}</Descriptions.Item>
              <Descriptions.Item label={t('common:m723')}>{detail.transfer?.fromUserId}</Descriptions.Item>
              <Descriptions.Item label={t('common:m724')}>{detail.transfer?.toUserId}</Descriptions.Item>
              <Descriptions.Item label={t('common:m512')}>{detail.transfer?.stationId}</Descriptions.Item>
              <Descriptions.Item label={t('common:m725')}>{detail.transfer?.swapOrderId}</Descriptions.Item>
              <Descriptions.Item label={t('common:m726')} span={2}>{detail.transfer?.chainHash}</Descriptions.Item>
              <Descriptions.Item label="SOH">{detail.transfer?.assetSoh}</Descriptions.Item>
              <Descriptions.Item label="SOC">{detail.transfer?.assetSoc}</Descriptions.Item>
              <Descriptions.Item label={t('common:m216')}>{detail.transfer?.assetCycleCount}</Descriptions.Item>
              <Descriptions.Item label={t('common:m727')}>{detail.transfer?.transferredAt}</Descriptions.Item>
            </Descriptions>
            <div style={{ margin: '16px 0 8px', fontWeight: 600 }}>{t('common:m728')}</div>
            {detail.audits && detail.audits.length > 0 ? (
              <Table rowKey="id" dataSource={detail.audits} pagination={false} size="small"
                columns={[
                  { title: 'ID', dataIndex: 'id', width: 60 },
                  { title: t('common:m729'), dataIndex: 'anomalyType' },
                  { title: t('common:m730'), dataIndex: 'riskScore', width: 80 },
                  { title: t('common:m211'), dataIndex: 'description', ellipsis: true },
                  { title: t('common:m731'), dataIndex: 'userDailyTransferCount', width: 100 },
                  { title: t('common:m732'), dataIndex: 'assetDailyTransferCount', width: 100 },
                  { title: t('common:m733'), dataIndex: 'detectedAt', width: 160 },
                  { title: t('common:m734'), dataIndex: 'reviewed', width: 80, render: (v) => (v ? '是' : '否') },
                ]}
              />
            ) : <Empty description={t('common:m735')} />}
          </>
        ) : '加载中…'}
      </Drawer>
    </PageCard>
  );
}
