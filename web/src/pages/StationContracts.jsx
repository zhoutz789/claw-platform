import React, { useState } from 'react';
import { Card, Form, InputNumber, Button, message, Table, Tag, Empty, Select, Modal, Popconfirm } from 'antd';
import PageCard from '../components/PageCard';
import { useTranslation } from 'react-i18next';
import { listStationContracts, exitContract, refundContract, listPendingRefunds } from '../api/capacity';

// 状态 → antd Tag 颜色，覆盖合约 / 退款常见状态值。
const STATUS_COLOR = {
  ACTIVE: 'green',
  INACTIVE: 'default',
  EXPIRED: 'red',
  PENDING: 'gold',
  EXIT_REQUESTED: 'orange',
  REFUNDING: 'blue',
  REFUNDED: 'cyan',
  SETTLED: 'green',
};

const statusTag = (v) => <Tag color={STATUS_COLOR[v] || 'default'}>{v}</Tag>;

export default function StationContracts() {
  const { t } = useTranslation('common');
  const [stationId, setStationId] = useState(null);
  const [contracts, setContracts] = useState([]);
  const [pending, setPending] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadingPending, setLoadingPending] = useState(false);
  const [settle, setSettle] = useState(null);
  const [settleForm] = Form.useForm();

  const loadContracts = (id) => {
    if (!id) return;
    setLoading(true);
    listStationContracts(id)
      .then(setContracts)
      .catch((e) => message.error(e.message))
      .finally(() => setLoading(false));
  };
  const loadPending = () => {
    setLoadingPending(true);
    listPendingRefunds()
      .then(setPending)
      .catch((e) => message.error(e.message))
      .finally(() => setLoadingPending(false));
  };

  // 进入页面即拉取退款看板（与具体站点无关）。
  React.useEffect(() => {
    loadPending();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openSettle = (record) => {
    setSettle(record);
    settleForm.resetFields();
    settleForm.setFieldsValue({ refundAmount: record.depositAmount, settleType: 'FULL' });
  };
  const submitSettle = (v) => {
    if (!settle) return;
    const refunded = v.settleType === 'FULL';
    refundContract(settle.id, refunded, v.refundAmount)
      .then(() => {
        message.success(t('common:m997'));
        setSettle(null);
        loadContracts(stationId);
        loadPending();
      })
      .catch((e) => message.error(e.message));
  };
  const doExit = (record, remark) => {
    exitContract(record.id, remark)
      .then(() => {
        message.success(t('common:m997'));
        loadContracts(stationId);
        loadPending();
      })
      .catch((e) => message.error(e.message));
  };

  const columns = [
    { title: t('common:m1030'), dataIndex: 'contractNo', width: 140 },
    { title: t('common:m1031'), dataIndex: 'depositAmount', width: 110 },
    { title: t('common:m1032'), dataIndex: 'creditLimit', width: 110 },
    { title: t('common:m1033'), dataIndex: 'termYears', width: 80 },
    { title: t('common:m8'), dataIndex: 'status', width: 100, render: (v) => statusTag(v) },
    { title: t('common:m1034'), dataIndex: 'effectiveFrom', width: 110 },
    { title: t('common:m1035'), dataIndex: 'effectiveTo', width: 110 },
    { title: t('common:m1036'), dataIndex: 'refundStatus', width: 100, render: (v) => statusTag(v) },
    {
      title: t('common:m58'),
      key: 'actions',
      width: 180,
      render: (_, r) => (
        <span>
          <Popconfirm
            title={t('common:m1043')}
            onConfirm={() => {
              const remark = window.prompt(t('common:m1045'));
              doExit(r, remark || undefined);
            }}
          >
            <Button size="small" danger>
              {t('common:m1028')}
            </Button>
          </Popconfirm>
          <Button size="small" type="primary" style={{ marginLeft: 8 }} onClick={() => openSettle(r)}>
            {t('common:m1029')}
          </Button>
        </span>
      ),
    },
  ];

  const pendingColumns = [
    { title: t('common:m1030'), dataIndex: 'contractNo', width: 160 },
    { title: t('common:m8'), dataIndex: 'status', width: 110, render: (v) => statusTag(v) },
    { title: t('common:m1036'), dataIndex: 'refundStatus', width: 120 },
    { title: t('common:m1037'), dataIndex: 'refundDueAt', width: 180 },
  ];

  return (
    <PageCard title={t('common:m1026')} subtitle="服务站合约 / 退出 / 保证金清算">
      <Card size="small" style={{ marginBottom: 12 }}>
        <InputNumber
          placeholder={t('common:m503')}
          value={stationId}
          onChange={setStationId}
          style={{ width: 200, marginRight: 8 }}
        />
        <Button type="primary" onClick={() => loadContracts(stationId)}>
          {t('common:m1038')}
        </Button>
        <Button style={{ marginLeft: 8 }} onClick={loadPending}>
          {t('common:m248')}
        </Button>
      </Card>

      <Card
        size="small"
        title={t('common:m1026')}
        style={{ marginBottom: 12 }}
        extra={<Button onClick={() => loadContracts(stationId)}>{t('common:m248')}</Button>}
      >
        <Table
          rowKey="id"
          dataSource={contracts}
          loading={loading}
          pagination={false}
          size="small"
          columns={columns}
        />
        {!loading && contracts.length === 0 && <Empty description={t('common:m1039')} />}
      </Card>

      <Card size="small" title={t('common:m1027')} extra={<Button onClick={loadPending}>{t('common:m248')}</Button>}>
        <Table
          rowKey="id"
          dataSource={pending}
          loading={loadingPending}
          pagination={false}
          size="small"
          columns={pendingColumns}
        />
        {!loadingPending && pending.length === 0 && <Empty description={t('common:m1040')} />}
      </Card>

      <Modal
        title={t('common:m1029')}
        open={!!settle}
        onCancel={() => setSettle(null)}
        onOk={() => settleForm.submit()}
        destroyOnClose
      >
        <Form form={settleForm} layout="vertical" onFinish={submitSettle}>
          <Form.Item name="refundAmount" label={t('common:m1042')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} precision={2} />
          </Form.Item>
          <Form.Item name="settleType" label={t('common:m1046')} rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'FULL', label: t('common:m1047') },
                { value: 'PARTIAL', label: t('common:m1048') },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
