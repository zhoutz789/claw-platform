import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Descriptions, Modal, Select, Space, Table, Tabs,
} from 'antd';
import { ReloadOutlined, FileSearchOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import {
  EnumTag, EMPTY, fmtTime, useSupplyOptions,
} from '../components/supplyShared';
import {
  getCertificateByDevice, listInventory, listMyInventory,
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
 * 寄售入库改由服务站侧自主发起（POST /api/v1/station/consignment/inbound），
 * 本页为厂家库存只读视图：不再提供「发货到站」，原 /admin/inventory/ship 已由后端下线。
 * 对接后端 AdminInventoryController（/api/v1/admin/inventory）。
 */
export default function MfgInventory() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const { manufacturerOptions, productName, stationName } = useSupplyOptions();

  const [tab, setTab] = useState('OWNED_BY_MFG');
  const [manufacturerId, setManufacturerId] = useState(undefined);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  // 模块三 · 自动作用域：未手选厂家时，走 /me 取当前绑定厂家的双视图（零手选出数）。
  const [autoView, setAutoView] = useState(null);

  // 合格证弹窗
  const [certOpen, setCertOpen] = useState(false);
  const [cert, setCert] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      if (manufacturerId != null) {
        // 手选厂家：回落既有 listInventory（增强筛选）
        const d = await listInventory({ manufacturerId, ownershipType: tab });
        setRows(Array.isArray(d) ? d : []);
      } else {
        // 未手选：自动作用域，取当前绑定厂家的双视图（模块三 · M3-2）
        const v = await listMyInventory({ withRows: true });
        setAutoView(v);
        // 现有库存 Tab → view.current（OWNED_BY_MFG）；寄售库存 Tab → 下属服务站扁平化
        setRows(tab === 'CONSIGNED'
          ? (v.subordinateStations || []).flatMap((g) => g.rows || [])
          : (v.current || []));
      }
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [manufacturerId, tab, message, t]);

  useEffect(() => { load(); }, [load]);

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
      width: 130,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
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
      {/* 寄售入库改由服务站侧发起，本页只保留只读视图，避免厂家越权操作他人数据 */}
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="寄售入库由服务站自主发起，厂家不再分拨到站。本页为厂家库存只读视图；需要按站点查看分布请前往『库存总览』。"
      />

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={OWNERSHIP_TYPE.filter((o) => o.value !== 'FULL').map((o) => ({
          key: o.value,
          label: t(`supply:enum.ownership.${o.value}`),
          children: table,
        }))}
      />

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
