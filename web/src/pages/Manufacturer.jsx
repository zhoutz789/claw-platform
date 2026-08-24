import React, { useState, useMemo } from 'react';
import { Tabs, Card, Form, Select, Input, InputNumber, Button, Space, Table, message, Modal, Popconfirm } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import CrudTable from '../components/CrudTable';
import { useFetch } from '../hooks';
import api from '../api';
import {
  ASSET_TYPE, PRODUCT_STATUS, SKU_STATUS, MANUFACTURER_STATUS, PURCHASE_STATUS,
} from '../enums';

const manufacturerFields = [
  { name: 'code', label: '厂家编码', required: true },
  { name: 'name', label: '厂家名称', required: true },
  { name: 'contact', label: '联系人' },
  { name: 'country', label: '国家/地区' },
  { name: 'status', label: '状态', type: 'select', options: MANUFACTURER_STATUS, initialValue: 'ACTIVE' },
];
const productFields = (mf) => [
  { name: 'manufacturerId', label: '厂家', type: 'select', required: true, options: mf },
  { name: 'name', label: '商品名称', required: true },
  { name: 'assetType', label: '资产类型', type: 'select', required: true, options: ASSET_TYPE },
  { name: 'model', label: '型号' },
  { name: 'description', label: '描述', type: 'textarea' },
  { name: 'status', label: '状态', type: 'select', options: PRODUCT_STATUS, initialValue: 'ON_SALE' },
];
const skuFields = (pf) => [
  { name: 'productId', label: '商品', type: 'select', required: true, options: pf },
  { name: 'skuCode', label: 'SKU 编码', required: true },
  { name: 'price', label: '价格', type: 'number', required: true },
  { name: 'currency', label: '币种', initialValue: 'USD' },
  { name: 'specsJson', label: '规格(JSON)', type: 'textarea' },
  { name: 'status', label: '状态', type: 'select', options: SKU_STATUS, initialValue: 'ACTIVE' },
];
const orderFields = (pf, sf) => [
  { name: 'productId', label: '商品', type: 'select', required: true, options: pf },
  { name: 'skuId', label: 'SKU', type: 'select', required: true, options: sf },
  { name: 'buyerId', label: '采购方用户ID', type: 'number' },
  { name: 'qty', label: '数量', type: 'number', initialValue: 1 },
  { name: 'unitPrice', label: '单价', type: 'number' },
  { name: 'currency', label: '币种', initialValue: 'USD' },
];

export default function Manufacturer() {
  const { data: manufacturers } = useFetch(() => api.get('/v1/admin/manufacturer/manufacturers'));
  const { data: products } = useFetch(() => api.get('/v1/admin/manufacturer/products'));
  const { data: skus } = useFetch(() => api.get('/v1/admin/manufacturer/skus'));

  const mOpts = useMemo(() => (manufacturers || []).map((m) => ({ label: m.name, value: m.id })), [manufacturers]);
  const pOpts = useMemo(() => (products || []).map((p) => ({ label: p.name, value: p.id })), [products]);
  const sOpts = useMemo(() => (skus || []).map((s) => ({ label: s.skuCode, value: s.id })), [skus]);

  const orderCols = [
    { title: '订单号', dataIndex: 'orderNo' },
    { title: '商品ID', dataIndex: 'productId' },
    { title: 'SKU ID', dataIndex: 'skuId' },
    { title: '采购方', dataIndex: 'buyerId' },
    { title: '数量', dataIndex: 'qty' },
    { title: '总额', dataIndex: 'totalAmount', render: (v, r) => `${v} ${r.currency}` },
    { title: '状态', dataIndex: 'status', render: (v) => <span className="tag tag-green">{v}</span> },
  ];
  const orderActions = (record) => [
    <Popconfirm key="pay" title="确认支付？" onConfirm={() => actPut(`/v1/admin/manufacturer/purchase-orders/${record.id}/pay`, record)}>
      <Button size="small" type="link">支付</Button>
    </Popconfirm>,
    <Popconfirm key="ship" title="确认发货？" onConfirm={() => actPut(`/v1/admin/manufacturer/purchase-orders/${record.id}/ship`, record)}>
      <Button size="small" type="link">发货</Button>
    </Popconfirm>,
    <Button key="qr" size="small" type="link" onClick={() => openQr(record)}>登记二维码</Button>,
  ];

  const [qrModal, setQrModal] = useState(null);
  const [qrForm] = Form.useForm();
  const openQr = (order) => { setQrModal(order); qrForm.resetFields(); };
  const actPut = async (url) => {
    try { await api.put(url); message.success('操作成功'); }
    catch (e) { message.error(e.message); }
  };
  const submitQr = async () => {
    const vals = await qrForm.validateFields();
    try {
      const items = vals.items.map((it) => ({ serialNumber: it.serialNumber, qrCode: it.qrCode, assetNo: it.assetNo, assetType: it.assetType }));
      const r = await api.post(`/v1/admin/manufacturer/purchase-orders/${qrModal.id}/register-qr`, { orderId: qrModal.id, items });
      message.success(`已登记并出生 ${r.bornCount} 台资产`);
      setQrModal(null);
    } catch (e) { message.error(e.message); }
  };

  return (
    <>
    <Tabs defaultActiveKey="manufacturers" items={[
      { key: 'manufacturers', label: '厂家', children: <CrudTable title="厂家" subtitle="专业组织公司 / 工厂（由平台维护）" endpoint="/v1/admin/manufacturer/manufacturers" columns={[
        { title: '编码', dataIndex: 'code' }, { title: '名称', dataIndex: 'name' }, { title: '联系人', dataIndex: 'contact' }, { title: '国家', dataIndex: 'country' }, { title: '状态', dataIndex: 'status' },
      ]} fields={manufacturerFields} rowKey="id" /> },
      { key: 'products', label: '商品', children: <CrudTable title="商品" subtitle="厂家发布的资产类商品" endpoint="/v1/admin/manufacturer/products" columns={[
        { title: 'ID', dataIndex: 'id' }, { title: '厂家', dataIndex: 'manufacturerId' }, { title: '名称', dataIndex: 'name' }, { title: '资产类型', dataIndex: 'assetType' }, { title: '型号', dataIndex: 'model' }, { title: '状态', dataIndex: 'status' },
      ]} fields={productFields(mOpts)} rowKey="id" query={{}} /> },
      { key: 'skus', label: 'SKU 定价', children: <CrudTable title="SKU 定价" subtitle="商品变体定价" endpoint="/v1/admin/manufacturer/skus" columns={[
        { title: 'ID', dataIndex: 'id' }, { title: '商品', dataIndex: 'productId' }, { title: 'SKU', dataIndex: 'skuCode' }, { title: '价格', dataIndex: 'price', render: (v, r) => `${v} ${r.currency}` }, { title: '状态', dataIndex: 'status' },
      ]} fields={skuFields(pOpts)} rowKey="id" /> },
      { key: 'orders', label: '采购订单', children: <CrudTable title="采购订单" subtitle="客户购买 → 支付 → 发货 → 登记二维码出生资产" endpoint="/v1/admin/manufacturer/purchase-orders" columns={orderCols} fields={orderFields(pOpts, sOpts)} rowKey="id" extraRowActions={orderActions} /> },
    ]} />

    <Modal title={`登记二维码 / 资产出生 — 采购单 #${qrModal?.id || ''}`} open={!!qrModal} onCancel={() => setQrModal(null)} onOk={submitQr} width={760}>
      <p style={{ color: 'var(--muted)', fontSize: 12 }}>发货前逐台录入序列号与二维码，每台登记将出生一台资产并写入「生产出厂」生命周期。</p>
      <Form form={qrForm} initialValues={{ items: [{}] }}>
        <Form.List name="items">
          {(fields, { add, remove }) => (
            <>
              {fields.map((f) => (
                <Space key={f.key} align="baseline" style={{ display: 'flex', marginBottom: 8 }}>
                  <Form.Item {...f} name={[f.name, 'assetNo']} rules={[{ required: true, message: '资产编号' }]}><Input placeholder="资产编号" /></Form.Item>
                  <Form.Item {...f} name={[f.name, 'serialNumber']} rules={[{ required: true, message: '序列号' }]}><Input placeholder="出厂序列号" /></Form.Item>
                  <Form.Item {...f} name={[f.name, 'qrCode']}><Input placeholder="二维码(可空,自动生成)" /></Form.Item>
                  <Form.Item {...f} name={[f.name, 'assetType']} initialValue="VEHICLE"><Select options={ASSET_TYPE} style={{ width: 130 }} /></Form.Item>
                  {fields.length > 1 && <Button type="link" danger onClick={() => remove(f.name)}>删除</Button>}
                </Space>
              ))}
              <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />}>新增一台</Button>
            </>
          )}
        </Form.List>
      </Form>
    </Modal>
    </>
  );
}
