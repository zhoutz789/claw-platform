import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Form, Input, InputNumber, Modal, Select, Space, Table, Tag,
} from 'antd';
import { ReloadOutlined, ImportOutlined, EditOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EnumTag, EMPTY, fmtTime, useSupplyOptions } from '../components/supplyShared';
import { ScopeBanner } from '../components/inventoryShared';
import {
  listStationInventory, listStationMovements, getStationScope,
  inboundStationInventory, adjustStationInventory,
} from '../api/station';

/**
 * 服务站库存层页面（模块四 · ①）。
 *
 * 数据范围：经 StationScopeService 解析，服务站只看自身、厂家看下属站、平台看全量（BC-5）。
 * 入站 / 调整写操作由 <Perm> 门控（station:inventory:inbound / adjust），且后端再次越权校验。
 * 空数据 / 403 均不白屏：scope 为空显示引导，列表为空显示占位。
 */
export default function StationInventory() {
  const { t } = useTranslation(['common', 'station']);
  const { message } = App.useApp();
  const { stationOptions, stationName } = useSupplyOptions();

  const [scope, setScope] = useState(null);
  const [stationId, setStationId] = useState(undefined);
  const [rows, setRows] = useState([]);
  const [movements, setMovements] = useState([]);
  const [loading, setLoading] = useState(false);

  const [inboundOpen, setInboundOpen] = useState(false);
  const [adjustOpen, setAdjustOpen] = useState(false);
  const [inboundForm] = Form.useForm();
  const [adjustForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  // 作用域视图：决定可选服务站集合与默认站。
  useEffect(() => {
    getStationScope()
      .then((v) => {
        setScope(v);
        if (v && Array.isArray(v.allowedStationIds) && v.allowedStationIds.length === 1) {
          setStationId(v.allowedStationIds[0]);
        }
      })
      .catch(() => setScope(null));
  }, []);

  const effectiveStations = useMemo(() => {
    if (!scope || !Array.isArray(scope.allowedStationIds)) return stationOptions;
    const set = new Set(scope.allowedStationIds);
    return stationOptions.filter((o) => set.has(o.value));
  }, [scope, stationOptions]);

  const bannerScope = useMemo(() => (scope ? {
    scopeLevel: scope.level,
    principalId: scope.overrideStationId,
    subordinateStationIds: scope.allowedStationIds || [],
  } : null), [scope]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [stock, mv] = await Promise.all([
        listStationInventory({ stationId }),
        listStationMovements({ stationId }),
      ]);
      setRows(Array.isArray(stock) ? stock : []);
      setMovements(Array.isArray(mv) ? mv : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
      setMovements([]);
    } finally {
      setLoading(false);
    }
  }, [stationId, message, t]);

  useEffect(() => { load(); }, [load]);

  const stockColumns = [
    { title: t('station:inventory.col.id'), dataIndex: 'id', width: 90 },
    {
      title: t('station:inventory.col.stationId'),
      dataIndex: 'stationId',
      width: 160,
      render: (v) => (v == null ? EMPTY : stationName(v)),
    },
    { title: t('station:inventory.col.skuCode'), dataIndex: 'skuCode', width: 160, render: (v) => v || EMPTY },
    {
      title: t('station:inventory.col.stockQty'),
      dataIndex: 'stockQty',
      width: 120,
      render: (v) => (v == null ? EMPTY : v),
    },
    {
      title: t('station:inventory.col.updatedAt'),
      dataIndex: 'updatedAt',
      width: 170,
      render: (v) => fmtTime(v),
    },
  ];

  const movementColumns = [
    { title: t('station:inventory.col.id'), dataIndex: 'id', width: 90 },
    {
      title: t('station:inventory.col.stationId'),
      dataIndex: 'stationId',
      width: 160,
      render: (v) => (v == null ? EMPTY : stationName(v)),
    },
    { title: t('station:inventory.col.skuCode'), dataIndex: 'skuCode', width: 140, render: (v) => v || EMPTY },
    {
      title: t('station:inventory.col.deltaQty'),
      dataIndex: 'deltaQty',
      width: 110,
      render: (v) => (v == null ? EMPTY : <Tag color={v >= 0 ? 'green' : 'red'}>{v}</Tag>),
    },
    {
      title: t('station:inventory.col.reason'),
      dataIndex: 'reason',
      width: 140,
      render: (v) => v || EMPTY,
    },
    {
      title: t('station:inventory.col.createdAt'),
      dataIndex: 'createdAt',
      width: 170,
      render: (v) => fmtTime(v),
    },
  ];

  const submitInbound = async () => {
    const values = await inboundForm.validateFields();
    setSubmitting(true);
    try {
      await inboundStationInventory({
        stationId: Number(stationId),
        skuCode: values.skuCode,
        qty: Number(values.qty),
      });
      message.success(t('msg.success'));
      setInboundOpen(false);
      inboundForm.resetFields();
      load();
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const submitAdjust = async () => {
    const values = await adjustForm.validateFields();
    setSubmitting(true);
    try {
      await adjustStationInventory({
        stationId: Number(stationId),
        skuCode: values.skuCode,
        deltaQty: Number(values.deltaQty),
        reason: values.reason || null,
      });
      message.success(t('msg.success'));
      setAdjustOpen(false);
      adjustForm.resetFields();
      load();
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const canWrite = stationId != null;

  return (
    <PageCard
      title={t('station:inventory.title')}
      subtitle={t('station:inventory.subtitle')}
      extra={
        <Space>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('station:inventory.selectStation')}
            style={{ width: 220 }}
            options={effectiveStations}
            value={stationId}
            onChange={setStationId}
          />
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
        </Space>
      }
    >
      <ScopeBanner scope={bannerScope} stationName={stationName} />

      {effectiveStations.length === 0 && (
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('station:inventory.noStation')} />
      )}

      <Perm code="station:inventory:inbound">
        <Button
          type="primary"
          icon={<ImportOutlined />}
          disabled={!canWrite}
          style={{ marginRight: 8, marginBottom: 12 }}
          onClick={() => { inboundForm.setFieldsValue({ stationId }); setInboundOpen(true); }}
        >
          {t('station:inventory.inbound')}
        </Button>
      </Perm>
      <Perm code="station:inventory:adjust">
        <Button
          icon={<EditOutlined />}
          disabled={!canWrite}
          style={{ marginBottom: 12 }}
          onClick={() => { adjustForm.setFieldsValue({ stationId }); setAdjustOpen(true); }}
        >
          {t('station:inventory.adjust')}
        </Button>
      </Perm>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={stockColumns}
        size="middle"
        locale={{ emptyText: t('station:inventory.empty') }}
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      <div style={{ marginTop: 24 }}>
        <h4>{t('station:inventory.movements')}</h4>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={movements}
          columns={movementColumns}
          size="middle"
          locale={{ emptyText: t('station:inventory.empty') }}
          scroll={{ x: 'max-content' }}
          pagination={{ pageSize: 10, showSizeChanger: true }}
        />
      </div>

      <Modal
        title={t('station:inventory.inboundTitle')}
        open={inboundOpen}
        onOk={submitInbound}
        confirmLoading={submitting}
        onCancel={() => setInboundOpen(false)}
        destroyOnClose
      >
        <Form form={inboundForm} layout="vertical">
          <Form.Item label={t('station:inventory.col.stationId')}>
            <span>{stationName(stationId)} (#{stationId})</span>
          </Form.Item>
          <Form.Item name="skuCode" label={t('station:inventory.skuCode')} rules={[{ required: true }]}>
            <Input placeholder="e.g. BYD-C1-2024" />
          </Form.Item>
          <Form.Item name="qty" label={t('station:inventory.qty')} rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('station:inventory.adjustTitle')}
        open={adjustOpen}
        onOk={submitAdjust}
        confirmLoading={submitting}
        onCancel={() => setAdjustOpen(false)}
        destroyOnClose
      >
        <Form form={adjustForm} layout="vertical">
          <Form.Item label={t('station:inventory.col.stationId')}>
            <span>{stationName(stationId)} (#{stationId})</span>
          </Form.Item>
          <Form.Item name="skuCode" label={t('station:inventory.skuCode')} rules={[{ required: true }]}>
            <Input placeholder="e.g. BYD-C1-2024" />
          </Form.Item>
          <Form.Item name="deltaQty" label={t('station:inventory.deltaQty')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reason" label={t('station:inventory.reason')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
