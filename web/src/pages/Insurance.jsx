import React, { useState } from 'react';
import { Card, Form, InputNumber, Input, Button, message, Tabs, Table, Tag, Empty, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';

const q = (url, params) => api.post(url + '?' + new URLSearchParams(params).toString());

export default function Insurance() {
  const [result, setResult] = useState(null);
  const [pending, setPending] = useState([]);
  const [policyForm] = Form.useForm();
  const [claimForm] = Form.useForm();

  const show = (d) => setResult(d);
  const loadPending = () => api.get('/v1/admin/insurance/claims/pending').then(setPending).catch((e) => message.error(e.message));

  const policyTab = (
    <Card size="small" title="投保（创建保单）">
      <Form form={policyForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/insurance/policies', v).then(show).catch((e) => message.error(e.message))}>
        <Form.Item name="assetId" label="资产ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="ownerUserId" label="业主用户ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="templateId" label="模板ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="type" label="险种" rules={[{ required: true }]}><Select options={[{ label: '第三方责任', value: 'THIRD_PARTY_LIABILITY' }, { label: '资产损失', value: 'ASSET_LOSS' }, { label: '电池损坏', value: 'BATTERY_DAMAGE' }]} /></Form.Item>
        <Form.Item name="provider" label="承保方"><Input /></Form.Item>
        <Form.Item name="coverage" label="保额" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
        <Form.Item name="deductible" label="免赔额"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
        <Form.Item name="monthlyPremium" label="月保费"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
        <Button type="primary" htmlType="submit">创建保单</Button>
      </Form>
    </Card>
  );

  const claimTab = (
    <div>
      <Card size="small" title="报案（创建理赔）" style={{ marginBottom: 12 }}>
        <Form form={claimForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/insurance/claims', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="insuranceId" label="保单ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="assetId" label="资产ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="claimantUserId" label="报案人ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="claimType" label="理赔类型" rules={[{ required: true }]}><Select options={[{ label: '第三方人身伤害', value: 'THIRD_PARTY_INJURY' }, { label: '第三方财产', value: 'THIRD_PARTY_PROPERTY' }, { label: '资产丢失', value: 'ASSET_LOST' }, { label: '电池损坏', value: 'BATTERY_DAMAGE' }]} /></Form.Item>
          <Form.Item name="location" label="事故地点"><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="damageAmount" label="损失金额"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">报案</Button>
        </Form>
      </Card>
      <Card size="small" title="定损 / 审批 / 拒赔（基于 claimId）">
        <Form layout="vertical" onFinish={(v) => Promise.resolve()
          .then(() => v.assessedAmount != null && q('/v1/admin/insurance/claims/' + v.claimId + '/assess', { reviewedBy: v.reviewedBy || 1, assessedAmount: v.assessedAmount, notes: v.notes || '' }).then(show))
          .then(() => v.approve && q('/v1/admin/insurance/claims/' + v.claimId + '/approve', { ledgerTxnId: v.ledgerTxnId || ('TXN-' + v.claimId) }).then(show))
          .then(() => v.rejectReason && q('/v1/admin/insurance/claims/' + v.claimId + '/reject', { reviewedBy: v.reviewedBy || 1, reason: v.rejectReason }).then(show))
          .catch((e) => message.error(e.message))}>
          <Form.Item name="claimId" label="理赔ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="reviewedBy" label="审核人ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="assessedAmount" label="定损金额"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="notes" label="定损说明"><Input /></Form.Item>
          <Form.Item name="ledgerTxnId" label="赔付记账流水号"><Input /></Form.Item>
          <Form.Item name="rejectReason" label="拒赔原因"><Input /></Form.Item>
          <Button type="primary" htmlType="submit">提交</Button>
        </Form>
      </Card>
      <Card size="small" title="待处理理赔" style={{ marginTop: 12 }} extra={<Button onClick={loadPending}>刷新</Button>}>
        <Table rowKey="id" dataSource={pending} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: '保单', dataIndex: 'insuranceId', width: 90 },
            { title: '资产', dataIndex: 'assetId', width: 90 },
            { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag>{v}</Tag> },
            { title: '损失', dataIndex: 'damageAmount', width: 90 },
          ]} />
        {pending.length === 0 && <Empty description="暂无待处理理赔" />}
      </Card>
    </div>
  );

  return (
    <PageCard title="保险管理" subtitle="投保 / 报案 / 定损 / 审批赔付 / 拒赔">
      <Tabs items={[
        { key: 'p', label: '投保', children: policyTab },
        { key: 'c', label: '理赔', children: claimTab },
      ]} />
      {result && <Card size="small" title="操作结果" style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre></Card>}
    </PageCard>
  );
}
