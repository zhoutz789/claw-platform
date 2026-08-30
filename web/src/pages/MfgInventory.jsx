import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Button, Descriptions, Form, InputNumber, Modal, Select, Space, Table, Tabs,
} from 'antd';
import { ReloadOutlined, SendOutlined, FileSearchOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import {
  EnumTag, EMPTY, fmtTime, useSupplyOptions,
} from '../components/supplyShared';
import {
  getCertificateByDevice, listInventory, shipToStation,
} from '../api/supplyChain';
import {
  DEVICE_LIFECYCLE_LABEL, OWNERSHIP_TYPE, OWNERSHIP_TYPE_LABEL,
} from '../enums';

/**
 * 厂家库存页（增量 B · R1/R2/B2）。
 *
 * 双视图：
 *   - 自有库存 OWNED_BY_MFG：货权与占有权均在厂家；
 *   - 寄售库存 CONSIGNED：货权在厂家，占有权在下挂服务站（服务站仅寄售占有，不垫资）。
 * 「发货到站」调用 /admin/inventory/ship 建立寄售占有权，是 Q2 约定的占有权转移点。
 * 对接后端 AdminInventoryController（/api/v1/admin/inventory）。
 */
export default function MfgInventory() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const { manufacturerOptions, stationOptions, productName, stationName } = useSupplyOptions();

  const [tab, setTab] = useState('OWNED_BY_MFG');
  const [manufacturerId, setManufacturerId] = useState(undefined);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  // 发货到站弹窗
  const [shipOpen, setShipOpen] = useState(false);
  const [shipRow, setShipRow] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [shipForm] = Form.useForm();

  // 合格证弹窗
  const [certOpen, setCertOpen] = useState(false);
  const [cert, setCert] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listInventory({ manufacturerId, ownershipType: tab });
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [manufacturerId, tab, message, t]);

  useEffect(() => { load(); }, [load]);

  const openShip = (record) => {
    setShipRow(record);
    setShipOpen(true);
    shipForm.resetFields();
    shipForm.setFieldsValue({
      deviceId: record.deviceId ?? record.assetId ?? null,
      manufacturerId: record.ownerManufacturerId ?? manufacturerId ?? null,
      stationId: record.holderStationId ?? null,
    });
  };

  /** 发货到站：建立寄售占有权（货权仍归厂家）。 */
  const submitShip = async () => {
    const v = await shipForm.validateFields();
    setSubmitting(true);
    try {
      await shipToStation({
        deviceId: v.deviceId,
        stationId: v.stationId,
        manufacturerId: v.manufacturerId,
      });
      message.success(t('supply:mfgInventory.msg.shipped'));
      setShipOpen(false);
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  /** 查看合格证（生成即写库不可事后补，查不到即无）。 */
  const viewCert = async (deviceId) => {
    if (deviceId == null) {
      message.warning(t('supply:production.msg.inputDeviceId'));
      return;
    }
    setCert(null);
    setCertOpen(true);
    try {
      const d = await getCertificateByDevice(deviceId);
      setCert(d);
    } catch (e) {
      message.error(`${t('supply:production.cert.notFound')}（${e.message}）`);
    }
  };

  const columns = [
    { title: t('supply:mfgInventory.col.id'), dataIndex: 'id', width: 90 },
    { title: t('supply:mfgInventory.col.assetId'), dataIndex: 'assetId', width: 100 },
    { title: t('supply:mfgInventory.col.deviceId'), dataIndex: 'deviceId', width: 100 },
    {
      title: t('supply:mfgInventory.col.serialNumber'),
      dataIndex: 'serialNumber',
      width: 160,
      render: (v) => v || EMPTY,
    },
    {
      title: t('supply:mfgInventory.col.productId'),
      dataIndex: 'productId',
      width: 160,
      render: (v) => (v == null ? EMPTY : `${productName(v)} (#${v})`),
    },
    {
      title: t('supply:mfgInventory.col.ownershipType'),
      dataIndex: 'ownershipType',
      width: 130,
      render: (v) => <EnumTag value={v} labelMap={OWNERSHIP_TYPE_LABEL} />,
    },
    {
      title: t('supply:mfgInventory.col.currentStatus'),
      dataIndex: 'currentStatus',
      width: 140,
      render: (v) => <EnumTag value={v} labelMap={DEVICE_LIFECYCLE_LABEL} />,
    },
    {
      title: t('supply:mfgInventory.col.holderStationId'),
      dataIndex: 'holderStationId',
      width: 160,
      render: (v) => (v == null ? EMPTY : stationName(v)),
    },
    {
      title: t('supply:mfgInventory.col.inboundAt'),
      dataIndex: 'inboundAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 200,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Perm code="mfg:transfer:create">
            <Button size="small" type="link" icon={<SendOutlined />} onClick={() => openShip(r)}>
              {t('supply:mfgInventory.ship')}
            </Button>
          </Perm>
          <Perm code="mfg:certificate:view">
            <Button
              size="small"
              type="link"
              icon={<FileSearchOutlined />}
              onClick={() => viewCert(r.deviceId ?? r.assetId)}
            >
              {t('supply:mfgInventory.viewCert')}
            </Button>
          </Perm>
        </Space>
      ),
    },
  ];

  const table = (
    <Table
      rowKey="id"
      loading={loading}
      dataSource={rows}
      columns={columns}
      size="middle"
      scroll={{ x: 'max-content' }}
      pagination={{ pageSize: 10, showSizeChanger: true }}
    />
  );

  return (
    <PageCard
      title={t('supply:mfgInventory.title')}
      subtitle={t('supply:mfgInventory.subtitle')}
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
        </Space>
      }
    >
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={OWNERSHIP_TYPE.filter((o) => o.value !== 'FULL').map((o) => ({
          key: o.value,
          label: t(`supply:enum.ownership.${o.value}`),
          children: table,
        }))}
      />

      {/* 发货到站：建立寄售占有权 */}
      <Modal
        title={t('supply:mfgInventory.ship')}
        open={shipOpen}
        onOk={submitShip}
        confirmLoading={submitting}
        onCancel={() => setShipOpen(false)}
        destroyOnClose
        width={520}
      >
        <Form form={shipForm} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="deviceId"
            label={t('supply:mfgInventory.field.deviceId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:mfgInventory.field.deviceId') }) }]}
          >
            <InputNumber style={{ width: '100%' }} disabled={shipRow?.deviceId != null} />
          </Form.Item>
          <Form.Item
            name="manufacturerId"
            label={t('supply:mfgInventory.field.manufacturerId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:mfgInventory.field.manufacturerId') }) }]}
          >
            <Select showSearch optionFilterProp="label" allowClear options={manufacturerOptions} />
          </Form.Item>
          <Form.Item
            name="stationId"
            label={t('supply:mfgInventory.field.stationId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:mfgInventory.field.stationId') }) }]}
          >
            <Select showSearch optionFilterProp="label" allowClear options={stationOptions} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 合格证 */}
      <Modal
        title={t('supply:production.cert.title')}
        open={certOpen}
        onCancel={() => setCertOpen(false)}
        footer={null}
        width={640}
      >
        {cert ? (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('supply:production.cert.certNo')}>{cert.certNo || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:production.cert.deviceId')}>{cert.deviceId ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:production.cert.issuedAt')}>{fmtTime(cert.issuedAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:production.cert.issuedBy')}>{cert.issuedBy ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:production.cert.specJson')}>
              <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12 }}>
                {cert.specJson || EMPTY}
              </pre>
            </Descriptions.Item>
          </Descriptions>
        ) : (
          <div style={{ color: '#999' }}>{t('supply:production.cert.notFound')}</div>
        )}
      </Modal>
    </PageCard>
  );
}
