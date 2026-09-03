import { useState } from 'react';
import { Table, Tag, Button, Modal, Input, App } from 'antd';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';

const statusColor = { OPEN: 'red', RESOLVED: 'green', PROCESSING: 'orange' };

export default function Complaints() {  const { t } = useTranslation('common');

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
      message.warning(t('common:m931'));
      return;
    }
    setSubmitting(true);
    try {
      await api.post(`/v1/admin/complaints/${current.complaintNo}/resolve`, {
        resolution: resolution.trim(),
      });
      message.success(t('common:m932'));
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
    { title: t('common:m933'), dataIndex: 'complaintNo' },
    { title: t('common:m934'), dataIndex: 'userId' },
    { title: t('common:m935'), dataIndex: 'channel' },
    { title: t('common:m936'), dataIndex: 'subject' },
    {
      title: t('common:m8'),
      dataIndex: 'status',
      render: (v) => <Tag color={statusColor[v] || 'default'}>{v}</Tag>,
    },
    {
      title: t('common:m279'),
      dataIndex: 'createdAt',
      render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: t('common:m58'),
      key: 'act',
      render: (_, r) => (
        <Button size="small" type="primary" onClick={() => showResolve(r)}>{t('common:m937')}</Button>
      ),
    },
  ];

  return (
    <PageCard title={t('common:m938')} reload={reload} loading={loading}>
      <Table rowKey="complaintNo" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} locale={{ emptyText: '暂无待处理投诉' }} />
      <Modal
        title={`处理投诉 ${current?.complaintNo || ''}`}
        open={open}
        onOk={onResolve}
        confirmLoading={submitting}
        onCancel={() => setOpen(false)}
        okText={t('common:m939')}
        cancelText={t('common:m96')}
      >
        <Input.TextArea
          rows={4}
          placeholder={t('common:m931')}
          value={resolution}
          onChange={(e) => setResolution(e.target.value)}
        />
      </Modal>
    </PageCard>
  );
}
