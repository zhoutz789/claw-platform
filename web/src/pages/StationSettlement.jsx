import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Alert, App, Button, DatePicker, Drawer, Descriptions, Form, Modal, Select, Space, Table, Tag,
} from 'antd';
import { ReloadOutlined, PlusOutlined, EyeOutlined, CheckOutlined, DollarOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EMPTY, fmtTime, useSupplyOptions } from '../components/supplyShared';
import { ScopeBanner } from '../components/inventoryShared';
import {
  listStationSettlements, generateStationSettlement, confirmStationSettlement,
  payStationSettlement, getStationSettlement, getStationScope,
} from '../api/station';

const STATUS_LABEL = {
  DRAFT: { key: 'station:settlement.statusDraft', color: 'default' },
  CONFIRMED: { key: 'station:settlement.statusConfirmed', color: 'blue' },
  PAID: { key: 'station:settlement.statusPaid', color: 'green' },
};

/**
 * 服务站结算层页面（模块四 · ③）。
 *
 * 结算单由平台管理员生成/确认/支付；服务站仅查自身、可发起草稿（D7）。
 * 三金额（物流费 / 服务站提成 / 厂家净额）由后端独立计算，前端只读展示。
 * 写操作由 <Perm> 门控（station:settlement:manage / view）。
 */
export default function StationSettlement() {
  const { t } = useTranslation(['common', 'station']);
  const { message } = App.useApp();
  const { stationOptions, stationName } = useSupplyOptions();

  const [scope, setScope] = useState(null);
  const [stationId, setStationId] = useState(undefined);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  const [genOpen, setGenOpen] = useState(false);
  const [genForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  const [detail, setDetail] = useState(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);

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
      const data = await listStationSettlements({ stationId });
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [stationId, message, t]);

  useEffect(() => { load(); }, [load]);

  const openDetail = async (id) => {
    setDetailOpen(true);
    setDetailLoading(true);
    setDetail(null);
    try {
      const d = await getStationSettlement(id);
      setDetail(d);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
    } finally {
      setDetailLoading(false);
    }
  };

  const doConfirm = async (id) => {
    try {
      await confirmStationSettlement(id);
      message.success(t('msg.success'));
      load();
      if (detail && detail.settlement.id === id) openDetail(id);
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    }
  };

  const doPay = async (id) => {
    try {
      await payStationSettlement(id);
      message.success(t('msg.success'));
      load();
      if (detail && detail.settlement.id === id) openDetail(id);
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    }
  };

  const submitGen = async () => {
    const values = await genForm.validateFields();
    setSubmitting(true);
    try {
      const [start, end] = values.period;
      await generateStationSettlement({
        stationId: Number(values.stationId),
        periodStart: start.toISOString(),
        periodEnd: end.toISOString(),
      });
      message.success(t('msg.success'));
      setGenOpen(false);
      genForm.resetFields();
      load();
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    { title: t('station:settlement.col.settlementNo'), dataIndex: 'settlementNo', width: 180 },
    {
      title: t('station:settlement.col.stationId'),
      dataIndex: 'stationId',
      width: 160,
      render: (v) => (v == null ? EMPTY : stationName(v)),
    },
    {
      title: t('station:settlement.col.periodStart'),
      dataIndex: 'periodStart',
      width: 150,
      render: (v) => fmtTime(v, 'YYYY-MM-DD'),
    },
    {
      title: t('station:settlement.col.periodEnd'),
      dataIndex: 'periodEnd',
      width: 150,
      render: (v) => fmtTime(v, 'YYYY-MM-DD'),
    },
    {
      title: t('station:settlement.col.status'),
      dataIndex: 'status',
      width: 110,
      render: (v) => {
        const cfg = STATUS_LABEL[v] || { key: v, color: 'default' };
        return <Tag color={cfg.color}>{t(cfg.key)}</Tag>;
      },
    },
    {
      title: t('station:settlement.col.logisticsFee'),
      dataIndex: 'logisticsFee',
      width: 120,
      render: (v) => (v == null ? EMPTY : v),
    },
    {
      title: t('station:settlement.col.stationCommission'),
      dataIndex: 'stationCommission',
      width: 130,
      render: (v) => (v == null ? EMPTY : v),
    },
    {
      title: t('station:settlement.col.manufacturerNet'),
      dataIndex: 'manufacturerNet',
      width: 130,
      render: (v) => (v == null ? EMPTY : v),
    },
    { title: t('station:settlement.col.currency'), dataIndex: 'currency', width: 80, render: (v) => v || EMPTY },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 240,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => openDetail(r.id)}>
            {t('station:settlement.detail')}
          </Button>
          <Perm code="station:settlement:manage">
            {r.status === 'DRAFT' && (
              <Button size="small" type="link" icon={<CheckOutlined />} onClick={() => doConfirm(r.id)}>
                {t('station:settlement.confirm')}
              </Button>
            )}
            {r.status === 'CONFIRMED' && (
              <Button size="small" type="link" icon={<DollarOutlined />} onClick={() => doPay(r.id)}>
                {t('station:settlement.pay')}
              </Button>
            )}
          </Perm>
        </Space>
      ),
    },
  ];

  const itemColumns = [
    {
      title: t('station:settlement.items.itemType'),
      dataIndex: 'itemType',
      width: 130,
      render: (v) => (v ? t(`station:settlement.itemType.${v}`, { defaultValue: v }) : EMPTY),
    },
    { title: t('station:settlement.items.description'), dataIndex: 'description', width: 220, render: (v) => v || EMPTY },
    {
      title: t('station:settlement.items.amount'),
      dataIndex: 'amount',
      width: 120,
      render: (v) => (v == null ? EMPTY : v),
    },
    {
      title: t('station:settlement.items.direction'),
      dataIndex: 'direction',
      width: 120,
      render: (v) => {
        if (!v) return EMPTY;
        const color = v === 'DEBIT' ? 'red' : 'green';
        return <Tag color={color}>{t(`station:settlement.direction.${v}`, { defaultValue: v })}</Tag>;
      },
    },
  ];

  return (
    <PageCard
      title={t('station:settlement.title')}
      subtitle={t('station:settlement.subtitle')}
      extra={
        <Space>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('station:settlement.selectStation')}
            style={{ width: 220 }}
            options={effectiveStations}
            value={stationId}
            onChange={setStationId}
          />
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
          <Perm any={['station:settlement:manage', 'station:settlement:view']}>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              disabled={stationId == null}
              onClick={() => { genForm.setFieldsValue({ stationId }); setGenOpen(true); }}
            >
              {t('station:settlement.generate')}
            </Button>
          </Perm>
        </Space>
      }
    >
      <ScopeBanner scope={bannerScope} stationName={stationName} />

      {effectiveStations.length === 0 && (
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('station:inventory.noStation')} />
      )}

      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        locale={{ emptyText: t('station:settlement.empty') }}
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      {/* 生成草稿 */}
      <Modal
        title={t('station:settlement.generateTitle')}
        open={genOpen}
        onOk={submitGen}
        confirmLoading={submitting}
        onCancel={() => setGenOpen(false)}
        destroyOnClose
      >
        <Form form={genForm} layout="vertical">
          <Form.Item name="stationId" label={t('station:settlement.selectStation')} rules={[{ required: true }]}>
            <Select options={effectiveStations} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="period" label={`${t('station:settlement.periodStart')} ~ ${t('station:settlement.periodEnd')}`} rules={[{ required: true }]}>
            <DatePicker.RangePicker style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 明细抽屉 */}
      <Drawer
        title={t('station:settlement.detailTitle')}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        width={640}
      >
        {detailLoading ? (
          <div style={{ color: '#999' }}>{t('msg.loading')}</div>
        ) : detail ? (
          <>
            <Descriptions bordered size="small" column={1} style={{ marginBottom: 16 }}>
              <Descriptions.Item label={t('station:settlement.col.settlementNo')}>
                {detail.settlement.settlementNo || EMPTY}
              </Descriptions.Item>
              <Descriptions.Item label={t('station:settlement.col.stationId')}>
                {stationName(detail.settlement.stationId)}
              </Descriptions.Item>
              <Descriptions.Item label={t('station:settlement.col.periodStart')}>
                {fmtTime(detail.settlement.periodStart, 'YYYY-MM-DD')}
              </Descriptions.Item>
              <Descriptions.Item label={t('station:settlement.col.periodEnd')}>
                {fmtTime(detail.settlement.periodEnd, 'YYYY-MM-DD')}
              </Descriptions.Item>
              <Descriptions.Item label={t('station:settlement.col.status')}>
                {(() => {
                  const cfg = STATUS_LABEL[detail.settlement.status] || { key: detail.settlement.status, color: 'default' };
                  return <Tag color={cfg.color}>{t(cfg.key)}</Tag>;
                })()}
              </Descriptions.Item>
              <Descriptions.Item label={t('station:settlement.logisticsFee')}>
                {detail.settlement.logisticsFee ?? EMPTY} {detail.settlement.currency || ''}
              </Descriptions.Item>
              <Descriptions.Item label={t('station:settlement.stationCommission')}>
                {detail.settlement.stationCommission ?? EMPTY} {detail.settlement.currency || ''}
              </Descriptions.Item>
              <Descriptions.Item label={t('station:settlement.manufacturerNet')}>
                {detail.settlement.manufacturerNet ?? EMPTY} {detail.settlement.currency || ''}
              </Descriptions.Item>
            </Descriptions>

            <h4>{t('station:settlement.items.title')}</h4>
            <Table
              rowKey="id"
              dataSource={detail.items || []}
              columns={itemColumns}
              size="small"
              pagination={false}
              locale={{ emptyText: t('station:inventory.empty') }}
            />
          </>
        ) : null}
      </Drawer>
    </PageCard>
  );
}
