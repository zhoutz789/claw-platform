import { Table, Tag, Popconfirm, Button, App } from 'antd';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';
import dayjs from 'dayjs';

const statusColor = {
  PENDING: 'orange',
  COMPLETED: 'green',
  CANCELLED: 'red',
  CONFIRMED: 'blue',
};

export default function SwapOrders() {
  const { message } = App.useApp();
  const { data, loading, reload } = useFetch(() => api.get('/v1/swap-orders'));

  const onConfirm = async (no) => {
    try {
      await api.post(`/v1/swap-orders/${no}/confirm`);
      message.success(`订单 ${no} 已确认`);
      reload();
    } catch (e) {
      message.error(e.message);
    }
  };

  const rows = data || [];
  const cols = [
    { title: '订单号', dataIndex: 'orderNo' },
    { title: '用户', dataIndex: 'userId' },
    { title: '站点', dataIndex: 'stationName', render: (v, r) => v || r.stationId },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v) => <Tag color={statusColor[v] || 'default'}>{v}</Tag>,
    },
    { title: '押金', dataIndex: 'batteryDeposit', render: (v) => `$${Number(v || 0).toFixed(2)}` },
    {
      title: '预估电费',
      dataIndex: 'estElecFee',
      render: (v) => `$${Number(v || 0).toFixed(2)}`,
    },
    {
      title: '预估服务费',
      dataIndex: 'estServiceFee',
      render: (v) => `$${Number(v || 0).toFixed(2)}`,
    },
    {
      title: '结算状态',
      dataIndex: 'settleStatus',
      render: (v) => (v ? <Tag>{v}</Tag> : '-'),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'act',
      render: (_, r) =>
        r.status === 'PENDING' ? (
          <Popconfirm title="确认该换电订单？" onConfirm={() => onConfirm(r.orderNo)}>
            <Button size="small" type="primary">
              确认
            </Button>
          </Popconfirm>
        ) : (
          '-'
        ),
    },
  ];
  return (
    <PageCard title="换电订单" reload={reload} loading={loading}>
      <Table rowKey="orderNo" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
    </PageCard>
  );
}
