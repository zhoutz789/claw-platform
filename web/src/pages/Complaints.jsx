import { useState } from 'react';
import { Table, Tag, Button, Modal, Input, App } from 'antd';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';
import dayjs from 'dayjs';

const statusColor = { OPEN: 'red', RESOLVED: 'green', PROCESSING: 'orange' };

export default function Complaints() {
  const { message } = App.useApp();
  const { data, loading, reload } = useFetch(() => api.get('/v1/admin/complaints?status=OPEN'));
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState(null);
  const [resolution, setResolution] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const showResolve = (row) => {
    setCurrent(row);
    setResolution('');
    setOpen(true);
  };

  const onResolve = async () => {
    if (!resolution.trim()) {
      message.warning('请填写处理结论');
      return;
    }
    setSubmitting(true);
    try {
      await api.post(`/v1/admin/complaints/${current.complaintNo}/resolve`, {
        resolution: resolution.trim(),
      });
      message.success('投诉已处理');
      setOpen(false);
      reload();
    } catch (e) {
      message.error(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const rows = data || [];
  const cols = [
    { title: '投诉单号', dataIndex: 'complaintNo' },
    { title: '用户', dataIndex: 'userId' },
    { title: '渠道', dataIndex: 'channel' },
    { title: '主题', dataIndex: 'subject' },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v) => <Tag color={statusColor[v] || 'default'}>{v}</Tag>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'act',
      render: (_, r) => (
        <Button size="small" type="primary" onClick={() => showResolve(r)}>
          处理
        </Button>
      ),
    },
  ];

  return (
    <PageCard title="投诉处理（待处理）" reload={reload} loading={loading}>
      <Table rowKey="complaintNo" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} locale={{ emptyText: '暂无待处理投诉' }} />
      <Modal
        title={`处理投诉 ${current?.complaintNo || ''}`}
        open={open}
        onOk={onResolve}
        confirmLoading={submitting}
        onCancel={() => setOpen(false)}
        okText="提交处理"
        cancelText="取消"
      >
        <Input.TextArea
          rows={4}
          placeholder="请填写处理结论"
          value={resolution}
          onChange={(e) => setResolution(e.target.value)}
        />
      </Modal>
    </PageCard>
  );
}
