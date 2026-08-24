import { Table, Tag, Descriptions, Empty } from 'antd';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';
import dayjs from 'dayjs';

export default function Reconciliations() {
  const { data, loading, reload } = useFetch(() => api.get('/v1/reconciliations/latest'));
  if (!data) {
    return (
      <PageCard title="资金对账（最新一日）" reload={reload} loading={loading}>
        <Empty description="暂无对账数据" />
      </PageCard>
    );
  }
  const cols = [
    { title: '对账日期', dataIndex: 'runDate' },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v) => <Tag color={v === 'MATCHED' ? 'green' : 'orange'}>{v}</Tag>,
    },
    {
      title: '平台总额',
      dataIndex: 'platformTotal',
      render: (v) => `$${Number(v || 0).toFixed(2)}`,
    },
    {
      title: '银行总额',
      dataIndex: 'bankTotal',
      render: (v) => `$${Number(v || 0).toFixed(2)}`,
    },
    { title: '匹配笔数', dataIndex: 'matchedCount' },
    { title: '差异笔数', dataIndex: 'mismatchCount' },
    {
      title: '生成时间',
      dataIndex: 'createdAt',
      render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
  ];
  return (
    <PageCard title="资金对账（最新一日）" reload={reload} loading={loading}>
      <Descriptions bordered column={2} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label="对账日期">{data.runDate || '-'}</Descriptions.Item>
        <Descriptions.Item label="状态">
          <Tag color={data.status === 'MATCHED' ? 'green' : 'orange'}>{data.status}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="平台总额">
          ${Number(data.platformTotal || 0).toFixed(2)}
        </Descriptions.Item>
        <Descriptions.Item label="银行总额">
          ${Number(data.bankTotal || 0).toFixed(2)}
        </Descriptions.Item>
        <Descriptions.Item label="匹配 / 差异">
          {data.matchedCount} / {data.mismatchCount}
        </Descriptions.Item>
      </Descriptions>
      <Table rowKey="runDate" size="small" pagination={false} columns={cols} dataSource={[data]} />
    </PageCard>
  );
}
