import React, { useState } from 'react';
import { Tag, Modal, Form, Input, InputNumber, Button, message } from 'antd';
import CrudTable from '../components/CrudTable';
import api from '../api';
import { DISPUTE_TYPE } from '../enums';
import { useTranslation } from 'react-i18next';

const columns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '转移ID', dataIndex: 'transferId', width: 90 },
  { title: '资产ID', dataIndex: 'assetId', width: 90 },
  { title: '申诉方', dataIndex: 'claimantId', width: 90 },
  { title: '被诉方', dataIndex: 'respondentId', width: 90 },
  { title: '争议类型', dataIndex: 'disputeType', width: 130 },
  { title: '描述', dataIndex: 'description', ellipsis: true },
  { title: '索赔额', dataIndex: 'claimAmount', width: 100, render: (v) => (v == null ? '-' : `$${v}`) },
  {
    title: '状态', dataIndex: 'status', width: 100,
    render: (v) => <Tag color={v === 'RESOLVED' ? 'green' : 'orange'}>{v || '-'}</Tag>,
  },
];

const fields = [
  { name: 'transferId', label: '关联转移ID', type: 'number', required: true },
  { name: 'assetId', label: '资产ID', type: 'number', required: true },
  { name: 'claimantId', label: '申诉方用户ID', type: 'number', required: true },
  { name: 'respondentId', label: '被诉方用户ID', type: 'number', required: true },
  { name: 'disputeType', label: '争议类型', type: 'select', options: DISPUTE_TYPE, required: true },
  { name: 'description', label: '争议描述', type: 'textarea', required: true },
  { name: 'evidenceUrls', label: '证据URL', placeholder: '逗号分隔' },
  { name: 'claimAmount', label: '索赔金额', type: 'number', precision: 2 },
];

export default function Arbitration() {  const { t } = useTranslation('common');

  const [arbitOpen, setArbitOpen] = useState(false);
  const [target, setTarget] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const openArbitrate = (r) => {
    setTarget(r);
    form.resetFields();
    setArbitOpen(true);
  };

  const submitArbitrate = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      await api.post(`/v1/admin/custody/disputes/${target.id}/arbitrate`, v);
      message.success(t('common:m925'));
      setArbitOpen(false);
    } catch (e) { message.error(`仲裁失败：${e.message}`); }
    finally { setSubmitting(false); }
  };

  const extraRowActions = (r) => (
    <Button size="small" type="link" onClick={() => openArbitrate(r)}>{t('common:m926')}</Button>
  );

  return (
    <>
      <CrudTable
        title={t('common:m927')}
        subtitle="产权争议登记与仲裁裁决"
        endpoint="/v1/admin/custody/disputes"
        columns={columns}
        fields={fields}
        rowKey="id"
        extraRowActions={extraRowActions}
      />
      <Modal title={`仲裁裁决 · #${target ? target.id : ''}`} open={arbitOpen} onOk={submitArbitrate} confirmLoading={submitting} onCancel={() => setArbitOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="arbitratorId" label={t('common:m928')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="awardedAmount" label={t('common:m929')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="resolution" label={t('common:m930')} rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>
    </>
  );
}
