import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  App, Button, Descriptions, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, QrcodeOutlined, EyeOutlined, DeleteOutlined,
} from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EnumTag, EMPTY, fmtTime, useSupplyOptions } from '../components/supplyShared';
import {
  cancelFulfillmentOrder, confirmFulfillmentOrder, createFulfillmentOrder, expireFulfillmentOrder,
  getFulfillmentOrder, listFulfillmentOrders, payFulfillmentOrder, receiveFulfillmentOrder,
  shipFulfillmentOrder,
} from '../api/supplyChain';
import { FULFILLMENT_STATUS_COLOR, FULFILLMENT_STATUS_LABEL } from '../enums';

/**
 * 待履约订单页（增量 B · R6/B5）。
 *
 * 状态链：PENDING_PAYMENT →(付款冻结) PAID_FROZEN →(服务站确认选品) CONFIRMED
 *        →(厂家发货) SHIPPED →(服务站收货) RECEIVED →(取货扫码) PICKED_UP →(结算) SETTLED，
 * 任意节点可 CANCELLED / EXPIRED。取货扫码在独立页 /pickup-scan 完成。
 * 对接后端 AdminFulfillmentController（/api/v1/admin/fulfillment）。
 */
export default function FulfillmentOrders() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { manufacturerOptions, stationOptions, manufacturerName, stationName } = useSupplyOptions();

  const [stationId, setStationId] = useState(undefined);
  const [customerUserId, setCustomerUserId] = useState(undefined);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  // 新建订单弹窗
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  // 付款弹窗（paymentRef 可空）
  const [payOpen, setPayOpen] = useState(false);
  const [payRow, setPayRow] = useState(null);
  const [payForm] = Form.useForm();

  // 详情弹窗
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listFulfillmentOrders({ stationId, customerUserId });
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [stationId, customerUserId, message, t]);

  useEffect(() => { load(); }, [load]);

  const openCreate = () => {
    setCreateOpen(true);
    form.resetFields();
    form.setFieldsValue({
      remoteOrder: false,
      items: [{ qty: 1 }],
    });
  };

  const submitCreate = async () => {
    const v = await form.validateFields();
    const items = (v.items || []).map((i) => ({
      productId: i.productId ?? null,
      deviceId: i.deviceId ?? null,
      qty: i.qty ?? 1,
      price: i.price ?? null,
    }));
    if (!items.length) {
      message.warning(t('supply:fulfillment.msg.needItems'));
      return;
    }
    setSubmitting(true);
    try {
      await createFulfillmentOrder({
        customerUserId: v.customerUserId,
        manufacturerId: v.manufacturerId ?? null,
        stationId: v.stationId ?? null,
        remoteOrder: Boolean(v.remoteOrder),
        totalAmount: v.totalAmount ?? null,
        items,
      });
      message.success(t('supply:fulfillment.msg.created'));
      setCreateOpen(false);
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  const openPay = (record) => {
    setPayRow(record);
    setPayOpen(true);
    payForm.resetFields();
  };

  const submitPay = async () => {
    const v = await payForm.validateFields().catch(() => ({}));
    setSubmitting(true);
    try {
      const d = await payFulfillmentOrder(payRow.id, v.paymentRef);
      message.success(t('supply:fulfillment.msg.paid'));
      setPayOpen(false);
      if (detail && detail.id === d.id) setDetail(d);
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
      const d = await getFulfillmentOrder(id);
      setDetail(d);
    } catch (e) {
      message.error(t('msg.detailLoadFailed', { msg: e.message }));
    }
  };

  /**
   * 推进履约状态：confirm / ship / receive / cancel / expire。
   * @param {Object} record 订单行
   * @param {'confirm'|'ship'|'receive'|'cancel'|'expire'} action 动作
   */
  const advance = async (record, action) => {
    const runners = {
      confirm: () => confirmFulfillmentOrder(record.id),
      ship: () => shipFulfillmentOrder(record.id),
      receive: () => receiveFulfillmentOrder(record.id),
      cancel: () => cancelFulfillmentOrder(record.id),
      expire: () => expireFulfillmentOrder(record.id),
    };
    const successMsg = {
      confirm: 'supply:fulfillment.msg.confirmed',
      ship: 'supply:fulfillment.msg.shipped',
      receive: 'supply:fulfillment.msg.received',
      cancel: 'supply:fulfillment.msg.cancelled',
      expire: 'supply:fulfillment.msg.expired',
    };
    try {
      const d = await runners[action]();
      message.success(t(successMsg[action]));
      if (detail && detail.id === d.id) setDetail(d);
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    }
  };

  const columns = [
    { title: t('supply:fulfillment.col.id'), dataIndex: 'id', width: 80 },
    { title: t('supply:fulfillment.col.orderNo'), dataIndex: 'orderNo', width: 170 },
    { title: t('supply:fulfillment.col.customerUserId'), dataIndex: 'customerUserId', width: 100 },
    {
      title: t('supply:fulfillment.col.manufacturerId'),
      dataIndex: 'manufacturerId',
      width: 140,
      render: (v) => manufacturerName(v),
    },
    {
      title: t('supply:fulfillment.col.stationId'),
      dataIndex: 'stationId',
      width: 150,
      render: (v) => stationName(v),
    },
    {
      title: t('supply:fulfillment.col.remoteOrder'),
      dataIndex: 'remoteOrder',
      width: 110,
      render: (v) => (v ? t('action.yes') : t('action.no')),
    },
    {
      title: t('supply:fulfillment.col.status'),
      dataIndex: 'status',
      width: 140,
      render: (v) => <EnumTag value={v} labelMap={FULFILLMENT_STATUS_LABEL} colorMap={FULFILLMENT_STATUS_COLOR} />,
    },
    {
      title: t('supply:fulfillment.col.totalAmount'),
      dataIndex: 'totalAmount',
      width: 120,
      render: (v) => (v == null ? EMPTY : Number(v).toFixed(2)),
    },
    {
      title: t('supply:fulfillment.col.frozenAmount'),
      dataIndex: 'frozenAmount',
      width: 120,
      render: (v) => (v == null ? EMPTY : Number(v).toFixed(2)),
    },
    {
      title: t('supply:fulfillment.col.expireAt'),
      dataIndex: 'expireAt',
      width: 150,
      render: (v) => fmtTime(v),
    },
    {
      title: t('supply:fulfillment.col.paidAt'),
      dataIndex: 'paidAt',
      width: 150,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 340,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small" wrap>
          <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => openDetail(r.id)}>
            {t('action.detail')}
          </Button>
          {r.status === 'PENDING_PAYMENT' && (
            <Perm code="order:fulfill:pay">
              <Button size="small" type="link" onClick={() => openPay(r)}>{t('supply:fulfillment.pay')}</Button>
            </Perm>
          )}
          {r.status === 'PAID_FROZEN' && (
            <Perm code="order:fulfill:confirm">
              <Button size="small" type="link" onClick={() => advance(r, 'confirm')}>{t('supply:fulfillment.confirm')}</Button>
            </Perm>
          )}
          {r.status === 'CONFIRMED' && (
            <Perm code="mfg:fulfill:ship">
              <Button size="small" type="link" onClick={() => advance(r, 'ship')}>{t('supply:fulfillment.ship')}</Button>
            </Perm>
          )}
          {r.status === 'SHIPPED' && (
            <Perm code="station:fulfill:receive">
              <Button size="small" type="link" onClick={() => advance(r, 'receive')}>{t('supply:fulfillment.receive')}</Button>
            </Perm>
          )}
          {r.status === 'RECEIVED' && (
            <Perm any={['order:pickup:scan', 'station:pickup:confirm']}>
              <Button
                size="small"
                type="link"
                icon={<QrcodeOutlined />}
                onClick={() => navigate(`/pickup-scan?orderId=${r.id}`)}
              >
                {t('supply:fulfillment.pickup')}
              </Button>
            </Perm>
          )}
          {!['CANCELLED', 'EXPIRED', 'SETTLED'].includes(r.status) && (
            <>
              <Popconfirm
                title={t('supply:fulfillment.cancel')}
                okText={t('action.ok')}
                cancelText={t('action.cancel')}
                onConfirm={() => advance(r, 'cancel')}
              >
                <Button size="small" type="link" danger icon={<DeleteOutlined />}>{t('supply:fulfillment.cancel')}</Button>
              </Popconfirm>
              <Popconfirm
                title={t('supply:fulfillment.expire')}
                okText={t('action.ok')}
                cancelText={t('action.cancel')}
                onConfirm={() => advance(r, 'expire')}
              >
                <Button size="small" type="link" danger>{t('supply:fulfillment.expire')}</Button>
              </Popconfirm>
            </>
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('supply:fulfillment.title')}
      subtitle={t('supply:fulfillment.subtitle')}
      extra={
        <Space wrap>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('supply:common.selectStation')}
            style={{ width: 200 }}
            options={stationOptions}
            value={stationId}
            onChange={setStationId}
          />
          <InputNumber
            placeholder={t('supply:fulfillment.field.customerUserId')}
            style={{ width: 150 }}
            value={customerUserId}
            onChange={setCustomerUserId}
            min={1}
            precision={0}
          />
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
          <Perm code="order:fulfill:pay">
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              {t('supply:fulfillment.create')}
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

      {/* 新建待履约订单 */}
      <Modal
        title={t('supply:fulfillment.create')}
        open={createOpen}
        onOk={submitCreate}
        confirmLoading={submitting}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
        width={720}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="customerUserId"
            label={t('supply:fulfillment.field.customerUserId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:fulfillment.field.customerUserId') }) }]}
          >
            <InputNumber style={{ width: '100%' }} min={1} precision={0} />
          </Form.Item>
          <Form.Item name="manufacturerId" label={t('supply:fulfillment.field.manufacturerId')}>
            <Select showSearch optionFilterProp="label" allowClear options={manufacturerOptions} />
          </Form.Item>
          <Form.Item
            name="stationId"
            label={t('supply:fulfillment.field.stationId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:fulfillment.field.stationId') }) }]}
          >
            <Select showSearch optionFilterProp="label" allowClear options={stationOptions} />
          </Form.Item>
          <Form.Item name="remoteOrder" label={t('supply:fulfillment.field.remoteOrder')}>
            <Select options={[{ label: t('action.no'), value: false }, { label: t('action.yes'), value: true }]} />
          </Form.Item>
          <Form.Item name="totalAmount" label={t('supply:fulfillment.field.totalAmount')}>
            <InputNumber min={0} precision={2} style={{ width: '100%' }} />
          </Form.Item>

          <Form.List name="items">
            {(fields, { add, remove }) => (
              <>
                <div style={{ fontWeight: 600, marginBottom: 8 }}>{t('supply:fulfillment.field.items')}</div>
                {fields.map((f) => (
                  <Space key={f.key} align="baseline" style={{ display: 'flex', marginBottom: 8 }} wrap>
                    <Form.Item {...f} name={[f.name, 'productId']} label={t('supply:fulfillment.item.productId')}>
                      <InputNumber style={{ width: 120 }} min={1} precision={0} />
                    </Form.Item>
                    <Form.Item {...f} name={[f.name, 'deviceId']} label={t('supply:fulfillment.item.deviceId')}>
                      <InputNumber style={{ width: 120 }} min={1} precision={0} />
                    </Form.Item>
                    <Form.Item {...f} name={[f.name, 'qty']} label={t('supply:fulfillment.item.qty')}>
                      <InputNumber style={{ width: 90 }} min={1} precision={0} />
                    </Form.Item>
                    <Form.Item {...f} name={[f.name, 'price']} label={t('supply:fulfillment.item.price')}>
                      <InputNumber style={{ width: 130 }} min={0} precision={2} />
                    </Form.Item>
                    <Button type="link" danger onClick={() => remove(f.name)}>{t('action.delete')}</Button>
                  </Space>
                ))}
                <Button type="dashed" block onClick={() => add({ qty: 1 })} icon={<PlusOutlined />}>
                  {t('action.add')}
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>

      {/* 付款冻结 */}
      <Modal
        title={t('supply:fulfillment.pay')}
        open={payOpen}
        onOk={submitPay}
        confirmLoading={submitting}
        onCancel={() => setPayOpen(false)}
        destroyOnClose
        width={480}
      >
        <Form form={payForm} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="paymentRef" label={t('supply:fulfillment.field.paymentRef')}>
            <Input placeholder={t('supply:fulfillment.ph.paymentRef')} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 订单详情（含各节点时间，便于责任倒查） */}
      <Modal
        title={t('table.detailTitle')}
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        footer={null}
        width={640}
      >
        {detail ? (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('supply:fulfillment.col.orderNo')}>{detail.orderNo || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.status')}>{detail.status || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.customerUserId')}>{detail.customerUserId ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.manufacturerId')}>{manufacturerName(detail.manufacturerId)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.stationId')}>{stationName(detail.stationId)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.remoteOrder')}>
              {detail.remoteOrder ? t('action.yes') : t('action.no')}
            </Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.totalAmount')}>
              {detail.totalAmount == null ? EMPTY : Number(detail.totalAmount).toFixed(2)}
            </Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.frozenAmount')}>
              {detail.frozenAmount == null ? EMPTY : Number(detail.frozenAmount).toFixed(2)}
            </Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.paymentRef')}>{detail.paymentRef || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.expireAt')}>{fmtTime(detail.expireAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.paidAt')}>{fmtTime(detail.paidAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.confirmedAt')}>{fmtTime(detail.confirmedAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.shippedAt')}>{fmtTime(detail.shippedAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.receivedAt')}>{fmtTime(detail.receivedAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.pickedUpAt')}>{fmtTime(detail.pickedUpAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.settledAt')}>{fmtTime(detail.settledAt)}</Descriptions.Item>
          </Descriptions>
        ) : (
          <div style={{ color: '#999' }}>{t('msg.loading')}</div>
        )}
      </Modal>
    </PageCard>
  );
}
