import { Table, Tag, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import { ScopeBanner, useCurrentScope } from '../components/inventoryShared';
import api from '../api';
import dayjs from 'dayjs';

const statusColor = {
  IN_STOCK: 'green',
  IN_USE: 'blue',
  SHARED: 'purple',
  REPAIR: 'orange',
  DISABLED: 'default',
};

export default function Assets() {
  const { data, loading, reload } = useFetch(() => api.get('/v1/assets?page=0&size=50'));
  const navigate = useNavigate();
  const rows = data || [];
  const scope = useCurrentScope();
  const cols = [
    { title: 'ID', dataIndex: 'id' },
    { title: '类型', dataIndex: 'assetType', render: (v) => <Tag>{v}</Tag> },
    { title: '资产编号', dataIndex: 'assetNo' },
    { title: '序列号', dataIndex: 'serialNumber' },
    { title: '二维码', dataIndex: 'qrCode', ellipsis: true },
    { title: '归属用户', dataIndex: 'ownerId' },
    { title: '使用用户', dataIndex: 'userId' },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v) => <Tag color={statusColor[v] || 'default'}>{v}</Tag>,
    },
    {
      title: '溯源',
      render: (_, r) => <Button size="small" type="link" onClick={() => navigate(`/asset-trace?id=${r.id}`)}>全生命周期溯源</Button>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
  ];
  return (
    <PageCard title="资产 / 电池列表" reload={reload} loading={loading}>
      <ScopeBanner scope={scope} />
      <Table rowKey="id" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
    </PageCard>
  );
}
