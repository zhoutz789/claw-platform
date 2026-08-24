import { useState, useEffect } from 'react';
import { Table, Input, Button, Space, Tag, App, Alert } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import dayjs from 'dayjs';

const statusColor = { PENDING: 'orange', PAID: 'green', FORFEITED: 'red', RELEASED: 'blue' };

export default function Deposits() {
  const { message } = App.useApp();
  const [userId, setUserId] = useState('1001');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    if (!userId.trim()) {
      message.warning('请输入用户 ID');
      return;
    }
    setLoading(true);
    try {
      const d = await api.get(`/v1/deposits?userId=${encodeURIComponent(userId.trim())}`);
      setRows(d || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const cols = [
    { title: 'ID', dataIndex: 'id' },
    { title: '押金单号', dataIndex: 'depositNo' },
    { title: '用户', dataIndex: 'userId' },
    { title: '资产', dataIndex: 'assetId' },
    {
      title: '金额',
      dataIndex: 'amount',
      render: (v) => `$${Number(v || 0).toFixed(2)}`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v) => <Tag color={statusColor[v] || 'default'}>{v}</Tag>,
    },
    { title: '支付单号', dataIndex: 'payOrderNo' },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
  ];

  return (
    <PageCard
      title="押金管理"
      loading={loading}
      extra={
        <Space>
          <Input
            addonBefore="用户ID"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            onPressEnter={load}
            style={{ width: 180 }}
          />
          <Button type="primary" onClick={load}>
            查询
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="押金接口按 userId 查询。当前测试用户：顾客 1001~1004、资产主 1005/1006、站长 1004/1007。"
      />
      <Table rowKey="id" loading={loading} columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} locale={{ emptyText: '该用户暂无押金记录' }} />
    </PageCard>
  );
}
