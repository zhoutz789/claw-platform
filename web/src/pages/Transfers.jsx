import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Button, Descriptions, Form, InputNumber, Modal, Popconfirm, Select, Space, Table,
} from 'antd';
import { PlusOutlined, ReloadOutlined, QrcodeOutlined, EyeOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import {
  EnumTag, EMPTY, asArray, fmtTime, useSupplyOptions,
} from '../components/supplyShared';
import {
  createTransfer, getTransfer, handoverTransfer, listInventory, listTransfers, receiveTransfer,
} from '../api/supplyChain';
import { TRANSFER_STATUS_COLOR, TRANSFER_STATUS_LABEL } from '../enums';

/**
 * 调拨单页（增量 B · R5/B7）。
 *
 * 厂家发起站间调拨；源站扫码交接（占有权转出，设备在途）→ 目标站扫码收货
 * （建立新占有权，计入目标站寄售库存）。物流费由厂家承担。
 * 对接后端 AdminTransferController（/api/v1/admin/transfers）。
 */
export default function Transfers() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const { manufacturerOptions, stationOptions, manufacturerName, stationName } = useSupplyOptions();

  const [manufacturerId, setManufacturerId] = useState(undefined);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  // 新建弹窗
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  // 源站寄售在库设备，作为调拨设备下拉的数据源（真实接口）
  const [sourceDevices, setSourceDevices] = useState([]);

  // 详情弹窗
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listTransfers(manufacturerId);
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [manufacturerId, message, t]);

  useEffect(() => { load(); }, [load]);

  const fromStationId = Form.useWatch('fromStationId', form);

  // 源站选定后拉取该站的寄售在库设备，供调拨设备下拉选择（货权仍是厂家的寄售占有设备）。
  useEffect(() => {
    if (fromStationId == null) {
      setSourceDevices([]);
      return;
    }
    let alive = true;
    listInventory({ stationId: fromStationId, ownershipType: 'CONSIGNED' })
      .then((d) => { if (alive) setSourceDevices(asArray(d)); })
      .catch(() => { if (alive) setSourceDevices([]); });
    return () => { alive = false; };
  }, [fromStationId]);

  const deviceOptions = useMemo(
    () => sourceDevices.map((i) => ({
      label: `${i.serialNumber || t('supply:common.device')}#${i.deviceId ?? i.assetId ?? i.id}${i.currentStatus ? ` · ${i.currentStatus}` : ''}`,
      value: i.deviceId ?? i.assetId ?? i.id,
    })),
    [sourceDevices, t]
  );

  const openCreate = () => {
    setCreateOpen(true);
    form.resetFields();
    form.setFieldsValue({ manufacturerId, logisticsFee: 0 });
  };

  const submitCreate = async () => {
    const v = await form.validateFields();
    if (!v.deviceIds || !v.deviceIds.length) {
      message.warning(t('supply:transfers.msg.needDevice'));
      return;
    }
    if (v.fromStationId != null && v.fromStationId === v.toStationId) {
      message.warning(t('supply:transfers.msg.needDifferentStation'));
      return;
    }
    setSubmitting(true);
    try {
      await createTransfer({
        manufacturerId: v.manufacturerId,
        fromStationId: v.fromStationId,
        toStationId: v.toStationId,
        deviceIds: v.deviceIds.map((x) => Number(x)),
        logisticsFee: v.logisticsFee ?? 0,
      });
      message.success(t('supply:transfers.msg.created'));
      setCreateOpen(false);
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  const openDetail = async (id) => {
    setDetail(null);
    setDetailOpen(true);
    try {
      const d = await getTransfer(id);
      setDetail(d);
    } catch (e) {
      message.error(t('msg.detailLoadFailed', { msg: e.message }));
    }
  };

  /** 扫码推进：handover = 源站交接，receive = 目标站收货。 */
  const advance = async (record, action) => {
    try {
      const d = action === 'handover' ? await handoverTransfer(record.id) : await receiveTransfer(record.id);
      message.success(t(action === 'handover'
        ? 'supply:transfers.msg.handonvered'
        : 'supply:transfers.msg.received'));
      if (detail && detail.id === record.id) setDetail(d);
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    }
  };

  const columns = [
    { title: t('supply:transfers.col.id'), dataIndex: 'id', width: 80 },
    { title: t('supply:transfers.col.transferNo'), dataIndex: 'transferNo', width: 180 },
    {
      title: t('supply:transfers.col.manufacturerId'),
      dataIndex: 'manufacturerId',
      width: 150,
      render: (v) => manufacturerName(v),
    },
    {
      title: t('supply:transfers.col.fromStationId'),
      dataIndex: 'fromStationId',
      width: 160,
      render: (v) => stationName(v),
    },
    {
      title: t('supply:transfers.col.toStationId'),
      dataIndex: 'toStationId',
      width: 160,
      render: (v) => stationName(v),
    },
    {
      title: t('supply:transfers.col.status'),
      dataIndex: 'status',
      width: 110,
      render: (v) => <EnumTag value={v} labelMap={TRANSFER_STATUS_LABEL} colorMap={TRANSFER_STATUS_COLOR} />,
    },
    {
      title: t('supply:transfers.col.logisticsFee'),
      dataIndex: 'logisticsFee',
      width: 120,
      render: (v) => (v == null ? EMPTY : Number(v).toFixed(2)),
    },
    {
      title: t('supply:transfers.col.handoverAt'),
      dataIndex: 'handoverAt',
      width: 150,
      render: (v) => fmtTime(v),
    },
    {
      title: t('supply:transfers.col.receiveAt'),
      dataIndex: 'receiveAt',
      width: 150,
      render: (v) => fmtTime(v),
    },
    {
      title: t('supply:transfers.col.completedAt'),
      dataIndex: 'completedAt',
      width: 150,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 260,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => openDetail(r.id)}>
            {t('supply:transfers.detail')}
          </Button>
          {r.status === 'CREATED' && (
            <Perm code="station:transfer:handover">
              <Popconfirm
                title={t('supply:transfers.handover')}
                okText={t('action.ok')}
                cancelText={t('action.cancel')}
                onConfirm={() => advance(r, 'handover')}
              >
                <Button size="small" type="link" icon={<QrcodeOutlined />}>
                  {t('supply:transfers.handover')}
                </Button>
              </Popconfirm>
            </Perm>
          )}
          {r.status === 'IN_TRANSIT' && (
            <Perm code="station:transfer:receive">
              <Popconfirm
                title={t('supply:transfers.receive')}
                okText={t('action.ok')}
                cancelText={t('action.cancel')}
                onConfirm={() => advance(r, 'receive')}
              >
                <Button size="small" type="link" icon={<QrcodeOutlined />}>
                  {t('supply:transfers.receive')}
                </Button>
              </Popconfirm>
            </Perm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('supply:transfers.title')}
      subtitle={t('supply:transfers.subtitle')}
      extra={
        <Space>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('supply:common.selectManufacturer')}
            style={{ width: 200 }}
            options={manufacturerOptions}
            value={manufacturerId}
            onChange={setManufacturerId}
          />
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
          <Perm code="mfg:transfer:create">
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              {t('supply:transfers.create')}
            </Button>
          </Perm>
        </Space>
      }
    >
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      <Modal
        title={t('supply:transfers.create')}
        open={createOpen}
        onOk={submitCreate}
        confirmLoading={submitting}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="manufacturerId"
            label={t('supply:transfers.field.manufacturerId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:transfers.field.manufacturerId') }) }]}
          >
            <Select showSearch optionFilterProp="label" allowClear options={manufacturerOptions} />
          </Form.Item>
          <Form.Item
            name="fromStationId"
            label={t('supply:transfers.field.fromStationId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:transfers.field.fromStationId') }) }]}
          >
            <Select showSearch optionFilterProp="label" allowClear options={stationOptions} />
          </Form.Item>
          <Form.Item
            name="toStationId"
            label={t('supply:transfers.field.toStationId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:transfers.field.toStationId') }) }]}
          >
            <Select showSearch optionFilterProp="label" allowClear options={stationOptions} />
          </Form.Item>
          <Form.Item
            name="deviceIds"
            label={t('supply:transfers.field.deviceIds')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:transfers.field.deviceIds') }) }]}
          >
            <Select
              mode="multiple"
              allowClear
              showSearch
              optionFilterProp="label"
              tokenSeparators={[',', '，', ' ']}
              placeholder={t('supply:transfers.ph.deviceIds')}
              options={deviceOptions}
              notFoundContent={fromStationId == null ? t('supply:common.selectStation') : undefined}
            />
          </Form.Item>
          <Form.Item name="logisticsFee" label={t('supply:transfers.field.logisticsFee')}>
            <InputNumber min={0} precision={2} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('supply:transfers.detail')}
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        footer={null}
        width={640}
      >
        {detail ? (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('supply:transfers.col.transferNo')}>{detail.transferNo || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:transfers.col.status')}>{detail.status || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:transfers.col.manufacturerId')}>{manufacturerName(detail.manufacturerId)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:transfers.col.fromStationId')}>{stationName(detail.fromStationId)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:transfers.col.toStationId')}>{stationName(detail.toStationId)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:transfers.col.logisticsFee')}>
              {detail.logisticsFee == null ? EMPTY : Number(detail.logisticsFee).toFixed(2)}
            </Descriptions.Item>
            <Descriptions.Item label={t('supply:transfers.col.handoverAt')}>{fmtTime(detail.handoverAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:transfers.col.receiveAt')}>{fmtTime(detail.receiveAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:transfers.col.completedAt')}>{fmtTime(detail.completedAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:common.createdAt')}>{fmtTime(detail.createdAt)}</Descriptions.Item>
          </Descriptions>
        ) : (
          <div style={{ color: '#999' }}>{t('msg.loading')}</div>
        )}
      </Modal>
    </PageCard>
  );
}
