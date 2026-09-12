import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Table, Button, Modal, Form, Input, InputNumber, Select, Tag, Drawer, message, Space, Spin, Empty, Typography, Popconfirm,
} from 'antd';
import { PlusOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import {
  listProductClasses, createProductClass, seedProductClasses,
  getProductClassAttrs, createProductClassAttr, createVehicleAsset,
} from '../api/vehicle';
import { useTranslation } from 'react-i18next';

const { Text } = Typography;
const { TextArea } = Input;

// 自动驾驶等级（NONE / ASSISTED / FULL）。值即后端枚举，标签走 i18n。
const AUTONOMY_LEVELS = ['NONE', 'ASSISTED', 'FULL'];
const AUTONOMY_LEVEL_COLOR = { NONE: 'default', ASSISTED: 'blue', FULL: 'green' };

// 场景属性类型（与新增表单的 Select 对应）
const ATTR_TYPES = ['STRING', 'NUMBER', 'BOOLEAN', 'ENUM', 'DATE'];

/**
 * 把逗号分隔文本转成去空白后的数组（用于 capabilityTags / defaultDeviceTypes / requiredCerts）。
 * @param {string} [s]
 * @returns {string[]}
 */
const toArr = (s) => (s || '').split(',').map((x) => x.trim()).filter(Boolean);

/**
 * 车型管理（平台 admin）。车型 = 地面自动驾驶 / 换电车辆的产品类目，
 * 含场景属性（EAV）管理。全部对接真实后端，无 mock。
 * @returns {JSX.Element}
 */
export default function VehicleProductClass() {
  const { t } = useTranslation(['common', 'task']);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [creating, setCreating] = useState(false);

  const [attrDrawer, setAttrDrawer] = useState({ open: false, code: null, name: '' });
  const [attrs, setAttrs] = useState([]);
  const [attrsLoading, setAttrsLoading] = useState(false);
  const [attrForm] = Form.useForm();
  const [attrAdding, setAttrAdding] = useState(false);

  /* ---------- 新建车辆资产（调 createVehicleAsset 走门面） ---------- */
  const navigate = useNavigate();
  const [createVehicleOpen, setCreateVehicleOpen] = useState(false);
  const [createVehicleForm] = Form.useForm();
  const [creatingVehicle, setCreatingVehicle] = useState(false);

  const openCreateVehicle = () => { createVehicleForm.resetFields(); setCreateVehicleOpen(true); };
  const submitCreateVehicle = () => {
    createVehicleForm.validateFields().then(async (v) => {
      setCreatingVehicle(true);
      try {
        const body = {
          assetNo: v.assetNo,
          model: v.model,
          qrCode: v.qrCode || '',
          vin: v.vin || '',
          frameNo: v.frameNo || '',
          motorNo: v.motorNo || '',
          ownerId: v.ownerId != null ? Number(v.ownerId) : null,
        };
        await createVehicleAsset(body);
        message.success(t('task:vehicle.create.success'));
        setCreateVehicleOpen(false);
        navigate('/assets');
      } catch (e) {
        message.error(t('task:vehicle.create.failed', { message: e.message }));
      } finally {
        setCreatingVehicle(false);
      }
    }).catch(() => {});
  };

  const load = useCallback(() => {
    setLoading(true);
    listProductClasses()
      .then((d) => setRows(Array.isArray(d) ? d : []))
      .catch((e) => { message.error(t('task:vehicle.productClass.loadFailed', { message: e.message })); setRows([]); })
      .finally(() => setLoading(false));
  }, [t]);

  useEffect(() => { load(); }, [load]);

  /* ---------- 新增车型 ---------- */
  const openCreate = () => { createForm.resetFields(); setCreateOpen(true); };
  const submitCreate = () => {
    createForm.validateFields().then(async (v) => {
      setCreating(true);
      try {
        const body = {
          code: v.code,
          nameZh: v.nameZh,
          nameEn: v.nameEn,
          nameKm: v.nameKm,
          scenario: v.scenario,
          autonomyLevel: v.autonomyLevel,
          capabilityTags: toArr(v.capabilityTags),
          defaultDeviceTypes: toArr(v.defaultDeviceTypes),
          requiredCerts: toArr(v.requiredCerts),
          attrSchema: v.attrSchema || '',
          geofencePreset: v.geofencePreset || '',
        };
        await createProductClass(body);
        message.success(t('task:vehicle.productClass.createSuccess'));
        setCreateOpen(false);
        load();
      } catch (e) {
        message.error(t('task:vehicle.productClass.createFailed', { message: e.message }));
      } finally {
        setCreating(false);
      }
    }).catch(() => {});
  };

  /* ---------- 种子默认车型 ---------- */
  const onSeed = () => {
    seedProductClasses()
      .then((n) => message.success(t('task:vehicle.productClass.seeded', { count: n })))
      .catch((e) => message.error(t('task:vehicle.productClass.seedFailed', { message: e.message })));
  };

  /* ---------- 场景属性抽屉 ---------- */
  const openAttrs = (row) => {
    setAttrDrawer({ open: true, code: row.code, name: row.nameZh || row.code });
    attrForm.resetFields();
    setAttrsLoading(true);
    getProductClassAttrs(row.code)
      .then((d) => setAttrs(Array.isArray(d) ? d : []))
      .catch((e) => { message.error(t('task:vehicle.productClass.attrLoadFailed', { message: e.message })); setAttrs([]); })
      .finally(() => setAttrsLoading(false));
  };

  const submitAttr = () => {
    attrForm.validateFields().then(async (v) => {
      setAttrAdding(true);
      try {
        await createProductClassAttr(attrDrawer.code, {
          attrKey: v.attrKey,
          attrType: v.attrType,
          unit: v.unit || '',
          required: !!v.required,
          labelZh: v.labelZh,
          labelEn: v.labelEn,
          labelKm: v.labelKm,
          sortOrder: v.sortOrder != null ? Number(v.sortOrder) : 0,
        });
        message.success(t('task:vehicle.productClass.attrAddSuccess'));
        attrForm.resetFields();
        const d = await getProductClassAttrs(attrDrawer.code);
        setAttrs(Array.isArray(d) ? d : []);
      } catch (e) {
        message.error(t('task:vehicle.productClass.attrAddFailed', { message: e.message }));
      } finally {
        setAttrAdding(false);
      }
    }).catch(() => {});
  };

  const columns = [
    { title: t('task:vehicle.productClass.code'), dataIndex: 'code', render: (v) => <Text strong>{v}</Text> },
    { title: t('task:vehicle.productClass.nameZh'), dataIndex: 'nameZh', render: (v, r) => `${v || '—'} / ${r.nameEn || ''} / ${r.nameKm || ''}` },
    { title: t('task:vehicle.productClass.scenario'), dataIndex: 'scenario', render: (v) => v || '—' },
    {
      title: t('task:vehicle.productClass.autonomyLevel'),
      dataIndex: 'autonomyLevel',
      render: (v) => <Tag color={AUTONOMY_LEVEL_COLOR[v] || 'default'}>{v || '—'}</Tag>,
    },
    {
      title: t('task:vehicle.productClass.capabilityTags'),
      dataIndex: 'capabilityTags',
      render: (v) => (Array.isArray(v) && v.length ? v.map((x) => <Tag key={x}>{x}</Tag>) : '—'),
    },
    {
      title: t('task:vehicle.productClass.defaultDeviceTypes'),
      dataIndex: 'defaultDeviceTypes',
      render: (v) => (Array.isArray(v) && v.length ? v.map((x) => <Tag key={x} color="purple">{x}</Tag>) : '—'),
    },
    {
      title: t('task:vehicle.productClass.requiredCerts'),
      dataIndex: 'requiredCerts',
      render: (v) => (Array.isArray(v) && v.length ? v.map((x) => <Tag key={x} color="gold">{x}</Tag>) : '—'),
    },
    {
      title: t('task:vehicle.productClass.actions'),
      key: 'actions',
      render: (_, r) => (
        <Button size="small" type="link" icon={<SettingOutlined />} onClick={() => openAttrs(r)}>
          {t('task:vehicle.productClass.attrs')}
        </Button>
      ),
    },
  ];

  const attrColumns = [
    { title: t('task:vehicle.productClass.attrKey'), dataIndex: 'attrKey' },
    { title: t('task:vehicle.productClass.attrType'), dataIndex: 'attrType', render: (v) => <Tag>{v}</Tag> },
    { title: t('task:vehicle.productClass.unit'), dataIndex: 'unit', render: (v) => v || '—' },
    { title: t('task:vehicle.productClass.required'), dataIndex: 'required', render: (v) => (v ? '是' : '否') },
    { title: t('task:vehicle.productClass.labelZh'), dataIndex: 'labelZh', render: (v, r) => `${v || '—'} / ${r.labelEn || ''} / ${r.labelKm || ''}` },
    { title: t('task:vehicle.productClass.sortOrder'), dataIndex: 'sortOrder' },
  ];

  return (
    <PageCard title={t('task:vehicle.productClass.title')}>
      <Space style={{ marginBottom: 14 }} wrap>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>{t('task:vehicle.productClass.addVehicle')}</Button>
        <Popconfirm
          title={t('task:vehicle.productClass.seedConfirm')}
          okText={t('common:m97')}
          cancelText={t('common:m96')}
          onConfirm={onSeed}
        >
          <Button icon={<ReloadOutlined />}>{t('task:vehicle.productClass.seed')}</Button>
        </Popconfirm>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreateVehicle}>{t('task:vehicle.create.button')}</Button>
      </Space>

      <Table
        rowKey="code"
        loading={loading}
        pagination={{ pageSize: 10 }}
        dataSource={rows}
        columns={columns}
        locale={{ emptyText: <Empty description={t('task:vehicle.productClass.empty')} /> }}
      />

      {/* 新增车型 */}
      <Modal
        title={t('task:vehicle.productClass.addVehicle')}
        open={createOpen}
        onOk={submitCreate}
        confirmLoading={creating}
        onCancel={() => setCreateOpen(false)}
        okText={t('common:m97')}
        cancelText={t('common:m96')}
        width={640}
        destroyOnClose
      >
        <Form form={createForm} layout="vertical" initialValues={{ autonomyLevel: 'NONE' }}>
          <Space size="large" wrap>
            <Form.Item label={t('task:vehicle.productClass.code')} name="code" rules={[{ required: true, message: t('task:vehicle.productClass.codeRequired') }]} style={{ minWidth: 200 }}>
              <Input placeholder="e.g. EV_DELIVERY_VAN" />
            </Form.Item>
            <Form.Item label={t('task:vehicle.productClass.autonomyLevel')} name="autonomyLevel" rules={[{ required: true }]} style={{ minWidth: 180 }}>
              <Select options={AUTONOMY_LEVELS.map((l) => ({ label: t(`task:vehicle.productClass.level.${l}`), value: l }))} />
            </Form.Item>
          </Space>
          <Space size="large" wrap>
            <Form.Item label={t('task:vehicle.productClass.nameZh')} name="nameZh" rules={[{ required: true, message: t('task:vehicle.productClass.nameZhRequired') }]} style={{ minWidth: 180 }}>
              <Input placeholder="中文名" />
            </Form.Item>
            <Form.Item label={t('task:vehicle.productClass.nameEn')} name="nameEn" rules={[{ required: true, message: t('task:vehicle.productClass.nameEnRequired') }]} style={{ minWidth: 180 }}>
              <Input placeholder="English name" />
            </Form.Item>
            <Form.Item label={t('task:vehicle.productClass.nameKm')} name="nameKm" rules={[{ required: true, message: t('task:vehicle.productClass.nameKmRequired') }]} style={{ minWidth: 180 }}>
              <Input placeholder="ឈ្មោះខ្មែរ" />
            </Form.Item>
          </Space>
          <Form.Item label={t('task:vehicle.productClass.scenario')} name="scenario" style={{ minWidth: 200 }}>
            <Input placeholder={t('task:vehicle.productClass.scenarioPlaceholder')} />
          </Form.Item>
          <Form.Item label={t('task:vehicle.productClass.capabilityTags')} name="capabilityTags" tooltip={t('task:vehicle.productClass.commaTip')}>
            <Input placeholder="DELIVERY,SWEEP" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.productClass.defaultDeviceTypes')} name="defaultDeviceTypes" tooltip={t('task:vehicle.productClass.commaTip')}>
            <Input placeholder="CAMERA,LIDAR" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.productClass.requiredCerts')} name="requiredCerts" tooltip={t('task:vehicle.productClass.commaTip')}>
            <Input placeholder="ISO9001" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.productClass.attrSchema')} name="attrSchema">
            <TextArea rows={2} placeholder='{"payloadKg":120}' />
          </Form.Item>
          <Form.Item label={t('task:vehicle.productClass.geofencePreset')} name="geofencePreset">
            <TextArea rows={2} placeholder='{"radiusM":500}' />
          </Form.Item>
        </Form>
      </Modal>

      {/* 场景属性 */}
      <Drawer
        title={`${t('task:vehicle.productClass.attrs')} · ${attrDrawer.name}`}
        width={760}
        open={attrDrawer.open}
        onClose={() => setAttrDrawer({ open: false, code: null, name: '' })}
      >
        <Card
          size="small"
          title={t('task:vehicle.productClass.addAttr')}
          style={{ marginBottom: 14 }}
          extra={<Button type="primary" size="small" loading={attrAdding} onClick={submitAttr}>{t('common:m97')}</Button>}
        >
          <Form form={attrForm} layout="vertical" initialValues={{ attrType: 'STRING', required: false, sortOrder: 0 }}>
            <Space size="large" wrap>
              <Form.Item label={t('task:vehicle.productClass.attrKey')} name="attrKey" rules={[{ required: true, message: t('task:vehicle.productClass.attrKeyRequired') }]} style={{ minWidth: 180 }}>
                <Input placeholder="payloadKg" />
              </Form.Item>
              <Form.Item label={t('task:vehicle.productClass.attrType')} name="attrType" rules={[{ required: true }]} style={{ minWidth: 160 }}>
                <Select options={ATTR_TYPES.map((x) => ({ label: x, value: x }))} />
              </Form.Item>
              <Form.Item label={t('task:vehicle.productClass.unit')} name="unit" style={{ minWidth: 120 }}>
                <Input placeholder="kg" />
              </Form.Item>
              <Form.Item label={t('task:vehicle.productClass.sortOrder')} name="sortOrder" style={{ minWidth: 120 }}>
                <Input type="number" />
              </Form.Item>
            </Space>
            <Space size="large" wrap>
              <Form.Item label={t('task:vehicle.productClass.labelZh')} name="labelZh" rules={[{ required: true, message: t('task:vehicle.productClass.labelZhRequired') }]} style={{ minWidth: 160 }}>
                <Input placeholder="载重" />
              </Form.Item>
              <Form.Item label={t('task:vehicle.productClass.labelEn')} name="labelEn" rules={[{ required: true, message: t('task:vehicle.productClass.labelEnRequired') }]} style={{ minWidth: 160 }}>
                <Input placeholder="Payload" />
              </Form.Item>
              <Form.Item label={t('task:vehicle.productClass.labelKm')} name="labelKm" rules={[{ required: true, message: t('task:vehicle.productClass.labelKmRequired') }]} style={{ minWidth: 160 }}>
                <Input placeholder="ទម្ងន់" />
              </Form.Item>
              <Form.Item label={t('task:vehicle.productClass.required')} name="required" style={{ minWidth: 120 }}>
                <Select options={[{ label: t('task:vehicle.productClass.no'), value: false }, { label: t('task:vehicle.productClass.yes'), value: true }]} />
              </Form.Item>
            </Space>
          </Form>
        </Card>

        <Table
          rowKey="id"
          loading={attrsLoading}
          pagination={false}
          size="small"
          dataSource={attrs}
          columns={attrColumns}
          locale={{ emptyText: <Empty description={t('task:vehicle.productClass.attrEmpty')} /> }}
        />
      </Drawer>

      {/* 新建车辆资产：调 createVehicleAsset 走统一门面，成功后跳资产列表核验 */}
      <Modal
        title={t('task:vehicle.create.title')}
        open={createVehicleOpen}
        onOk={submitCreateVehicle}
        confirmLoading={creatingVehicle}
        onCancel={() => setCreateVehicleOpen(false)}
        okText={t('task:vehicle.create.submit')}
        cancelText={t('common:m96')}
        width={600}
        destroyOnClose
      >
        <Form form={createVehicleForm} layout="vertical">
          <Form.Item label={t('task:vehicle.create.assetNo')} name="assetNo" rules={[{ required: true, message: t('task:vehicle.create.assetNoRequired') }]}>
            <Input placeholder="VEH-0001" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.model')} name="model" rules={[{ required: true, message: t('task:vehicle.create.modelRequired') }]}>
            <Select
              showSearch
              placeholder={t('task:vehicle.create.modelPlaceholder')}
              optionFilterProp="label"
              options={rows.map((r) => ({ label: `${r.code} / ${r.nameZh || ''}`, value: r.code }))}
            />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.qrCode')} name="qrCode">
            <Input placeholder="https://…/qr.png" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.vin')} name="vin">
            <Input placeholder="VIN" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.frameNo')} name="frameNo">
            <Input placeholder="车架号" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.motorNo')} name="motorNo">
            <Input placeholder="电机号" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.ownerId')} name="ownerId">
            <InputNumber style={{ width: '100%' }} placeholder={t('task:vehicle.create.ownerIdPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
