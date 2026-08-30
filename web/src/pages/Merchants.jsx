import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag,
} from 'antd';
import { PlusOutlined, ReloadOutlined, EditOutlined, DeleteOutlined, ShopOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { EMPTY, asArray, fmtTime, useSupplyOptions } from '../components/supplyShared';
import {
  createMerchant, createMerchantBooth, createMerchantZone, deleteMerchant, listMerchantBooths,
  listMerchantZones, listMerchants, updateMerchant,
} from '../api/supplyChain';
import { MERCHANT_BOOTH_STATUS, MERCHANT_STATUS, MERCHANT_ZONE_STATUS } from '../enums';

/**
 * 商家入驻骨架页（Phase 2 骨架 · P2-1）。
 *
 * 本期仅回填骨架数据维护能力：merchants / merchant_zones / merchant_booths 的基础 CRUD，
 * 完整招商审批流（申请 → 审核 → 铺位分配）留 Phase 2。
 * 对接后端 AdminMerchantController（/api/v1/admin/merchants）。
 * 注：该 Controller 本轮未加 @RequirePermission，故按钮不做权限包裹（PRD 亦未定权限码）。
 */
export default function Merchants() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const { stationOptions, stationName } = useSupplyOptions();

  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  // 商家新增 / 编辑
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  // 区块抽屉
  const [zoneOpen, setZoneOpen] = useState(false);
  const [zoneRow, setZoneRow] = useState(null);
  const [zones, setZones] = useState([]);
  const [zoneLoading, setZoneLoading] = useState(false);
  const [zoneForm] = Form.useForm();

  // 铺位抽屉
  const [boothOpen, setBoothOpen] = useState(false);
  const [boothZone, setBoothZone] = useState(null);
  const [booths, setBooths] = useState([]);
  const [boothLoading, setBoothLoading] = useState(false);
  const [boothForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listMerchants();
      setRows(asArray(d));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => { load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    setModalOpen(true);
    form.resetFields();
    form.setFieldsValue({ status: 'PENDING' });
  };

  const openEdit = (record) => {
    setEditing(record);
    setModalOpen(true);
    form.resetFields();
    form.setFieldsValue({
      code: record.code, name: record.name, contact: record.contact,
      country: record.country, status: record.status,
    });
  };

  const submit = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await updateMerchant(editing.id, v);
        message.success(t('supply:merchants.msg.updated'));
      } else {
        await createMerchant(v);
        message.success(t('supply:merchants.msg.created'));
      }
      setModalOpen(false);
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record) => {
    try {
      await deleteMerchant(record.id);
      message.success(t('supply:merchants.msg.deleted'));
      load();
    } catch (e) {
      message.error(t('msg.deleteFailed', { msg: e.message }));
    }
  };

  /* ------------------------------ 区块 ------------------------------ */

  const openZones = async (record) => {
    setZoneRow(record);
    setZoneOpen(true);
    setZones([]);
    zoneForm.resetFields();
    zoneForm.setFieldsValue({ status: 'OPEN' });
    await loadZones(record.id);
  };

  const loadZones = async (merchantId) => {
    setZoneLoading(true);
    try {
      const d = await listMerchantZones(merchantId);
      setZones(asArray(d));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setZones([]);
    } finally {
      setZoneLoading(false);
    }
  };

  const submitZone = async () => {
    const v = await zoneForm.validateFields();
    setZoneLoading(true);
    try {
      await createMerchantZone(zoneRow.id, v);
      message.success(t('supply:merchants.msg.zoneCreated'));
      zoneForm.resetFields();
      zoneForm.setFieldsValue({ status: 'OPEN' });
      await loadZones(zoneRow.id);
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setZoneLoading(false);
    }
  };

  /* ------------------------------ 铺位 ------------------------------ */

  const openBooths = async (zone) => {
    setBoothZone(zone);
    setBoothOpen(true);
    setBooths([]);
    boothForm.resetFields();
    boothForm.setFieldsValue({ status: 'AVAILABLE' });
    setBoothLoading(true);
    try {
      const d = await listMerchantBooths(zone.id);
      setBooths(asArray(d));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setBooths([]);
    } finally {
      setBoothLoading(false);
    }
  };

  const submitBooth = async () => {
    const v = await boothForm.validateFields();
    setBoothLoading(true);
    try {
      await createMerchantBooth(boothZone.id, v);
      message.success(t('supply:merchants.msg.boothCreated'));
      boothForm.resetFields();
      boothForm.setFieldsValue({ status: 'AVAILABLE' });
      const d = await listMerchantBooths(boothZone.id);
      setBooths(asArray(d));
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setBoothLoading(false);
    }
  };

  const columns = [
    { title: t('supply:merchants.col.id'), dataIndex: 'id', width: 80 },
    { title: t('supply:merchants.col.code'), dataIndex: 'code', width: 160 },
    { title: t('supply:merchants.col.name'), dataIndex: 'name', width: 200 },
    { title: t('supply:merchants.col.contact'), dataIndex: 'contact', width: 140 },
    { title: t('supply:merchants.col.country'), dataIndex: 'country', width: 100 },
    {
      title: t('supply:merchants.col.status'),
      dataIndex: 'status',
      width: 110,
      render: (v) => <Tag color={v === 'ACTIVE' ? 'green' : 'orange'}>{v || EMPTY}</Tag>,
    },
    {
      title: t('supply:common.createdAt'),
      dataIndex: 'createdAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 240,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Button size="small" type="link" icon={<ShopOutlined />} onClick={() => openZones(r)}>
            {t('supply:merchants.zones')}
          </Button>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(r)}>
            {t('action.edit')}
          </Button>
          <Popconfirm
            title={t('confirm.delete')}
            okText={t('confirm.deleteOk')}
            cancelText={t('action.cancel')}
            onConfirm={() => remove(r)}
          >
            <Button size="small" type="link" danger icon={<DeleteOutlined />}>{t('action.delete')}</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('supply:merchants.title')}
      subtitle={t('supply:merchants.subtitle')}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            {t('supply:merchants.create')}
          </Button>
        </Space>
      }
    >
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('supply:merchants.phase2Tip')} />

      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      {/* 商家新增 / 编辑 */}
      <Modal
        title={editing ? t('supply:merchants.edit') : t('supply:merchants.create')}
        open={modalOpen}
        onOk={submit}
        confirmLoading={submitting}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
        width={520}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="code"
            label={t('supply:merchants.field.code')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:merchants.field.code') }) }]}
          >
            <Input disabled={Boolean(editing)} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('supply:merchants.field.name')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:merchants.field.name') }) }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="contact" label={t('supply:merchants.field.contact')}>
            <Input />
          </Form.Item>
          <Form.Item name="country" label={t('supply:merchants.field.country')}>
            <Input placeholder="KH" />
          </Form.Item>
          <Form.Item name="status" label={t('supply:merchants.field.status')}>
            <Select options={MERCHANT_STATUS} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 区块 */}
      <Drawer
        title={`${t('supply:merchants.zones')} · ${zoneRow?.name || ''}`}
        open={zoneOpen}
        onClose={() => setZoneOpen(false)}
        width={720}
      >
        <Form form={zoneForm} layout="vertical">
          <Space wrap>
            <Form.Item
              name="zoneCode"
              label={t('supply:merchants.field.zoneCode')}
              rules={[{ required: true, message: t('form.required', { label: t('supply:merchants.field.zoneCode') }) }]}
            >
              <Input style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="name" label={t('supply:merchants.field.zoneName')}>
              <Input style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="stationId" label={t('supply:merchants.field.stationId')}>
              <Select showSearch optionFilterProp="label" allowClear style={{ width: 200 }} options={stationOptions} />
            </Form.Item>
            <Form.Item name="status" label={t('supply:merchants.col.status')}>
              <Select style={{ width: 120 }} options={MERCHANT_ZONE_STATUS} />
            </Form.Item>
            <Form.Item label=" ">
              <Button type="primary" loading={zoneLoading} onClick={submitZone}>{t('supply:merchants.createZone')}</Button>
            </Form.Item>
          </Space>
        </Form>

        <Table
          rowKey="id"
          size="small"
          loading={zoneLoading}
          dataSource={zones}
          pagination={false}
          columns={[
            { title: t('supply:merchants.col.zoneCode'), dataIndex: 'zoneCode', width: 140 },
            { title: t('supply:merchants.col.name'), dataIndex: 'name', width: 160 },
            {
              title: t('supply:merchants.col.stationId'),
              dataIndex: 'stationId',
              width: 160,
              render: (v) => (v == null ? EMPTY : stationName(v)),
            },
            { title: t('supply:merchants.col.status'), dataIndex: 'status', width: 100, render: (v) => <Tag>{v || EMPTY}</Tag> },
            {
              title: t('table.actions'),
              key: '_actions',
              width: 120,
              render: (_, r) => (
                <Button size="small" type="link" onClick={() => openBooths(r)}>
                  {t('supply:merchants.booths')}
                </Button>
              ),
            },
          ]}
        />
      </Drawer>

      {/* 铺位 */}
      <Drawer
        title={`${t('supply:merchants.booths')} · ${boothZone?.zoneCode || ''}`}
        open={boothOpen}
        onClose={() => setBoothOpen(false)}
        width={720}
      >
        <Form form={boothForm} layout="vertical">
          <Space wrap>
            <Form.Item
              name="boothCode"
              label={t('supply:merchants.field.boothCode')}
              rules={[{ required: true, message: t('form.required', { label: t('supply:merchants.field.boothCode') }) }]}
            >
              <Input style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="name" label={t('supply:merchants.field.boothName')}>
              <Input style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="areaSqm" label={t('supply:merchants.field.areaSqm')}>
              <InputNumber min={0} precision={2} style={{ width: 130 }} />
            </Form.Item>
            <Form.Item name="monthlyRent" label={t('supply:merchants.field.monthlyRent')}>
              <InputNumber min={0} precision={2} style={{ width: 130 }} />
            </Form.Item>
            <Form.Item name="status" label={t('supply:merchants.col.status')}>
              <Select style={{ width: 140 }} options={MERCHANT_BOOTH_STATUS} />
            </Form.Item>
            <Form.Item label=" ">
              <Button type="primary" loading={boothLoading} onClick={submitBooth}>{t('supply:merchants.createBooth')}</Button>
            </Form.Item>
          </Space>
        </Form>

        <Table
          rowKey="id"
          size="small"
          loading={boothLoading}
          dataSource={booths}
          pagination={false}
          columns={[
            { title: t('supply:merchants.col.boothCode'), dataIndex: 'boothCode', width: 150 },
            { title: t('supply:merchants.col.name'), dataIndex: 'name', width: 160 },
            {
              title: t('supply:merchants.col.areaSqm'),
              dataIndex: 'areaSqm',
              width: 110,
              render: (v) => (v == null ? EMPTY : Number(v).toFixed(2)),
            },
            {
              title: t('supply:merchants.col.monthlyRent'),
              dataIndex: 'monthlyRent',
              width: 120,
              render: (v) => (v == null ? EMPTY : Number(v).toFixed(2)),
            },
            { title: t('supply:merchants.col.status'), dataIndex: 'status', width: 110, render: (v) => <Tag>{v || EMPTY}</Tag> },
          ]}
        />
      </Drawer>
    </PageCard>
  );
}
