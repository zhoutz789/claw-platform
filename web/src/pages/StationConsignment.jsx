import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Descriptions, Modal, Select, Space, Table,
} from 'antd';
import { ReloadOutlined, FileSearchOutlined, EyeOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import {
  EnumTag, EMPTY, fmtTime, useSupplyOptions,
} from '../components/supplyShared';
import { getCertificateByDevice, getInventoryByDevice, listInventory } from '../api/supplyChain';
import { DEVICE_LIFECYCLE_LABEL } from '../enums';

/**
 * 服务站寄售库存页（增量 B · R1/R2/B2）。
 *
 * 服务站只持有「寄售占有权」，货权始终归厂家（不垫资、不持货权，只赚提成）。
 * 数据范围：用户仅可见所属 / 附近服务站库存（R2 / Q7），本页按服务站过滤查询。
 * 对接后端 AdminInventoryController：GET /admin/inventory?stationId= 与 GET /admin/inventory/device/{deviceId}。
 */
export default function StationConsignment() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const { stationOptions, manufacturerName, productName } = useSupplyOptions();

  const [stationId, setStationId] = useState(undefined);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  // 占有权详情弹窗
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState(null);
  // 合格证弹窗
  const [certOpen, setCertOpen] = useState(false);
  const [cert, setCert] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listInventory({ stationId, ownershipType: 'CONSIGNED' });
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [stationId, message, t]);

  useEffect(() => { load(); }, [load]);

  /** 占有权详情：GET /admin/inventory/device/{deviceId}，用于确认该设备的货权与占有归属。 */
  const openDetail = async (deviceId) => {
    if (deviceId == null) {
      message.warning(t('supply:production.msg.inputDeviceId'));
      return;
    }
    setDetail(null);
    setDetailOpen(true);
    try {
      const d = await getInventoryByDevice(deviceId);
      setDetail(d);
    } catch (e) {
      message.error(t('msg.detailLoadFailed', { msg: e.message }));
    }
  };

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
    { title: t('supply:stationConsignment.col.id'), dataIndex: 'id', width: 90 },
    { title: t('supply:stationConsignment.col.assetId'), dataIndex: 'assetId', width: 100 },
    { title: t('supply:stationConsignment.col.deviceId'), dataIndex: 'deviceId', width: 100 },
    {
      title: t('supply:stationConsignment.col.serialNumber'),
      dataIndex: 'serialNumber',
      width: 160,
      render: (v) => v || EMPTY,
    },
    {
      title: t('supply:stationConsignment.col.productId'),
      dataIndex: 'productId',
      width: 160,
      render: (v) => (v == null ? EMPTY : `${productName(v)} (#${v})`),
    },
    {
      title: t('supply:stationConsignment.col.ownerManufacturerId'),
      dataIndex: 'ownerManufacturerId',
      width: 150,
      render: (v) => manufacturerName(v),
    },
    {
      title: t('supply:stationConsignment.col.currentStatus'),
      dataIndex: 'currentStatus',
      width: 150,
      render: (v) => <EnumTag value={v} labelMap={DEVICE_LIFECYCLE_LABEL} />,
    },
    {
      title: t('supply:stationConsignment.col.inboundAt'),
      dataIndex: 'inboundAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 220,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Perm code="station:consignment:view">
            <Button
              size="small"
              type="link"
              icon={<EyeOutlined />}
              onClick={() => openDetail(r.deviceId ?? r.assetId)}
            >
              {t('supply:stationConsignment.detail')}
            </Button>
          </Perm>
          <Perm any={['mfg:certificate:view', 'station:consignment:view']}>
            <Button
              size="small"
              type="link"
              icon={<FileSearchOutlined />}
              onClick={() => viewCert(r.deviceId ?? r.assetId)}
            >
              {t('supply:stationConsignment.cert')}
            </Button>
          </Perm>
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('supply:stationConsignment.title')}
      subtitle={t('supply:stationConsignment.subtitle')}
      extra={
        <Space>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('supply:common.selectStation')}
            style={{ width: 220 }}
            options={stationOptions}
            value={stationId}
            onChange={setStationId}
          />
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
        </Space>
      }
    >
      {/* 后端本轮未提供收货接口，此处显式提示，避免用户误以为页面漏做功能 */}
      <Perm code="station:consignment:receive">
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('supply:stationConsignment.receiveTodo')} />
      </Perm>

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
        title={t('supply:stationConsignment.detail')}
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        footer={null}
        width={640}
      >
        {detail ? (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('supply:stationConsignment.col.id')}>{detail.id ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:stationConsignment.col.deviceId')}>{detail.deviceId ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:stationConsignment.col.serialNumber')}>{detail.serialNumber || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:mfgInventory.col.ownershipType')}>{detail.ownershipType || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:stationConsignment.col.ownerManufacturerId')}>{manufacturerName(detail.ownerManufacturerId)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:mfgInventory.col.holderStationId')}>{detail.holderStationId ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:mfgInventory.col.currentStatus')}>{detail.currentStatus || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:mfgInventory.col.inboundAt')}>{fmtTime(detail.inboundAt)}</Descriptions.Item>
            <Descriptions.Item label="custodyId">{detail.custodyId ?? EMPTY}</Descriptions.Item>
          </Descriptions>
        ) : (
          <div style={{ color: '#999' }}>{t('msg.loading')}</div>
        )}
      </Modal>

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
