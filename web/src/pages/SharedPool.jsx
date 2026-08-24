import React, { useState } from 'react';
import { Card, Form, InputNumber, Input, Button, message, Tabs, Table, Tag, Empty, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';

const q = (url, params) => api.post(url + '?' + new URLSearchParams(params).toString());

export default function SharedPool() {
  const [result, setResult] = useState(null);
  const [available, setAvailable] = useState([]);
  const [owner, setOwner] = useState([]);
  const [renter, setRenter] = useState([]);
  const [poolForm] = Form.useForm();
  const [rentalForm] = Form.useForm();

  const show = (d) => setResult(d);
  const loadAvailable = (stationId) => stationId && api.get('/v1/admin/shared-pool/available?stationId=' + stationId).then(setAvailable).catch((e) => message.error(e.message));
  const loadOwner = (ownerUserId) => ownerUserId && api.get('/v1/admin/shared-pool/owner?ownerUserId=' + ownerUserId).then(setOwner).catch((e) => message.error(e.message));
  const loadRenter = (renterUserId) => renterUserId && api.get('/v1/admin/shared-pool/renter?renterUserId=' + renterUserId).then(setRenter).catch((e) => message.error(e.message));

  const poolTab = (
    <div>
      <Card size="small" title="资产入池" style={{ marginBottom: 12 }}>
        <Form form={poolForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/shared-pool/entries', v).then((d) => { show(d); loadAvailable(v.stationId); }).catch((e) => message.error(e.message))}>
          <Form.Item name="assetId" label="资产ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerUserId" label="业主ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label="站点ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerSplitRate" label="业主分成率" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
          <Form.Item name="stationSplitRate" label="站点分成率" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
          <Form.Item name="dailyUsageFee" label="日使用费"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="perSwapFee" label="每次换电费"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">入池</Button>
        </Form>
      </Card>
      <Card size="small" title="站点可用池资产" extra={<Button onClick={() => loadAvailable(poolForm.getFieldValue('stationId'))}>刷新</Button>}>
        <Table rowKey="id" dataSource={available} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: '资产', dataIndex: 'assetId', width: 90 },
            { title: '业主', dataIndex: 'ownerUserId', width: 90 },
            { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag>{v}</Tag> },
          ]} />
        {available.length === 0 && <Empty description="该站点暂无可用池资产" />}
      </Card>
    </div>
  );

  const rentalTab = (
    <div>
      <Card size="small" title="创建租赁" style={{ marginBottom: 12 }}>
        <Form form={rentalForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/shared-pool/rentals', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="assetId" label="资产ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="renterUserId" label="承租方ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label="站点ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="rentalType" label="租赁类型" rules={[{ required: true }]}>
            <Select placeholder="选择租赁类型" options={[
              { value: 'BATTERY_EXCHANGE', label: '电池换电' },
              { value: 'VEHICLE_RENTAL', label: '车辆租赁' },
            ]} />
          </Form.Item>
          <Form.Item name="poolEntryId" label="池条目ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">创建租赁</Button>
        </Form>
      </Card>
      <Card size="small" title="完成租赁（基于 rentalOrderId）" style={{ marginBottom: 12 }}>
        <Form layout="vertical" onFinish={(v) => q('/v1/admin/shared-pool/rentals/' + v.rentalOrderId + '/complete', { totalFee: v.totalFee }).then((d) => { show(d); loadRenter(v.renterUserId); }).catch((e) => message.error(e.message))}>
          <Form.Item name="rentalOrderId" label="租赁单ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="totalFee" label="总费用" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="renterUserId" label="承租方ID(用于刷新)"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">完成并分账</Button>
        </Form>
      </Card>
      <Card size="small" title="出池（基于 poolEntryId）">
        <Form layout="vertical" onFinish={(v) => q('/v1/admin/shared-pool/entries/' + v.poolEntryId + '/remove', {}).then((d) => { show(d); loadAvailable(poolForm.getFieldValue('stationId')); }).catch((e) => message.error(e.message))}>
          <Form.Item name="poolEntryId" label="池条目ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">出池</Button>
        </Form>
      </Card>
    </div>
  );

  const myTab = (
    <div>
      <Card size="small" title="我的池资产" extra={<Button onClick={() => loadOwner(poolForm.getFieldValue('ownerUserId'))}>刷新</Button>}>
        <Table rowKey="id" dataSource={owner} pagination={false} size="small"
          columns={[{ title: 'ID', dataIndex: 'id', width: 70 }, { title: '资产', dataIndex: 'assetId', width: 90 }, { title: '状态', dataIndex: 'status', render: (v) => <Tag>{v}</Tag> }]} />
        {owner.length === 0 && <Empty description="无" />}
      </Card>
      <Card size="small" title="我的租赁单" style={{ marginTop: 12 }} extra={<Button onClick={() => loadRenter(rentalForm.getFieldValue('renterUserId'))}>刷新</Button>}>
        <Table rowKey="id" dataSource={renter} pagination={false} size="small"
          columns={[{ title: 'ID', dataIndex: 'id', width: 70 }, { title: '资产', dataIndex: 'assetId', width: 90 }, { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag>{v}</Tag> }, { title: '费用', dataIndex: 'totalFee', width: 90 }]} />
        {renter.length === 0 && <Empty description="无" />}
      </Card>
    </div>
  );

  return (
    <PageCard title="共享池" subtitle="资产入池 / 租赁 / 完成分账 / 出池">
      <Tabs items={[
        { key: 'p', label: '入池', children: poolTab },
        { key: 'r', label: '租赁', children: rentalTab },
        { key: 'm', label: '我的', children: myTab },
      ]} />
      {result && <Card size="small" title="操作结果" style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre></Card>}
    </PageCard>
  );
}
