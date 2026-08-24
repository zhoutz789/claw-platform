import React, { useState } from 'react';
import { Card, Form, InputNumber, Input, Button, message, Tabs, Table, Tag, Empty, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';

const q = (url, params) => api.post(url + '?' + new URLSearchParams(params).toString());

export default function Operator() {
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
      <Card size="small" title="创建运营方账户" style={{ marginBottom: 12 }}>
        <Form form={accountForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/operator/accounts', v).then((d) => { show(d); loadAccounts(v.operatorId); }).catch((e) => message.error(e.message))}>
          <Form.Item name="operatorId" label="运营方ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label="站点ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="type" label="账户类型" rules={[{ required: true }]}><Select options={[{ label: '管理费', value: 'MANAGEMENT_FEE' }, { label: '服务费', value: 'SERVICE_FEE' }, { label: '光伏收益', value: 'PV_REVENUE' }, { label: '回收', value: 'RECOVERY' }]} /></Form.Item>
          <Button type="primary" htmlType="submit">创建账户</Button>
        </Form>
      </Card>
      <Card size="small" title="账户列表" extra={<Button onClick={() => { const o = accountForm.getFieldValue('operatorId'); loadAccounts(o); }}>按运营方刷新</Button>}>
        <Table rowKey="id" dataSource={accounts} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: '运营方', dataIndex: 'operatorId', width: 90 },
            { title: '站点', dataIndex: 'stationId', width: 90 },
            { title: '类型', dataIndex: 'type', width: 120 },
            { title: '余额', dataIndex: 'balance', width: 100 },
          ]} />
        {accounts.length === 0 && <Empty description="暂无账户（先填写运营方ID并刷新）" />}
      </Card>
    </div>
  );

  const bondTab = (
    <div>
      <Card size="small" title="保证金台账(upsert)" style={{ marginBottom: 12 }}>
        <Form form={bondForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/operator/bonds', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="operatorId" label="运营方ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label="站点ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="operatorType" label="运营方类型" rules={[{ required: true }]}><Select options={[{ label: '直营', value: 'OWNED' }, { label: '特许经营', value: 'FRANCHISE' }]} /></Form.Item>
          <Form.Item name="baseBond" label="基准保证金" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="managedAssetValue" label="管理资产价值"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="bondRate" label="保证金率"><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
          <Button type="primary" htmlType="submit">建立/更新保证金</Button>
        </Form>
      </Card>
      <Card size="small" title="补缴保证金（基于 bondId）">
        <Form layout="vertical" onFinish={(v) => q('/v1/admin/operator/bonds/' + v.bondId + '/post', { amount: v.amount }).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="bondId" label="保证金ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="amount" label="补缴金额" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">补缴</Button>
        </Form>
      </Card>
    </div>
  );

  const eventTab = (
    <div>
      <Card size="small" title="KYC 审批（基于 kycId）">
        <Form layout="vertical" onFinish={(v) => Promise.resolve()
          .then(() => v.approve && q('/v1/admin/operator/kyc/' + v.kycId + '/approve', { approvedBy: v.approvedBy || 1 }).then(show))
          .then(() => v.rejectReason && q('/v1/admin/operator/kyc/' + v.kycId + '/reject', { approvedBy: v.approvedBy || 1, reason: v.rejectReason }).then(show))
          .catch((e) => message.error(e.message))}>
          <Form.Item name="kycId" label="KYC记录ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="approvedBy" label="审批人ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="rejectReason" label="拒批原因"><Input /></Form.Item>
          <Button type="primary" htmlType="submit">审批/拒批</Button>
        </Form>
      </Card>
      <Card size="small" title="记录风控事件" style={{ marginTop: 12 }}>
        <Form form={eventForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/operator/risk-events', v).then((d) => { show(d); loadEvents(); }).catch((e) => message.error(e.message))}>
          <Form.Item name="operatorId" label="运营方ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label="站点ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="eventType" label="事件类型" rules={[{ required: true }]}><Select options={[{ label: '保证金缺口', value: 'BOND_SHORTFALL' }, { label: '资产缺失', value: 'ASSET_MISSING' }, { label: '对账失败', value: 'RECONCILIATION_FAIL' }, { label: '投诉激增', value: 'COMPLAINT_SPIKE' }, { label: '异常交易', value: 'UNUSUAL_TRANSACTION' }]} /></Form.Item>
          <Form.Item name="severity" label="严重度" rules={[{ required: true }]}><Select options={[{ label: '低', value: 'LOW' }, { label: '中', value: 'MEDIUM' }, { label: '高', value: 'HIGH' }]} /></Form.Item>
          <Form.Item name="description" label="说明"><Input /></Form.Item>
          <Form.Item name="detectedValue" label="检测值"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="expectedValue" label="期望值"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">记录事件</Button>
        </Form>
      </Card>
      <Card size="small" title="未解决风控事件" style={{ marginTop: 12 }} extra={<Button onClick={loadEvents}>刷新</Button>}>
        <Table rowKey="id" dataSource={events} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: '运营方', dataIndex: 'operatorId', width: 90 },
            { title: '类型', dataIndex: 'eventType', width: 150 },
            { title: '严重度', dataIndex: 'severity', width: 100, render: (v) => <Tag color="red">{v}</Tag> },
            { title: '操作', key: '_r', width: 90, render: (_, r) => <Button size="small" type="link" onClick={() => q('/v1/admin/operator/risk-events/' + r.id + '/resolve', { resolvedBy: 1, note: '已处置' }).then(() => { message.success('已处置'); loadEvents(); }).catch((e) => message.error(e.message))}>处置</Button> },
          ]} />
        {events.length === 0 && <Empty description="暂无未解决事件" />}
      </Card>
    </div>
  );

  return (
    <PageCard title="运营方财务" subtitle="账户 / 保证金 / KYC / 风控事件">
      <Tabs items={[
        { key: 'a', label: '账户', children: accountTab },
        { key: 'b', label: '保证金', children: bondTab },
        { key: 'e', label: 'KYC与风控', children: eventTab },
      ]} />
      {result && <Card size="small" title="操作结果" style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre></Card>}
    </PageCard>
  );
}
