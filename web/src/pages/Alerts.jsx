import React, { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Tag, message, Select, Space } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { RISK_EVENT_TYPE, RISK_SEVERITY, AUTO_ACTION } from '../enums';

const columns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '运营方', dataIndex: 'operatorId', width: 90 },
  { title: '站点', dataIndex: 'stationId', width: 80 },
  { title: '事件类型', dataIndex: 'eventType', width: 180 },
  {
    title: '严重度', dataIndex: 'severity', width: 100,
    render: (v) => { const c = v === 'HIGH' ? 'red' : v === 'MEDIUM' ? 'orange' : 'default'; return <Tag color={c}>{v}</Tag>; },
  },
  { title: '描述', dataIndex: 'description', ellipsis: true },
  { title: '检测值', dataIndex: 'detectedValue', width: 100 },
  { title: '预期值', dataIndex: 'expectedValue', width: 100 },
  { title: '自动动作', dataIndex: 'autoAction', width: 130 },
  {
    title: '状态', dataIndex: 'resolved', width: 90,
    render: (v) => <Tag color={v ? 'green' : 'red'}>{v ? '已处置' : '待处置'}</Tag>,
  },
  { title: '操作', key: '_a', width: 100, render: (_, r) => (!r.resolved && <Button size="small" type="primary" onClick={() => openResolve(r)}>处置</Button>) },
];

export default function Alerts() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [resolvedFilter, setResolvedFilter] = useState(false);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const load = () => {
    setLoading(true);
    api.get('/v1/admin/risk/events', { params: { resolved: resolvedFilter } })
      .then(setData).catch((e) => message.error(e.message)).finally(() => setLoading(false));
  };
  useEffect(load, [resolvedFilter]);

  const openResolve = (r) => {
    setEditing(r);
    form.resetFields();
    setOpen(true);
  };

  const submit = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      await api.post(`/v1/admin/risk/events/${editing.id}/resolve`, v);
      message.success('已处置');
      setOpen(false);
      load();
    } catch (e) { message.error(`处置失败：${e.message}`); }
    finally { setSubmitting(false); }
  };

  return (
    <PageCard title="异常告警" subtitle="风控事件（异常告警）列表与处置">
      <Space style={{ marginBottom: 12 }}>
        <span>筛选：</span>
        <Select value={resolvedFilter} style={{ width: 160 }} onChange={setResolvedFilter}
          options={[{ label: '仅待处置', value: false }, { label: '全部', value: undefined }]} />
        <Button onClick={load}>刷新</Button>
      </Space>
      <Table rowKey="id" loading={loading} dataSource={data} columns={columns} pagination={{ pageSize: 10 }} size="middle" />
      <Modal title="处置风控事件" open={open} onOk={submit} confirmLoading={submitting} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="resolvedBy" label="处置人ID" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="resolutionNote" label="处置说明" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
