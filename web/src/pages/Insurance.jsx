import React, { useState } from 'react';
import { Card, Form, InputNumber, Input, Button, message, Tabs, Table, Tag, Empty, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { useTranslation } from 'react-i18next';

const q = (url, params) => api.post(url + '?' + new URLSearchParams(params).toString());

export default function Insurance() {  const { t } = useTranslation('common');

  const [result, setResult] = useState(null);
  const [pending, setPending] = useState([]);
  const [policyForm] = Form.useForm();
  const [claimForm] = Form.useForm();

  const show = (d) => setResult(d);
  const loadPending = () => api.get('/v1/admin/insurance/claims/pending').then(setPending).catch((e) => message.error(e.message));

  const policyTab = (
    <Card size="small" title={t('common:m583')}>
      <Form form={policyForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/insurance/policies', v).then(show).catch((e) => message.error(e.message))}>
        <Form.Item name="assetId" label={t('common:m213')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="ownerUserId" label={t('common:m214')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="templateId" label={t('common:m584')}><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="type" label={t('common:m585')} rules={[{ required: true }]}><Select options={[{ label: t('common:m586'), value: 'THIRD_PARTY_LIABILITY' }, { label: t('common:m587'), value: 'ASSET_LOSS' }, { label: t('common:m588'), value: 'BATTERY_DAMAGE' }]} /></Form.Item>
        <Form.Item name="provider" label={t('common:m589')}><Input /></Form.Item>
        <Form.Item name="coverage" label={t('common:m590')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
        <Form.Item name="deductible" label={t('common:m591')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
        <Form.Item name="monthlyPremium" label={t('common:m592')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
        <Button type="primary" htmlType="submit">{t('common:m593')}</Button>
      </Form>
    </Card>
  );

  const claimTab = (
    <div>
      <Card size="small" title={t('common:m594')} style={{ marginBottom: 12 }}>
        <Form form={claimForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/insurance/claims', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="insuranceId" label={t('common:m595')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="assetId" label={t('common:m213')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="claimantUserId" label={t('common:m596')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="claimType" label={t('common:m597')} rules={[{ required: true }]}><Select options={[{ label: t('common:m598'), value: 'THIRD_PARTY_INJURY' }, { label: t('common:m599'), value: 'THIRD_PARTY_PROPERTY' }, { label: t('common:m600'), value: 'ASSET_LOST' }, { label: t('common:m588'), value: 'BATTERY_DAMAGE' }]} /></Form.Item>
          <Form.Item name="location" label={t('common:m601')}><Input /></Form.Item>
          <Form.Item name="description" label={t('common:m602')}><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="damageAmount" label={t('common:m603')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m604')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m605')}>
        <Form layout="vertical" onFinish={(v) => Promise.resolve()
          .then(() => v.assessedAmount != null && q('/v1/admin/insurance/claims/' + v.claimId + '/assess', { reviewedBy: v.reviewedBy || 1, assessedAmount: v.assessedAmount, notes: v.notes || '' }).then(show))
          .then(() => v.approve && q('/v1/admin/insurance/claims/' + v.claimId + '/approve', { ledgerTxnId: v.ledgerTxnId || ('TXN-' + v.claimId) }).then(show))
          .then(() => v.rejectReason && q('/v1/admin/insurance/claims/' + v.claimId + '/reject', { reviewedBy: v.reviewedBy || 1, reason: v.rejectReason }).then(show))
          .catch((e) => message.error(e.message))}>
          <Form.Item name="claimId" label={t('common:m606')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="reviewedBy" label={t('common:m607')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="assessedAmount" label={t('common:m608')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="notes" label={t('common:m609')}><Input /></Form.Item>
          <Form.Item name="ledgerTxnId" label={t('common:m610')}><Input /></Form.Item>
          <Form.Item name="rejectReason" label={t('common:m611')}><Input /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m460')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m612')} style={{ marginTop: 12 }} extra={<Button onClick={loadPending}>{t('common:m248')}</Button>}>
        <Table rowKey="id" dataSource={pending} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('common:m613'), dataIndex: 'insuranceId', width: 90 },
            { title: t('common:m560'), dataIndex: 'assetId', width: 90 },
            { title: t('common:m8'), dataIndex: 'status', width: 100, render: (v) => <Tag>{v}</Tag> },
            { title: t('common:m614'), dataIndex: 'damageAmount', width: 90 },
          ]} />
        {pending.length === 0 && <Empty description={t('common:m615')} />}
      </Card>
    </div>
  );

  return (
    <PageCard title={t('common:m616')} subtitle="投保 / 报案 / 定损 / 审批赔付 / 拒赔">
      <Tabs items={[
        { key: 'p', label: t('common:m617'), children: policyTab },
        { key: 'c', label: t('common:m618'), children: claimTab },
      ]} />
      {result && <Card size="small" title={t('common:m262')} style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre></Card>}
    </PageCard>
  );
}
