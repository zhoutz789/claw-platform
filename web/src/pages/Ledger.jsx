import { Table, Tag } from 'antd';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';

const typeColor = {
  MASTER: 'green',
  ASSET: 'blue',
  SUB: 'default',
  DEPOSIT_LOCKED: 'orange',
  RESIDUAL_RESERVE: 'purple',
  BATTERY_FUND: 'cyan',
  VEHICLE_RISK: 'red',
};

export default function Ledger() {
  const { data, loading, reload } = useFetch(() => api.get('/v1/ledger/accounts'));
  const rows = data || [];
  const cols = [
    { title: '账户ID', dataIndex: 'id' },
    { title: '用户ID', dataIndex: 'userId' },
    {
      title: '账户类型',
      dataIndex: 'accountType',
      render: (v) => <Tag color={typeColor[v] || 'default'}>{v}</Tag>,
    },
    { title: '币种', dataIndex: 'currency' },
    {
      title: '余额',
      dataIndex: 'balance',
      render: (v) => `$${Number(v || 0).toFixed(2)}`,
    },
    {
      title: '冻结',
      dataIndex: 'frozen',
      render: (v) => `$${Number(v || 0).toFixed(2)}`,
    },
  ];
  return (
    <PageCard title="账本账户（平台 / 用户 / 三类托管专户）" reload={reload} loading={loading}>
      <Table rowKey="id" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
    </PageCard>
  );
}
