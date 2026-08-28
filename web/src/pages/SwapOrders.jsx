import React, { useEffect, useState } from 'react';
import { Table, Tag, Popconfirm, Button, message } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import dayjs from 'dayjs';

const statusColor = {
  PENDING: 'orange',
  COMPLETED: 'green',
  CANCELLED: 'red',
  CONFIRMED: 'blue',
  SWAPPING: 'cyan',
};

const columns = [
  { title: '订单号', dataIndex: 'orderNo', key: 'orderNo' },
  { title: '用户', dataIndex: 'userId', key: 'userId' },
  { title: '站点', dataIndex: 'stationName', key: 'stationName', render: (v, r) => v || r.stationId },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    render: (v) => <Tag color={statusColor[v] || 'default'}>{v}</Tag>,
  },
  { title: '押金', dataIndex: 'batteryDeposit', key: 'batteryDeposit', render: (v) => `$${Number(v || 0).toFixed(2)}` },
  {
    title: '预估电费',
    dataIndex: 'estElecFee',
    key: 'estElecFee',
    render: (v) => `$${Number(v || 0).toFixed(2)}`,
  },
  {
    title: '预估服务费',
    dataIndex: 'estServiceFee',
    key: 'estServiceFee',
    render: (v) => `$${Number(v || 0).toFixed(2)}`,
  },
  {
    title: '结算状态',
    dataIndex: 'settleStatus',
    key: 'settleStatus',
    render: (v) => (v ? <Tag>{v}</Tag> : '-'),
  },
  {
    title: '创建时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
  },
  {
    title: '操作',
    key: 'act',
    render: (_, r) =>
      r.status === 'PENDING' ? (
        <Popconfirm title="确认该换电订单？" onConfirm={() => handleConfirm(r.orderNo)} okText="确认" cancelText="取消">
          <Button size="small" type="primary">
            确认
          </Button>
        </Popconfirm>
      ) : (
        '-'
      ),
  },
];

async function handleConfirm(no) {
  try {
    await api.post(`/v1/swap-orders/${no}/confirm`);
    message.success(`订单 ${no} 已确认`);
    window.dispatchEvent(new CustomEvent('claw:reload-swap-orders'));
  } catch (e) {
    message.error(e.message);
  }
}

export default function SwapOrders() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);

  const load = () => {
    setLoading(true);
    api
      .get('/v1/swap-orders')
      .then((res) => setData(Array.isArray(res) ? res : []))
      .catch((e) => message.error(`加载失败：${e.message}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    const onReload = () => load();
    window.addEventListener('claw:reload-swap-orders', onReload);
    return () => window.removeEventListener('claw:reload-swap-orders', onReload);
  }, []);

  return (
    <PageCard title="换电订单" reload={load} loading={loading}>
      <Table
        rowKey="orderNo"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{ pageSize: 10 }}
        size="middle"
        scroll={{ x: 'max-content' }}
      />
    </PageCard>
  );
}
