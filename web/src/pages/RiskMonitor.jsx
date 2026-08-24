import React, { useEffect, useState } from 'react';
import { Tabs, Table, Button, Modal, Form, Input, InputNumber, Select, Tag, message, Space } from 'antd';
import CrudTable from '../components/CrudTable';
import api from '../api';
import { RISK_METRIC_TYPE, RISK_MONITOR_STATUS, FUND_STATUS } from '../enums';

const monitorColumns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '站点', dataIndex: 'stationId', width: 80 },
  { title: '运营方', dataIndex: 'operatorId', width: 90 },
  { title: '指标', dataIndex: 'metricType', width: 180 },
  { title: '观测值', dataIndex: 'metricValue', width: 100 },
  { title: '阈值', dataIndex: 'threshold', width: 100 },
  { title: '风险分', dataIndex: 'riskScore', width: 90 },
  {
    title: '状态', dataIndex: 'status', width: 120,
    render: (v) => {
      const c = v === 'NORMAL' ? 'green' : v === 'WARNING' ? 'orange' : v === 'CRITICAL' ? 'red' : 'purple';
      return <Tag color={c}>{v || '-'}</Tag>;
    },
  },
];

const monitorFields = [
  { name: 'stationId', label: '站点ID', type: 'number', required: true },
  { name: 'operatorId', label: '运营方ID', type: 'number' },
  { name: 'metricType', label: '监控指标', type: 'select', options: RISK_METRIC_TYPE, required: true },
  { name: 'metricValue', label: '观测值', type: 'number' },
  { name: 'threshold', label: '阈值', type: 'number' },
  { name: 'baseline', label: '基线', type: 'number' },
  { name: 'riskScore', label: '风险分', type: 'number' },
  { name: 'status', label: '状态', type: 'select', options: RISK_MONITOR_STATUS, initialValue: 'NORMAL' },
  { name: 'triggeredReason', label: '触发原因', type: 'textarea' },
  { name: 'resolutionNote', label: '处置说明', type: 'textarea' },
];

function InsuranceFundPanel() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const load = () => {
    setLoading(true);
    api.get('/v1/admin/risk/insurance-fund').then(setData).catch((e) => message.error(e.message)).finally(() => setLoading(false));
  };
  useEffect(load, []);

  const openEdit = (r) => {
    setEditing(r);
    form.resetFields();
    ['totalBalance', 'coverageRatio', 'status', 'lastUpdatedBy', 'auditNotes'].forEach((k) => form.setFieldValue(k, r[k]));
    setOpen(true);
  };

  const submit = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      await api.put(`/v1/admin/risk/insurance-fund/${editing.id}`, v);
      message.success('已调整');
      setOpen(false);
      load();
    } catch (e) { message.error(`调整失败：${e.message}`); }
    finally { setSubmitting(false); }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '总余额', dataIndex: 'totalBalance', render: (v) => `$${v}` },
    { title: '已归集', dataIndex: 'totalCollected', render: (v) => `$${v}` },
    { title: '已赔付', dataIndex: 'totalClaimed', render: (v) => `$${v}` },
    { title: '覆盖率', dataIndex: 'coverageRatio', render: (v) => `${v}%` },
    { title: '总资产值', dataIndex: 'totalAssetValue', render: (v) => `$${v}` },
    {
      title: '状态', dataIndex: 'status', render: (v) => {
        const c = v === 'HEALTHY' ? 'green' : v === 'LOW' ? 'orange' : 'red';
        return <Tag color={c}>{v}</Tag>;
      },
    },
    { title: '操作', key: '_a', width: 90, render: (_, r) => <Button size="small" type="link" onClick={() => openEdit(r)}>调整</Button> },
  ];

  return (
    <>
      <Table rowKey="id" loading={loading} dataSource={data} columns={columns} pagination={false} size="middle" />
      <Modal title="调整保险基金" open={open} onOk={submit} confirmLoading={submitting} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="totalBalance" label="总余额" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="coverageRatio" label="覆盖率(%)"><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}><Select options={FUND_STATUS} /></Form.Item>
          <Form.Item name="lastUpdatedBy" label="操作人ID"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="auditNotes" label="审计说明"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>
    </>
  );
}

export default function RiskMonitor() {
  return (
    <Tabs defaultActiveKey="monitors" items={[
      {
        key: 'monitors', label: '风控监控指标',
        children: (
          <CrudTable title="风控监控指标" subtitle="站点/运营方风险指标的配置与维护" endpoint="/v1/admin/risk/monitors" columns={monitorColumns} fields={monitorFields} rowKey="id" />
        ),
      },
      { key: 'fund', label: '保险基金池', children: <><div style={{ margin: '8px 0 12px', color: '#666' }}>平台保险基金池余额与赔付能力监控（只读 + 人工调整）</div><InsuranceFundPanel /></> },
    ]} />
  );
}
