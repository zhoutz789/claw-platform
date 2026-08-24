import React, { useState } from 'react';
import { Card, Form, InputNumber, Input, Button, message, Tabs, Table, Tag, Empty, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';

const q = (url, params) => api.post(url + '?' + new URLSearchParams(params).toString());

export default function Recovery() {
  const [result, setResult] = useState(null);
  const [blacklist, setBlacklist] = useState([]);
  const [valuationForm] = Form.useForm();
  const [cashForm] = Form.useForm();
  const [tradeForm] = Form.useForm();
  const [scoreForm] = Form.useForm();
  const [blForm] = Form.useForm();

  const show = (d) => setResult(d);

  const loadBlacklist = () => api.get('/v1/admin/recovery/blacklist').then(setBlacklist).catch((e) => message.error(e.message));

  const valuationTab = (
    <div>
      <Card size="small" title="创建残值估价" style={{ marginBottom: 12 }}>
        <Form form={valuationForm} layout="vertical" onFinish={(v) => q('/v1/admin/recovery/valuations', {}).catch(() => {})
          .then(() => api.post('/v1/admin/recovery/valuations', v).then(show).catch((e) => message.error(e.message)))}>
          <Form.Item name="assetId" label="资产ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerUserId" label="业主用户ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="soh" label="SOH(%)"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="usageYears" label="已用年限"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="brand" label="品牌"><Input /></Form.Item>
          <Form.Item name="model" label="型号"><Input /></Form.Item>
          <Form.Item name="cycleCount" label="循环次数"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">创建估价</Button>
        </Form>
      </Card>
      <Card size="small" title="三方估价与定稿（基于 valuationId）">
        <Form layout="vertical" onFinish={(v) => Promise.resolve()
          .then(() => v.systemEstimate != null && q('/v1/admin/recovery/valuations/system-estimate', { valuationId: v.valuationId, estimate: v.systemEstimate }).then(show))
          .then(() => v.stationEstimate != null && q('/v1/admin/recovery/valuations/station-estimate', { valuationId: v.valuationId, estimate: v.stationEstimate }).then(show))
          .then(() => v.thirdPartyEstimate != null && q('/v1/admin/recovery/valuations/third-party-estimate', { valuationId: v.valuationId, estimate: v.thirdPartyEstimate, thirdPartyName: v.thirdPartyName || '第三方', reportUrl: v.reportUrl || '' }).then(show))
          .then(() => v.finalize && q('/v1/admin/recovery/valuations/' + v.valuationId + '/finalize', {}).then(show))
          .catch((e) => message.error(e.message))}>
          <Form.Item name="valuationId" label="估价ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="systemEstimate" label="系统估价"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationEstimate" label="站点估价"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="thirdPartyEstimate" label="第三方估价"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="thirdPartyName" label="第三方名称"><Input /></Form.Item>
          <Form.Item name="reportUrl" label="报告URL"><Input /></Form.Item>
          <Button type="primary" htmlType="submit">提交估价并定稿</Button>
        </Form>
      </Card>
    </div>
  );

  const orderTab = (
    <div>
      <Card size="small" title="现金回收" style={{ marginBottom: 12 }}>
        <Form form={cashForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/recovery/cash', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="assetId" label="资产ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerUserId" label="业主用户ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="valuationId" label="估价ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownershipId" label="产权ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="recoveryPrice" label="回收价" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="processingFee" label="处理费"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">创建现金回收</Button>
        </Form>
      </Card>
      <Card size="small" title="以旧换新">
        <Form form={tradeForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/recovery/trade-in', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="assetId" label="旧资产ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerUserId" label="业主用户ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="valuationId" label="估价ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownershipId" label="产权ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="oldValuation" label="旧资产估值"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="newAssetId" label="新资产ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="newAssetPrice" label="新资产价"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">创建以旧换新</Button>
        </Form>
      </Card>
      <Card size="small" title="确认 / 完成回收单">
        <Form layout="vertical" onFinish={(v) => Promise.resolve()
          .then(() => v.confirmId && q('/v1/admin/recovery/orders/' + v.confirmId + '/confirm', {}).then(show))
          .then(() => v.completeId && q('/v1/admin/recovery/orders/' + v.completeId + '/complete', { ledgerTxnId: v.ledgerTxnId || ('TXN-' + v.completeId) }).then(show))
          .catch((e) => message.error(e.message))}>
          <Form.Item name="confirmId" label="确认回收单ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="completeId" label="完成回收单ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ledgerTxnId" label="记账流水号"><Input /></Form.Item>
          <Button type="primary" htmlType="submit">确认/完成</Button>
        </Form>
      </Card>
    </div>
  );

  const scoreTab = (
    <Card size="small" title="信用分（Claw Score）">
      <Form form={scoreForm} layout="vertical" onFinish={(v) => {
        if (v.delta != null) q('/v1/admin/recovery/scores/' + v.userId, { delta: v.delta, eventType: v.eventType || 'ADJUST', detail: v.detail || '' })
          .then(show).catch((e) => message.error(e.message));
        else api.get('/v1/admin/recovery/scores/' + v.userId).then(show).catch((e) => message.error(e.message));
      }}>
        <Form.Item name="userId" label="用户ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="delta" label="调整分(留空=仅查询)"><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="eventType" label="事件类型"><Input /></Form.Item>
        <Form.Item name="detail" label="说明"><Input /></Form.Item>
        <Button type="primary" htmlType="submit">查询/调整</Button>
      </Form>
    </Card>
  );

  const blacklistTab = (
    <div>
      <Card size="small" title="黑名单" extra={<Button onClick={loadBlacklist}>刷新</Button>}>
        <Table rowKey="id" dataSource={blacklist} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: '类型', dataIndex: 'type', width: 100 },
            { title: '目标ID', dataIndex: 'targetId', width: 90 },
            { title: '原因', dataIndex: 'reason', ellipsis: true },
            { title: '解除', key: '_r', width: 90, render: (_, r) => <Button size="small" type="link" onClick={() => q('/v1/admin/recovery/blacklist/' + r.id + '/resolve', { resolvedBy: 1 }).then(() => { message.success('已解除'); loadBlacklist(); }).catch((e) => message.error(e.message))}>解除</Button> },
          ]} />
        {blacklist.length === 0 && <Empty description="暂无黑名单" />}
      </Card>
      <Card size="small" title="加入黑名单" style={{ marginTop: 12 }}>
        <Form form={blForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/recovery/blacklist', v).then(() => { message.success('已加入'); loadBlacklist(); blForm.resetFields(); }).catch((e) => message.error(e.message))}>
          <Form.Item name="type" label="类型" rules={[{ required: true }]}><Select options={[{ label: '用户封禁', value: 'USER_BANNED' }]} /></Form.Item>
          <Form.Item name="targetId" label="目标ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="reason" label="原因"><Input /></Form.Item>
          <Form.Item name="description" label="说明"><Input /></Form.Item>
          <Form.Item name="blacklistedBy" label="操作人ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">加入黑名单</Button>
        </Form>
      </Card>
    </div>
  );

  return (
    <PageCard title="残值回收" subtitle="估价三方流程 / 现金回收 / 以旧换新 / 信用分 / 黑名单">
      <Tabs items={[
        { key: 'v', label: '估价', children: valuationTab },
        { key: 'o', label: '回收单', children: orderTab },
        { key: 's', label: '信用分', children: scoreTab },
        { key: 'b', label: '黑名单', children: blacklistTab },
      ]} />
      {result && <Card size="small" title="操作结果" style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre></Card>}
    </PageCard>
  );
}
