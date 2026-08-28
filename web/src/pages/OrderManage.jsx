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
export default function OrderManage() {
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
    if (!units.length) { message.warning('请至少登记一台设备'); return; }
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
      message.success('已删除登记（资产回滚）');
      loadProgress(orderId);
    } catch (e) { message.error(`纠错失败：${e.message}`); }
  };

  const onShip = async () => {
    setShipping(true);
    try {
      await shipOrder(orderId);
      message.success('发货成功');
      loadProgress(orderId);
    } catch (e) { message.error(`发货失败：${e.message}`); }
    finally { setShipping(false); }
  };

  const itemColumns = [
    { title: 'SKU', dataIndex: 'skuId', width: 90 },
    { title: '类型', dataIndex: 'assetType', width: 100, render: (v) => <Tag>{v}</Tag> },
    {
      title: '登记进度', width: 240,
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
      title: '操作', width: 120,
      render: (_, it) => (
        <Button size="small" type="primary" onClick={() => openReg({ id: it.orderItemId })}>
          登记
        </Button>
      ),
    },
  ];

  const regColumns = [
    { title: '#', dataIndex: 'seq', width: 50 },
    { title: '资产ID', dataIndex: 'assetId', width: 90 },
    { title: '二维码', dataIndex: 'qrCode', width: 160, ellipsis: true },
    { title: '车架号', dataIndex: 'vin', width: 120 },
    { title: '电机号', dataIndex: 'motorNo', width: 120 },
    { title: '状态', dataIndex: 'status', width: 110, render: (v) => <Tag color="blue">{v}</Tag> },
    {
      title: '操作', width: 90,
      render: (_, r) => (
        <Popconfirm title="删除该登记（资产回滚 RETIRED）？" onConfirm={() => onDelete(r.id)}>
          <Button size="small" danger>纠错</Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <PageCard
      title="发货前逐台登记"
      extra={(
        <Select
          showSearch
          placeholder="选择客户订单"
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
            <Text strong>订单：</Text>{order.orderNo}　
            <Text strong>状态：</Text><Tag color={STATUS_COLOR[order.status]}>{order.status}</Tag>　
            <Text strong>买家：</Text>{order.buyerUserId}
          </Paragraph>

          <Card title="各 SKU 登记进度" size="small" style={{ marginBottom: 14 }}>
            <Table rowKey="orderItemId" dataSource={progress?.items || []} columns={itemColumns}
              pagination={false} size="middle" />
          </Card>

          <Card title="已登记设备（资产列表）" size="small" style={{ marginBottom: 14 }}>
            <Table rowKey="id" dataSource={progress?.registrations || []} columns={regColumns}
              pagination={false} size="middle" />
          </Card>

          <Divider />
          <Button type="primary" size="large" disabled={!progress?.canShip}
            loading={shipping} onClick={onShip}>
            发货（登记齐全后放行）
          </Button>
          {!progress?.canShip && (
            <Text type="secondary" style={{ marginLeft: 12 }}>登记未齐全，不可发货。</Text>
          )}
        </>
      )}

      <Modal
        title={`逐台登记 · SKU ${regItem?.skuId}`} open={regOpen} width={760}
        onOk={submitReg} onCancel={() => setRegOpen(false)} destroyOnClose
        okText="提交登记" cancelText="取消"
      >
        <Form form={regForm} layout="vertical">
          <Form.List name="units">
            {(units, { add, remove }) => (
              <>
                {units.map((unit) => (
                  <Card size="small" key={unit.key} style={{ marginBottom: 12 }}
                    title={`第 ${unit.name + 1} 台`}
                    extra={<Button size="small" danger onClick={() => remove(unit.name)}>移除</Button>}>
                    <Space wrap>
                      <Form.Item label="二维码*" name={[unit.name, 'qrCode']} rules={[{ required: true, message: '请录入二维码' }]}>
                        <Input placeholder="扫码 / 录入唯一二维码" style={{ width: 220 }} />
                      </Form.Item>
                      <Form.Item label="车架号" name={[unit.name, 'vin']}>
                        <Input placeholder="VIN" style={{ width: 160 }} />
                      </Form.Item>
                      <Form.Item label="车架号(主部件)" name={[unit.name, 'frameNo']}>
                        <Input style={{ width: 140 }} />
                      </Form.Item>
                      <Form.Item label="电机号" name={[unit.name, 'motorNo']}>
                        <Input style={{ width: 140 }} />
                      </Form.Item>
                      <Form.Item label="出厂序列号" name={[unit.name, 'serialNumber']}>
                        <Input style={{ width: 160 }} />
                      </Form.Item>
                      <Form.Item label="型号" name={[unit.name, 'model']}>
                        <Input style={{ width: 140 }} />
                      </Form.Item>
                    </Space>
                    <Form.List name={[unit.name, 'components']}>
                      {(comps, { add: cAdd, remove: cRemove }) => (
                        <>
                          <Text type="secondary">主部件编号（component_nos）：</Text>
                          {comps.map((c) => (
                            <Space key={c.key} style={{ display: 'flex', marginBottom: 6 }}>
                              <Form.Item name={[c.name, 'type']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
                                <Select options={COMPONENT_TYPES} style={{ width: 160 }} placeholder="类型" />
                              </Form.Item>
                              <Form.Item name={[c.name, 'no']} rules={[{ required: true, message: '请填编号' }]} style={{ marginBottom: 0 }}>
                                <Input placeholder="编号" style={{ width: 200 }} />
                              </Form.Item>
                              <Button danger size="small" onClick={() => cRemove(c.name)}>删</Button>
                            </Space>
                          ))}
                          <Button size="small" onClick={() => cAdd({ type: 'BATTERY', no: '' })}>+ 加主部件</Button>
                        </>
                      )}
                    </Form.List>
                  </Card>
                ))}
                <Button type="dashed" block onClick={() => add({ components: [{ type: 'BATTERY', no: '' }] })}>
                  + 再登记一台
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>
    </PageCard>
  );
}
