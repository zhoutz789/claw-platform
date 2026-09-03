import React, { useEffect, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Card, Form, Input, Button, Table, Tag, message, Space, Alert, Select, Modal, Progress,
  Typography, Popconfirm, Divider,
} from 'antd';
import PageCard from '../components/PageCard';
import {
  listCustomerOrders, getRegistrationProgress, registerUnits,
  deleteRegistration, shipOrder,
} from '../api/order';
import { useTranslation } from 'react-i18next';

const { Text, Paragraph, Title } = Typography;

// 主部件类型（设计 §9.7 枚举）
const COMPONENT_TYPES = [
  'BATTERY', 'CONTROLLER', 'CHARGER', 'REMOTE', 'MOTOR', 'CAMERA', 'CELL',
].map((v) => ({ label: v, value: v }));

const STATUS_COLOR = {
  CREATED: 'default', PAID: 'blue', CERTIFICATED: 'cyan', SHIPPED: 'green',
  COMPLETED: 'success', CANCELLED: 'red', REFUNDED: 'volcano',
};

// 发货前逐台登记面板（接真实后端，替换原 mock）。
export default function OrderManage() {  const { t } = useTranslation('common');

  const [searchParams, setSearchParams] = useSearchParams();
  const orderId = searchParams.get('orderId');

  const [orders, setOrders] = useState([]);
  const [order, setOrder] = useState(null);
  const [progress, setProgress] = useState(null);
  const [loading, setLoading] = useState(false);
  const [regOpen, setRegOpen] = useState(false);
  const [regItem, setRegItem] = useState(null);
  const [regForm] = Form.useForm();
  const [shipping, setShipping] = useState(false);

  // 订单下拉
  const loadOrders = useCallback(() => {
    listCustomerOrders().then((data) => setOrders(Array.isArray(data) ? data : [])).catch(() => setOrders([]));
  }, []);

  const loadProgress = useCallback((id) => {
    if (!id) { setOrder(null); setProgress(null); return; }
    setLoading(true);
    Promise.all([
      listCustomerOrders().then((d) => Array.isArray(d) ? d : []),
      getRegistrationProgress(id),
    ]).then(([all, prog]) => {
      setOrders(all);
      setOrder(all.find((o) => String(o.id) === String(id)) || null);
      setProgress(prog);
    }).catch((e) => message.error(`加载失败：${e.message}`))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { loadOrders(); }, [loadOrders]);
  useEffect(() => { loadProgress(orderId); }, [orderId, loadProgress]);

  const onPickOrder = (id) => {
    if (id) setSearchParams({ orderId: id });
    else { setSearchParams({}); }
  };

  const openReg = (item) => {
    setRegItem(item);
    regForm.setFieldsValue({ units: [{ components: [{ type: 'BATTERY', no: '' }] }] });
    setRegOpen(true);
  };

  const submitReg = async () => {
    const vals = await regForm.validateFields();
    const units = (vals.units || []).map((u) => ({
      qrCode: u.qrCode,
      vin: u.vin,
      frameNo: u.frameNo,
      motorNo: u.motorNo,
      serialNumber: u.serialNumber,
      model: u.model,
      componentNosJson: JSON.stringify(u.components || []),
    }));
    if (!units.length) { message.warning(t('common:m850')); return; }
    try {
      await registerUnits(orderId, regItem.id, { units });
      message.success(`已登记 ${units.length} 台设备`);
      setRegOpen(false);
      loadProgress(orderId);
    } catch (e) {
      message.error(`登记失败：${e.message}`);
    }
  };

  const onDelete = async (regId) => {
    try {
      await deleteRegistration(orderId, regId);
      message.success(t('common:m851'));
      loadProgress(orderId);
    } catch (e) { message.error(`纠错失败：${e.message}`); }
  };

  const onShip = async () => {
    setShipping(true);
    try {
      await shipOrder(orderId);
      message.success(t('common:m852'));
      loadProgress(orderId);
    } catch (e) { message.error(`发货失败：${e.message}`); }
    finally { setShipping(false); }
  };

  const itemColumns = [
    { title: 'SKU', dataIndex: 'skuId', width: 90 },
    { title: t('common:m36'), dataIndex: 'assetType', width: 100, render: (v) => <Tag>{v}</Tag> },
    {
      title: t('common:m853'), width: 240,
      render: (_, it) => {
        const p = progress?.items?.find((x) => String(x.orderItemId) === String(it.orderItemId));
        const reg = p ? p.registered : 0;
        const total = p ? p.quantity : it.quantity;
        const percent = total ? Math.round((reg / total) * 100) : 0;
        return (
          <Space>
            <Progress percent={percent} size="small" style={{ width: 120 }} />
            <Text>{reg}/{total}</Text>
          </Space>
        );
      },
    },
    {
      title: t('common:m58'), width: 120,
      render: (_, it) => (
        <Button size="small" type="primary" onClick={() => openReg({ id: it.orderItemId })}>{t('common:m854')}</Button>
      ),
    },
  ];

  const regColumns = [
    { title: '#', dataIndex: 'seq', width: 50 },
    { title: t('common:m213'), dataIndex: 'assetId', width: 90 },
    { title: t('common:m183'), dataIndex: 'qrCode', width: 160, ellipsis: true },
    { title: t('common:m855'), dataIndex: 'vin', width: 120 },
    { title: t('common:m856'), dataIndex: 'motorNo', width: 120 },
    { title: t('common:m8'), dataIndex: 'status', width: 110, render: (v) => <Tag color="blue">{v}</Tag> },
    {
      title: t('common:m58'), width: 90,
      render: (_, r) => (
        <Popconfirm title={t('common:m857')} onConfirm={() => onDelete(r.id)}>
          <Button size="small" danger>{t('common:m858')}</Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <PageCard
      title={t('common:m859')}
      extra={(
        <Select
          showSearch
          placeholder={t('common:m860')}
          style={{ width: 320 }}
          value={orderId ? Number(orderId) : undefined}
          onChange={onPickOrder}
          options={orders.map((o) => ({ label: `${o.orderNo}（${o.status}）`, value: o.id }))}
          filterOption={(input, opt) => String(opt.label).toLowerCase().includes(input.toLowerCase())}
        />
      )}
      loading={loading}
      reload={() => loadProgress(orderId)}
    >
      {!orderId && (
        <Alert type="info" showIcon message="请先在右上角选择一笔客户订单，进入发货前逐台登记。" />
      )}

      {order && (
        <>
          <Alert
            type={progress?.canShip ? 'success' : 'warning'} showIcon
            message={progress?.canShip
              ? '全部 SKU 已登记齐全，可发货。'
              : '尚有 SKU 登记未齐全，须逐台登记完成后方可发货。'}
            style={{ marginBottom: 14 }}
          />
          <Paragraph>
            <Text strong>{t('common:m861')}</Text>{order.orderNo}　
            <Text strong>{t('common:m862')}</Text><Tag color={STATUS_COLOR[order.status]}>{order.status}</Tag>　
            <Text strong>{t('common:m863')}</Text>{order.buyerUserId}
          </Paragraph>

          <Card title={t('common:m864')} size="small" style={{ marginBottom: 14 }}>
            <Table rowKey="orderItemId" dataSource={progress?.items || []} columns={itemColumns}
              pagination={false} size="middle" />
          </Card>

          <Card title={t('common:m865')} size="small" style={{ marginBottom: 14 }}>
            <Table rowKey="id" dataSource={progress?.registrations || []} columns={regColumns}
              pagination={false} size="middle" />
          </Card>

          <Divider />
          <Button type="primary" size="large" disabled={!progress?.canShip}
            loading={shipping} onClick={onShip}>{t('common:m866')}</Button>
          {!progress?.canShip && (
            <Text type="secondary" style={{ marginLeft: 12 }}>{t('common:m867')}</Text>
          )}
        </>
      )}

      <Modal
        title={`逐台登记 · SKU ${regItem?.skuId}`} open={regOpen} width={760}
        onOk={submitReg} onCancel={() => setRegOpen(false)} destroyOnClose
        okText={t('common:m868')} cancelText={t('common:m96')}
      >
        <Form form={regForm} layout="vertical">
          <Form.List name="units">
            {(units, { add, remove }) => (
              <>
                {units.map((unit) => (
                  <Card size="small" key={unit.key} style={{ marginBottom: 12 }}
                    title={`第 ${unit.name + 1} 台`}
                    extra={<Button size="small" danger onClick={() => remove(unit.name)}>{t('common:m869')}</Button>}>
                    <Space wrap>
                      <Form.Item label={t('common:m870')} name={[unit.name, 'qrCode']} rules={[{ required: true, message: t('common:m871') }]}>
                        <Input placeholder={t('common:m872')} style={{ width: 220 }} />
                      </Form.Item>
                      <Form.Item label={t('common:m855')} name={[unit.name, 'vin']}>
                        <Input placeholder="VIN" style={{ width: 160 }} />
                      </Form.Item>
                      <Form.Item label={t('common:m873')} name={[unit.name, 'frameNo']}>
                        <Input style={{ width: 140 }} />
                      </Form.Item>
                      <Form.Item label={t('common:m856')} name={[unit.name, 'motorNo']}>
                        <Input style={{ width: 140 }} />
                      </Form.Item>
                      <Form.Item label={t('common:m874')} name={[unit.name, 'serialNumber']}>
                        <Input style={{ width: 160 }} />
                      </Form.Item>
                      <Form.Item label={t('common:m136')} name={[unit.name, 'model']}>
                        <Input style={{ width: 140 }} />
                      </Form.Item>
                    </Space>
                    <Form.List name={[unit.name, 'components']}>
                      {(comps, { add: cAdd, remove: cRemove }) => (
                        <>
                          <Text type="secondary">{t('common:m875')}</Text>
                          {comps.map((c) => (
                            <Space key={c.key} style={{ display: 'flex', marginBottom: 6 }}>
                              <Form.Item name={[c.name, 'type']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
                                <Select options={COMPONENT_TYPES} style={{ width: 160 }} placeholder={t('common:m36')} />
                              </Form.Item>
                              <Form.Item name={[c.name, 'no']} rules={[{ required: true, message: t('common:m876') }]} style={{ marginBottom: 0 }}>
                                <Input placeholder={t('common:m877')} style={{ width: 200 }} />
                              </Form.Item>
                              <Button danger size="small" onClick={() => cRemove(c.name)}>{t('common:m767')}</Button>
                            </Space>
                          ))}
                          <Button size="small" onClick={() => cAdd({ type: 'BATTERY', no: '' })}>{t('common:m878')}</Button>
                        </>
                      )}
                    </Form.List>
                  </Card>
                ))}
                <Button type="dashed" block onClick={() => add({ components: [{ type: 'BATTERY', no: '' }] })}>{t('common:m879')}</Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>
    </PageCard>
  );
}
