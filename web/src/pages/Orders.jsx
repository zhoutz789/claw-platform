import React, { useEffect, useState } from 'react';
import { Tabs, Table, Button, Tag, message, Modal, Form, Select, Input } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { SWAP_STATUS, RENTAL_ORDER_STATUS } from '../enums';

const swapColumns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '订单号', dataIndex: 'orderNo', width: 150 },
  { title: '用户', dataIndex: 'userId', width: 90 },
  { title: '站点', dataIndex: 'stationId', width: 80 },
  { title: '状态', dataIndex: 'status', width: 110, render: (v) => <Tag>{v}</Tag> },
  { title: '押金', dataIndex: 'batteryDeposit', width: 100, render: (v) => `$${v}` },
  { title: '预估电量', dataIndex: 'estKwh', width: 100 },
  { title: '预估费用', dataIndex: 'estTotal', width: 100, render: (v) => `$${v}` },
  { title: '实结', dataIndex: 'actualTotal', width: 100, render: (v) => (v == null ? '-' : `$${v}`) },
  { title: '结算状态', dataIndex: 'settleStatus', width: 110 },
  { title: '创建时间', dataIndex: 'createdAt', width: 170 },
  {
    title: '操作', key: '_a', width: 110,
    render: (_, r) => <Button size="small" type="link" onClick={() => openStatus(r)}>调整状态</Button>,
  },
];

const rentalColumns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '订单号', dataIndex: 'orderNo', width: 150 },
  { title: '资产ID', dataIndex: 'assetId', width: 90 },
  { title: '承租人', dataIndex: 'renterUserId', width: 90 },
  { title: '站点', dataIndex: 'stationId', width: 80 },
  { title: '租赁类型', dataIndex: 'rentalType', width: 110 },
  { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag>{v}</Tag> },
  { title: '总费用', dataIndex: 'totalFee', width: 100, render: (v) => `$${v}` },
  { title: '资产方', dataIndex: 'ownerShare', render: (v) => `$${v}` },
  { title: '站点方', dataIndex: 'stationShare', render: (v) => `$${v}` },
  { title: '平台', dataIndex: 'platformShare', render: (v) => `$${v}` },
  { title: '保险', dataIndex: 'insuranceShare', render: (v) => `$${v}` },
];

function SwapPanel() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [target, setTarget] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const load = () => {
    setLoading(true);
    api.get('/v1/admin/orders/swap').then(setData).catch((e) => message.error(e.message)).finally(() => setLoading(false));
  };
  useEffect(load, []);

  const openStatus = (r) => {
    setTarget(r);
    form.resetFields();
    form.setFieldValue('status', r.status);
    setOpen(true);
  };

  const submit = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      await api.put(`/v1/admin/orders/swap/${target.id}/status`, v);
      message.success('状态已更新');
      setOpen(false);
      load();
    } catch (e) { message.error(`更新失败：${e.message}`); }
    finally { setSubmitting(false); }
  };

  return (
    <>
      <Table rowKey="id" loading={loading} dataSource={data} columns={swapColumns} pagination={{ pageSize: 10 }} size="middle" scroll={{ x: 'max-content' }} />
      <Modal title={`调整换电订单状态 · #${target ? target.id : ''}`} open={open} onOk={submit} confirmLoading={submitting} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="status" label="订单状态" rules={[{ required: true }]}>
            <Select options={SWAP_STATUS} />
          </Form.Item>
          <Form.Item name="cancelReason" label="取消原因（取消时填写）"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>
    </>
  );
}

function RentalPanel() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    setLoading(true);
    api.get('/v1/admin/orders/rental').then(setData).catch((e) => message.error(e.message)).finally(() => setLoading(false));
  }, []);
  return <Table rowKey="id" loading={loading} dataSource={data} columns={rentalColumns} pagination={{ pageSize: 10 }} size="middle" scroll={{ x: 'max-content' }} />;
}

export default function Orders() {
  return (
    <Tabs defaultActiveKey="swap" items={[
      {
        key: 'swap', label: '换电订单',
        children: (
          <PageCard title="换电订单" subtitle="换电订单查询与运营状态调整">
            <SwapPanel />
          </PageCard>
        ),
      },
      {
        key: 'rental', label: '租赁订单（只读）',
        children: (
          <PageCard title="租赁订单" subtitle="资产租赁订单只读查询">
            <RentalPanel />
          </PageCard>
        ),
      },
    ]} />
  );
}
