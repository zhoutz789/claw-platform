import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Tabs, Table, Button, Tag, message, Modal, Form, Select, Input } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { SWAP_STATUS, RENTAL_ORDER_STATUS } from '../enums';
import { listCustomerOrders } from '../api/order';
import { useTranslation } from 'react-i18next';
import { ScopeBanner, useCurrentScope } from '../components/inventoryShared';

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
    render: (_, r) => {
  const { t } = useTranslation('common');
  return (<Button size="small" type="link" onClick={() => openStatus(r)}>{t('common:m880')}</Button>);
},
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

export function SwapPanel() {  const { t } = useTranslation('common');

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
      message.success(t('common:m881'));
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
          <Form.Item name="status" label={t('common:m882')} rules={[{ required: true }]}>
            <Select options={SWAP_STATUS} />
          </Form.Item>
          <Form.Item name="cancelReason" label={t('common:m883')}><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>
    </>
  );
}

export function RentalPanel() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    setLoading(true);
    api.get('/v1/admin/orders/rental').then(setData).catch((e) => message.error(e.message)).finally(() => setLoading(false));
  }, []);
  return <Table rowKey="id" loading={loading} dataSource={data} columns={rentalColumns} pagination={{ pageSize: 10 }} size="middle" scroll={{ x: 'max-content' }} />;
}

export function CustomerOrdersPanel() {  const { t } = useTranslation('common');

  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const load = () => {
    setLoading(true);
    listCustomerOrders().then((d) => setData(Array.isArray(d) ? d : []))
      .catch((e) => message.error(e.message))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: t('common:m884'), dataIndex: 'orderNo', width: 160 },
    { title: t('common:m885'), dataIndex: 'buyerUserId', width: 90 },
    { title: t('common:m8'), dataIndex: 'status', width: 110, render: (v) => <Tag>{v}</Tag> },
    {
      title: t('common:m58'), width: 110,
      render: (_, r) => (
        <Button size="small" type="link" onClick={() => navigate(`/order-manage?orderId=${r.id}`)}>{t('common:m886')}</Button>
      ),
    },
  ];

  return (
    <PageCard title={t('common:m887')} subtitle="选择订单进入发货前逐台登记">
      <Table rowKey="id" loading={loading} dataSource={data} columns={columns}
        pagination={{ pageSize: 10 }} size="middle" scroll={{ x: 'max-content' }} />
    </PageCard>
  );
}

export default function Orders() {  const { t } = useTranslation('common');
  const scope = useCurrentScope();

  return (
    <>
    <ScopeBanner scope={scope} />
    <Tabs defaultActiveKey="swap" items={[
      {
        key: 'swap', label: t('common:m888'),
        children: (
          <PageCard title={t('common:m888')} subtitle="换电订单查询与运营状态调整">
            <SwapPanel />
          </PageCard>
        ),
      },
      {
        key: 'rental', label: t('common:m889'),
        children: (
          <PageCard title={t('common:m890')} subtitle="资产租赁订单只读查询">
            <RentalPanel />
          </PageCard>
        ),
      },
      {
        key: 'customer', label: t('common:m887'),
        children: <CustomerOrdersPanel />,
      },
    ]} />
    </>
  );
}
