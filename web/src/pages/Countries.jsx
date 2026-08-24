import { Table, Tag } from 'antd';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';

export default function Countries() {
  const { data, loading, reload } = useFetch(() => api.get('/v1/countries'));
  const rows = data || [];
  const cols = [
    { title: '代码', dataIndex: 'code' },
    { title: '英文名', dataIndex: 'nameEn' },
    { title: '本地名', dataIndex: 'nameLocal' },
    { title: '区域', dataIndex: 'region' },
    { title: '币种', dataIndex: 'currencyCode' },
    { title: '默认语言', dataIndex: 'defaultLocale' },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v) => <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v}</Tag>,
    },
    { title: '节点角色', dataIndex: 'nodeRole' },
    { title: '贸易政策', dataIndex: 'tradePolicy' },
    {
      title: '数据驻留',
      dataIndex: 'dataResidency',
      render: (v) => (v ? '是' : '否'),
    },
  ];
  return (
    <PageCard title="国家 / 法域注册表" reload={reload} loading={loading}>
      <Table rowKey="code" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
    </PageCard>
  );
}
