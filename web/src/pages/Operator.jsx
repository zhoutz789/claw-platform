import React, { useState } from 'react';
import { Card, Form, InputNumber, Input, Button, message, Tabs, Table, Tag, Empty, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { useTranslation } from 'react-i18next';

const q = (url, params) => api.post(url + '?' + new URLSearchParams(params).toString());

export default function Operator() {  const { t } = useTranslation('common');

  const [result, setResult] = useState(null);
  const [accounts, setAccounts] = useState([]);
  const [events, setEvents] = useState([]);
  const [accountForm] = Form.useForm();
  const [bondForm] = Form.useForm();
  const [eventForm] = Form.useForm();

  const show = (d) => setResult(d);
  const loadAccounts = (operatorId) => operatorId && api.get('/v1/admin/operator/accounts?operatorId=' + operatorId).then(setAccounts).catch((e) => message.error(e.message));
  const loadEvents = () => api.get('/v1/admin/operator/risk-events/unresolved').then(setEvents).catch((e) => message.error(e.message));

  const accountTab = (
    <div>
      <Card size="small" title={t('common:m501')} style={{ marginBottom: 12 }}>
        <Form form={accountForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/operator/accounts', v).then((d) => { show(d); loadAccounts(v.operatorId); }).catch((e) => message.error(e.message))}>
          <Form.Item name="operatorId" label={t('common:m502')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label={t('common:m503')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="type" label={t('common:m504')} rules={[{ required: true }]}><Select options={[{ label: t('common:m505'), value: 'MANAGEMENT_FEE' }, { label: t('common:m506'), value: 'SERVICE_FEE' }, { label: t('common:m507'), value: 'PV_REVENUE' }, { label: t('common:m492'), value: 'RECOVERY' }]} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m508')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m509')} extra={<Button onClick={() => { const o = accountForm.getFieldValue('operatorId'); loadAccounts(o); }}>{t('common:m510')}</Button>}>
        <Table rowKey="id" dataSource={accounts} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('common:m511'), dataIndex: 'operatorId', width: 90 },
            { title: t('common:m512'), dataIndex: 'stationId', width: 90 },
            { title: t('common:m36'), dataIndex: 'type', width: 120 },
            { title: t('common:m285'), dataIndex: 'balance', width: 100 },
          ]} />
        {accounts.length === 0 && <Empty description={t('common:m513')} />}
      </Card>
    </div>
  );

  const bondTab = (
    <div>
      <Card size="small" title={t('common:m514')} style={{ marginBottom: 12 }}>
        <Form form={bondForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/operator/bonds', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="operatorId" label={t('common:m502')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label={t('common:m503')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="operatorType" label={t('common:m515')} rules={[{ required: true }]}><Select options={[{ label: t('common:m516'), value: 'OWNED' }, { label: t('common:m517'), value: 'FRANCHISE' }]} /></Form.Item>
          <Form.Item name="baseBond" label={t('common:m518')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="managedAssetValue" label={t('common:m519')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="bondRate" label={t('common:m520')}><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m521')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m522')}>
        <Form layout="vertical" onFinish={(v) => q('/v1/admin/operator/bonds/' + v.bondId + '/post', { amount: v.amount }).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="bondId" label={t('common:m523')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="amount" label={t('common:m524')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m525')}</Button>
        </Form>
      </Card>
    </div>
  );

  const eventTab = (
    <div>
      <Card size="small" title={t('common:m526')}>
        <Form layout="vertical" onFinish={(v) => Promise.resolve()
          .then(() => v.approve && q('/v1/admin/operator/kyc/' + v.kycId + '/approve', { approvedBy: v.approvedBy || 1 }).then(show))
          .then(() => v.rejectReason && q('/v1/admin/operator/kyc/' + v.kycId + '/reject', { approvedBy: v.approvedBy || 1, reason: v.rejectReason }).then(show))
          .catch((e) => message.error(e.message))}>
          <Form.Item name="kycId" label={t('common:m527')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="approvedBy" label={t('common:m528')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="rejectReason" label={t('common:m529')}><Input /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m530')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m531')} style={{ marginTop: 12 }}>
        <Form form={eventForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/operator/risk-events', v).then((d) => { show(d); loadEvents(); }).catch((e) => message.error(e.message))}>
          <Form.Item name="operatorId" label={t('common:m502')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label={t('common:m503')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="eventType" label={t('common:m245')} rules={[{ required: true }]}><Select options={[{ label: t('common:m532'), value: 'BOND_SHORTFALL' }, { label: t('common:m533'), value: 'ASSET_MISSING' }, { label: t('common:m534'), value: 'RECONCILIATION_FAIL' }, { label: t('common:m535'), value: 'COMPLAINT_SPIKE' }, { label: t('common:m536'), value: 'UNUSUAL_TRANSACTION' }]} /></Form.Item>
          <Form.Item name="severity" label={t('common:m537')} rules={[{ required: true }]}><Select options={[{ label: t('common:m538'), value: 'LOW' }, { label: t('common:m539'), value: 'MEDIUM' }, { label: t('common:m540'), value: 'HIGH' }]} /></Form.Item>
          <Form.Item name="description" label={t('common:m211')}><Input /></Form.Item>
          <Form.Item name="detectedValue" label={t('common:m541')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="expectedValue" label={t('common:m542')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m543')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m544')} style={{ marginTop: 12 }} extra={<Button onClick={loadEvents}>{t('common:m248')}</Button>}>
        <Table rowKey="id" dataSource={events} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('common:m511'), dataIndex: 'operatorId', width: 90 },
            { title: t('common:m36'), dataIndex: 'eventType', width: 150 },
            { title: t('common:m537'), dataIndex: 'severity', width: 100, render: (v) => <Tag color="red">{v}</Tag> },
            { title: t('common:m58'), key: '_r', width: 90, render: (_, r) => <Button size="small" type="link" onClick={() => q('/v1/admin/operator/risk-events/' + r.id + '/resolve', { resolvedBy: 1, note: '已处置' }).then(() => { message.success(t('common:m545')); loadEvents(); }).catch((e) => message.error(e.message))}>{t('common:m546')}</Button> },
          ]} />
        {events.length === 0 && <Empty description={t('common:m547')} />}
      </Card>
    </div>
  );

  return (
    <PageCard title={t('common:m548')} subtitle="账户 / 保证金 / KYC / 风控事件">
      <Tabs items={[
        { key: 'a', label: t('common:m549'), children: accountTab },
        { key: 'b', label: t('common:m550'), children: bondTab },
        { key: 'e', label: t('common:m551'), children: eventTab },
      ]} />
      {result && <Card size="small" title={t('common:m262')} style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre></Card>}
    </PageCard>
  );
}
