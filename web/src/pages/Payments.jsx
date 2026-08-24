import { Table, Empty } from 'antd';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';

function buildColumns(rows) {
  if (!rows.length) return [];
  const sample = rows[0];
  return Object.keys(sample).map((k) => ({
    title: k,
    dataIndex: k,
    render: (v) => {
      if (v === null || v === undefined) return '-';
      if (typeof v === 'object') return JSON.stringify(v);
      return String(v);
    },
  }));
}

export default function Payments() {
  const { data, loading, reload } = useFetch(() => api.get('/v1/payments/txns'));
  const rows = data || [];
  const cols = buildColumns(rows);
  return (
    <PageCard title="支付流水" reload={reload} loading={loading}>
      {rows.length ? (
        <Table rowKey="id" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} scroll={{ x: 'max-content' }} />
      ) : (
        <Empty description="暂无支付流水（可先在用户端发起一笔换电扣款）" />
      )}
    </PageCard>
  );
}
