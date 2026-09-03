import React, { useState, useMemo } from 'react';
import { Tabs, Card, Form, Select, Input, InputNumber, Button, Space, Table, message, Modal, Popconfirm } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import CrudTable from '../components/CrudTable';
import { ScopeBanner, useCurrentScope } from '../components/inventoryShared';
import { useFetch } from '../hooks';
import api from '../api';
import {
  ASSET_TYPE, PRODUCT_STATUS, SKU_STATUS, MANUFACTURER_STATUS, PURCHASE_STATUS,
} from '../enums';
import { useTranslation } from 'react-i18next';

const manufacturerFields = [
  { name: 'code', label: '厂家编码', required: true },
  { name: 'name', label: '厂家名称', required: true },
  { name: 'contact', label: '联系人' },
  { name: 'country', label: '国家/地区' },
  { name: 'status', label: '状态', type: 'select', options: MANUFACTURER_STATUS, initialValue: 'ACTIVE' },
];
const productFields = (mf) => {
  const { t } = useTranslation('common');
  return ([
  { name: 'manufacturerId', label: t('common:m981'), type: 'select', required: true, options: mf },
  { name: 'name', label: t('common:m982'), required: true },
  { name: 'assetType', label: t('common:m92'), type: 'select', required: true, options: ASSET_TYPE },
  { name: 'model', label: t('common:m136') },
  { name: 'description', label: t('common:m602'), type: 'textarea' },
  { name: 'status', label: t('common:m8'), type: 'select', options: PRODUCT_STATUS, initialValue: 'ON_SALE' },
]);
};
const skuFields = (pf) => {
  const { t } = useTranslation('common');
  return ([
  { name: 'productId', label: t('common:m983'), type: 'select', required: true, options: pf },
  { name: 'skuCode', label: t('common:m984'), required: true },
  { name: 'price', label: t('common:m985'), type: 'number', required: true },
  { name: 'currency', label: t('common:m986'), initialValue: 'USD' },
  { name: 'specsJson', label: t('common:m987'), type: 'textarea' },
  { name: 'status', label: t('common:m8'), type: 'select', options: SKU_STATUS, initialValue: 'ACTIVE' },
]);
};
const orderFields = (pf, sf) => {
  const { t } = useTranslation('common');
  return ([
  { name: 'productId', label: t('common:m983'), type: 'select', required: true, options: pf },
  { name: 'skuId', label: 'SKU', type: 'select', required: true, options: sf },
  { name: 'buyerId', label: t('common:m988'), type: 'number' },
  { name: 'qty', label: t('common:m765'), type: 'number', initialValue: 1 },
  { name: 'unitPrice', label: t('common:m989'), type: 'number' },
  { name: 'currency', label: t('common:m986'), initialValue: 'USD' },
]);
};

export default function Manufacturer() {  const { t } = useTranslation('common');
  const scope = useCurrentScope();

  const { data: manufacturers } = useFetch(() => api.get('/v1/admin/manufacturer/manufacturers'));
  const { data: products } = useFetch(() => api.get('/v1/admin/manufacturer/products'));
  const { data: skus } = useFetch(() => api.get('/v1/admin/manufacturer/skus'));

  const mOpts = useMemo(() => (manufacturers || []).map((m) => ({ label: m.name, value: m.id })), [manufacturers]);
  const pOpts = useMemo(() => (products || []).map((p) => ({ label: p.name, value: p.id })), [products]);
  const sOpts = useMemo(() => (skus || []).map((s) => ({ label: s.skuCode, value: s.id })), [skus]);

  const orderCols = [
    { title: t('common:m884'), dataIndex: 'orderNo' },
    { title: t('common:m480'), dataIndex: 'productId' },
    { title: 'SKU ID', dataIndex: 'skuId' },
    { title: t('common:m990'), dataIndex: 'buyerId' },
    { title: t('common:m765'), dataIndex: 'qty' },
    { title: t('common:m991'), dataIndex: 'totalAmount', render: (v, r) => `${v} ${r.currency}` },
    { title: t('common:m8'), dataIndex: 'status', render: (v) => <span className="tag tag-green">{v}</span> },
  ];
  const orderActions = (record) => [
    <Popconfirm key="pay" title={t('common:m992')} onConfirm={() => actPut(`/v1/admin/manufacturer/purchase-orders/${record.id}/pay`, record)}>
      <Button size="small" type="link">{t('common:m993')}</Button>
    </Popconfirm>,
    <Popconfirm key="ship" title={t('common:m994')} onConfirm={() => actPut(`/v1/admin/manufacturer/purchase-orders/${record.id}/ship`, record)}>
      <Button size="small" type="link">{t('common:m995')}</Button>
    </Popconfirm>,
    <Button key="qr" size="small" type="link" onClick={() => openQr(record)}>{t('common:m996')}</Button>,
  ];

  const [qrModal, setQrModal] = useState(null);
  const [qrForm] = Form.useForm();
  const openQr = (order) => { setQrModal(order); qrForm.resetFields(); };
  const actPut = async (url) => {
    try { await api.put(url); message.success(t('common:m997')); }
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
    <ScopeBanner scope={scope} />
    <Tabs defaultActiveKey="manufacturers" items={[
      { key: 'manufacturers', label: t('common:m981'), children: <CrudTable title={t('common:m981')} subtitle="专业组织公司 / 工厂（由平台维护）" endpoint="/v1/admin/manufacturer/manufacturers" columns={[
        { title: t('common:m341'), dataIndex: 'code' }, { title: t('common:m277'), dataIndex: 'name' }, { title: t('common:m836'), dataIndex: 'contact' }, { title: t('common:m998'), dataIndex: 'country' }, { title: t('common:m8'), dataIndex: 'status' },
      ]} fields={manufacturerFields} rowKey="id" /> },
      { key: 'products', label: t('common:m983'), children: <CrudTable title={t('common:m983')} subtitle="厂家发布的资产类商品" endpoint="/v1/admin/manufacturer/products" columns={[
        { title: 'ID', dataIndex: 'id' }, { title: t('common:m981'), dataIndex: 'manufacturerId' }, { title: t('common:m277'), dataIndex: 'name' }, { title: t('common:m92'), dataIndex: 'assetType' }, { title: t('common:m136'), dataIndex: 'model' }, { title: t('common:m8'), dataIndex: 'status' },
      ]} fields={productFields(mOpts)} rowKey="id" query={{}} /> },
      { key: 'skus', label: t('common:m999'), children: <CrudTable title={t('common:m999')} subtitle="商品变体定价" endpoint="/v1/admin/manufacturer/skus" columns={[
        { title: 'ID', dataIndex: 'id' }, { title: t('common:m983'), dataIndex: 'productId' }, { title: 'SKU', dataIndex: 'skuCode' }, { title: t('common:m985'), dataIndex: 'price', render: (v, r) => `${v} ${r.currency}` }, { title: t('common:m8'), dataIndex: 'status' },
      ]} fields={skuFields(pOpts)} rowKey="id" /> },
      { key: 'orders', label: t('common:m1000'), children: <CrudTable title={t('common:m1000')} subtitle="客户购买 → 支付 → 发货 → 登记二维码出生资产" endpoint="/v1/admin/manufacturer/purchase-orders" columns={orderCols} fields={orderFields(pOpts, sOpts)} rowKey="id" extraRowActions={orderActions} /> },
    ]} />

    <Modal title={`登记二维码 / 资产出生 — 采购单 #${qrModal?.id || ''}`} open={!!qrModal} onCancel={() => setQrModal(null)} onOk={submitQr} width={760}>
      <p style={{ color: 'var(--muted)', fontSize: 12 }}>{t('common:m1001')}</p>
      <Form form={qrForm} initialValues={{ items: [{}] }}>
        <Form.List name="items">
          {(fields, { add, remove }) => (
            <>
              {fields.map((f) => (
                <Space key={f.key} align="baseline" style={{ display: 'flex', marginBottom: 8 }}>
                  <Form.Item {...f} name={[f.name, 'assetNo']} rules={[{ required: true, message: t('common:m168') }]}><Input placeholder={t('common:m168')} /></Form.Item>
                  <Form.Item {...f} name={[f.name, 'serialNumber']} rules={[{ required: true, message: t('common:m182') }]}><Input placeholder={t('common:m874')} /></Form.Item>
                  <Form.Item {...f} name={[f.name, 'qrCode']}><Input placeholder={t('common:m1002')} /></Form.Item>
                  <Form.Item {...f} name={[f.name, 'assetType']} initialValue="VEHICLE"><Select options={ASSET_TYPE} style={{ width: 130 }} /></Form.Item>
                  {fields.length > 1 && <Button type="link" danger onClick={() => remove(f.name)}>{t('common:m83')}</Button>}
                </Space>
              ))}
              <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />}>{t('common:m1003')}</Button>
            </>
          )}
        </Form.List>
      </Form>
    </Modal>
    </>
  );
}
