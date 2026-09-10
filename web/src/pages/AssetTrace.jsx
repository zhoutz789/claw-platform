import React, { useState } from 'react';
import { Tabs, Card, InputNumber, Button, Input, Space, Table, Descriptions, Tag, message, Spin, Form, Select } from 'antd';
import { useSearchParams } from 'react-router-dom';
import api from '../api';
import { LIFECYCLE_LABEL, OP_LABEL, ASSET_STATUS_LABEL, ASSET_TYPE, enumLabel } from '../enums';
import { useTranslation } from 'react-i18next';
import AssetTaskEarnings from '../components/AssetTaskEarnings';

export default function AssetTrace() {  const { t } = useTranslation(['common', 'task']);

  const [params] = useSearchParams();
  const [assetId, setAssetId] = useState(params.get('id') ? Number(params.get('id')) : null);
  const [writeId, setWriteId] = useState(params.get('id') ? Number(params.get('id')) : null);
  const [trace, setTrace] = useState(null);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    if (!assetId) { message.warning(t('common:m462')); return; }
    setLoading(true);
    try { setTrace(await api.get(`/v1/admin/manufacturer/assets/${assetId}/trace`)); }
    catch (e) { message.error(e.message); setTrace(null); }
    finally { setLoading(false); }
  };

  const lifecycleCols = [
    { title: t('common:m463'), dataIndex: 'stage', render: (v) => <Tag color="blue">{enumLabel(LIFECYCLE_LABEL, v)}</Tag> },
    { title: t('common:m464'), dataIndex: 'location' },
    { title: t('common:m373'), dataIndex: 'operatorId' },
    { title: t('common:m318'), dataIndex: 'note' },
    { title: t('common:m35'), dataIndex: 'occurredAt' },
  ];
  const maintCols = [
    { title: t('common:m36'), dataIndex: 'mtype' }, { title: t('common:m465'), dataIndex: 'vendor' },
    { title: t('common:m115'), dataIndex: 'cost' }, { title: t('common:m35'), dataIndex: 'servicedAt' }, { title: t('common:m318'), dataIndex: 'note' },
  ];
  const usageCols = [
    { title: t('common:m466'), dataIndex: 'periodStart' }, { title: t('common:m467'), dataIndex: 'periodEnd' },
    { title: t('common:m468'), dataIndex: 'mileageKm' }, { title: t('common:m469'), dataIndex: 'cycles' },
    { title: t('common:m470'), dataIndex: 'energyKwh' }, { title: t('common:m318'), dataIndex: 'note' },
  ];
  const vopsCols = [
    { title: t('common:m471'), dataIndex: 'opType', render: (v) => enumLabel(OP_LABEL, v) },
    { title: t('common:m472'), dataIndex: 'startedAt' }, { title: t('common:m473'), dataIndex: 'endedAt' },
    { title: t('common:m197'), dataIndex: 'revenue' }, { title: t('common:m318'), dataIndex: 'note' },
  ];

  return (
    <Card title={t('common:m474')} extra={
      <Space>
        <InputNumber placeholder={t('common:m475')} value={assetId} onChange={setAssetId} style={{ width: 160 }} />
        <Button type="primary" onClick={load}>{t('common:m476')}</Button>
      </Space>
    }>
      <Spin spinning={loading}>
        {!trace && <div style={{ color: 'var(--muted)', padding: 24 }}>{t('common:m477')}</div>}
        {trace && (
          <Tabs items={[
            { key: 'factory', label: t('common:m478'), children: <Descriptions column={2} bordered size="small">
              <Descriptions.Item label={t('common:m168')}>{trace.asset?.assetNo}</Descriptions.Item>
              <Descriptions.Item label={t('common:m36')}>{ASSET_TYPE.find((x) => x.value === trace.assetType)?.label || trace.assetType}</Descriptions.Item>
              <Descriptions.Item label={t('common:m182')}>{trace.asset?.serialNumber || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('common:m8')}>{enumLabel(ASSET_STATUS_LABEL, trace.status)}</Descriptions.Item>
              <Descriptions.Item label={t('common:m479')}>{trace.asset?.manufacturerId || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('common:m480')}>{trace.asset?.productId || '-'}</Descriptions.Item>
              <Descriptions.Item label="SKU ID">{trace.asset?.skuId || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('common:m183')}>{trace.asset?.qrCode || '-'}</Descriptions.Item>
            </Descriptions> },
            { key: 'life', label: `生命周期(${trace.lifecycle?.length || 0})`, children: <Table rowKey="id" size="small" dataSource={trace.lifecycle || []} columns={lifecycleCols} pagination={false} /> },
            { key: 'maint', label: `维修(${trace.maintenance?.length || 0})`, children: <Table rowKey="id" size="small" dataSource={trace.maintenance || []} columns={maintCols} pagination={false} /> },
            { key: 'usage', label: `使用(${trace.usage?.length || 0})`, children: <Table rowKey="id" size="small" dataSource={trace.usage || []} columns={usageCols} pagination={false} /> },
            { key: 'ops', label: `车辆运营(${trace.vehicleOps?.length || 0})`, children: <Table rowKey="id" size="small" dataSource={trace.vehicleOps || []} columns={vopsCols} pagination={false} /> },
            { key: 'rev', label: t('common:m481'), children: <Descriptions column={1} bordered size="small">
              <Descriptions.Item label={t('common:m482')}>{trace.totalRevenue}</Descriptions.Item>
              <Descriptions.Item label={t('common:m211')}>{t('common:m483')}</Descriptions.Item>
            </Descriptions> },
            { key: 'write', label: t('common:m484'), children: (
              <div>
                <Space style={{ marginBottom: 12 }}>
                  <InputNumber placeholder={t('common:m475')} value={writeId} onChange={setWriteId} style={{ width: 200 }} />
                  <Button onClick={() => setWriteId(assetId)}>{t('common:m485')}</Button>
                </Space>
                <Card size="small" title={t('common:m486')} style={{ marginBottom: 12 }}>
                  <Form layout="vertical" onFinish={(v) => api.post(`/v1/admin/manufacturer/assets/${writeId}/lifecycle`, v).then(() => message.success(t('common:m487'))).catch((e) => message.error(e.message))}>
                    <Form.Item name="stage" label={t('common:m463')} rules={[{ required: true }]}><Select options={[{ label: t('common:m488'), value: 'PRODUCED' }, { label: t('common:m489'), value: 'IN_TRANSIT' }, { label: t('common:m490'), value: 'IN_USE' }, { label: t('common:m491'), value: 'MAINTENANCE' }, { label: t('common:m492'), value: 'RECYCLED' }]} /></Form.Item>
                    <Form.Item name="location" label={t('common:m464')}><Input /></Form.Item>
                    <Form.Item name="note" label={t('common:m318')}><Input /></Form.Item>
                    <Button type="primary" htmlType="submit">{t('common:m493')}</Button>
                  </Form>
                </Card>
                <Card size="small" title={t('common:m27')} style={{ marginBottom: 12 }}>
                  <Form layout="vertical" onFinish={(v) => api.post(`/v1/admin/manufacturer/assets/${writeId}/maintenance`, v).then(() => message.success(t('common:m487'))).catch((e) => message.error(e.message))}>
                    <Form.Item name="mtype" label={t('common:m36')}><Input /></Form.Item>
                    <Form.Item name="vendor" label={t('common:m465')}><Input /></Form.Item>
                    <Form.Item name="cost" label={t('common:m115')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
                    <Form.Item name="note" label={t('common:m318')}><Input /></Form.Item>
                    <Button type="primary" htmlType="submit">{t('common:m493')}</Button>
                  </Form>
                </Card>
                <Card size="small" title={t('common:m494')} style={{ marginBottom: 12 }}>
                  <Form layout="vertical" onFinish={(v) => api.post(`/v1/admin/manufacturer/assets/${writeId}/usage`, v).then(() => message.success(t('common:m487'))).catch((e) => message.error(e.message))}>
                    <Form.Item name="mileageKm" label={t('common:m468')}><InputNumber style={{ width: '100%' }} /></Form.Item>
                    <Form.Item name="cycles" label={t('common:m469')}><InputNumber style={{ width: '100%' }} /></Form.Item>
                    <Form.Item name="energyKwh" label={t('common:m470')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
                    <Form.Item name="note" label={t('common:m318')}><Input /></Form.Item>
                    <Button type="primary" htmlType="submit">{t('common:m493')}</Button>
                  </Form>
                </Card>
                <Card size="small" title={t('common:m495')}>
                  <Form layout="vertical" onFinish={(v) => api.post(`/v1/admin/manufacturer/assets/${writeId}/vehicle-ops`, v).then(() => message.success(t('common:m487'))).catch((e) => message.error(e.message))}>
                    <Form.Item name="opType" label={t('common:m471')} rules={[{ required: true }]}><Select options={[{ label: t('common:m496'), value: 'PASSENGER' }, { label: t('common:m497'), value: 'LOGISTICS' }, { label: t('common:m498'), value: 'MOBILE_SELL' }, { label: t('common:m499'), value: 'ADVERTISING' }]} /></Form.Item>
                    <Form.Item name="revenue" label={t('common:m197')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
                    <Form.Item name="note" label={t('common:m318')}><Input /></Form.Item>
                    <Button type="primary" htmlType="submit">{t('common:m493')}</Button>
                  </Form>
                </Card>
              </div>
            ) },
            { key: 'qr', label: t('common:m183'), children: <div>
              <p style={{ color: 'var(--muted)' }}>{t('common:m500')}</p>
              <Input.TextArea value={trace.asset?.qrCode || ''} rows={3} readOnly />
            </div> },
            // P3：资产「任务收益」Tab —— 读 GET /api/v1/tasks/assets/{assetId}/task-earnings。
            { key: 'taskEarnings', label: t('task:trace.tab'), children: <AssetTaskEarnings assetId={assetId} /> },
          ]} />
        )}
      </Spin>
    </Card>
  );
}
