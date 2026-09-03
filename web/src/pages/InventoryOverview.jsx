import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Alert, App, Button, Card, Col, Collapse, Empty, Progress, Row, Space, Statistic, Table, Tabs, Tag,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { EnumTag, EMPTY, fmtTime, useSupplyOptions } from '../components/supplyShared';
import { ScopeBanner, useInventoryColumns } from '../components/inventoryShared';
import { listMyInventory, getInventoryStats } from '../api/supplyChain';
import { DEVICE_LIFECYCLE_LABEL, OWNERSHIP_TYPE_LABEL } from '../enums';

/**
 * 库存总览页（模块三 · M3-1/2/3/4，供应流通组首位默认入口）。
 *
 * 三个角色感知 Tab：
 *   - 现有库存（current）：厂家/平台 = OWNED_BY_MFG；服务站 = CONSIGNED；
 *   - 服务站库存（subordinateStations）：按 holderStationId 分组的寄售库存（站点视角隐藏此 Tab）；
 *   - 统计报表（stats）：仅平台管理员可见（后端二次收窄 + 前端 Perm 兜底）。
 *
 * 作用域完全由后端 /me 解析，前端零手选出数；NONE（未绑定主体）显示引导，不白屏。
 */
export default function InventoryOverview() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const { manufacturerName, stationName } = useSupplyOptions();

  const [view, setView] = useState(null);
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('current');
  const [activeStations, setActiveStations] = useState([]);
  const [stationRowsMap, setStationRowsMap] = useState({});

  const [stats, setStats] = useState(null);
  const [statsLoading, setStatsLoading] = useState(false);

  const currentColumns = useInventoryColumns({ variant: 'current', options: { manufacturerName, stationName } });
  const stationColumns = useInventoryColumns({ variant: 'station', options: { manufacturerName, stationName } });

  const isPlatformAdmin = view?.scope?.platformAdmin === true;
  const isStation = view?.scope?.scopeLevel === 'STATION';
  const isNone = view?.scope?.scopeLevel === 'NONE';

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // 首屏 withRows=false：current 始终返回（受 limit 截断），服务站分组只给汇总（避免平台全量明细）。
      const first = await listMyInventory({ withRows: false });
      setView(first);
      const level = first?.scope?.scopeLevel;
      // 厂家/服务站：再拉一次带明细的分组，首屏即见明细（平台保持汇总，展开时懒加载）。
      if (level === 'MANUFACTURER' || level === 'STATION') {
        const full = await listMyInventory({ withRows: true });
        setView(full);
      }
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setView(null);
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => { load(); }, [load]);

  const loadStationRows = useCallback(async (sid) => {
    try {
      const d = await listMyInventory({ stationId: sid, withRows: true });
      const g = (d.subordinateStations || []).find((s) => s.stationId === sid);
      if (g) setStationRowsMap((m) => ({ ...m, [sid]: g.rows || [] }));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
    }
  }, [message, t]);

  const onStationExpand = (keys) => {
    setActiveStations(keys);
    if (isPlatformAdmin) {
      keys.forEach((k) => {
        const sid = Number(k);
        const g = (view?.subordinateStations || []).find((s) => s.stationId === sid);
        if (g && (!g.rows || g.rows.length === 0) && !stationRowsMap[sid]) {
          loadStationRows(sid);
        }
      });
    }
  };

  const loadStats = useCallback(async () => {
    if (stats) return;
    setStatsLoading(true);
    try {
      setStats(await getInventoryStats({}));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
    } finally {
      setStatsLoading(false);
    }
  }, [stats, message, t]);

  const onTabChange = (key) => {
    setActiveTab(key);
    if (key === 'stats' && isPlatformAdmin) loadStats();
  };

  // ---------------- Tab1：现有库存 ----------------
  const currentTotal = view?.currentTotal ?? 0;
  const currentTruncated = view?.currentTruncated === true;
  const currentTable = (
    <>
      {currentTruncated && (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message={t('supply:inventoryOverview.truncated', { limit: 500 })} />
      )}
      <Table
        rowKey="id"
        loading={loading}
        dataSource={view?.current || []}
        columns={currentColumns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        locale={{ emptyText: <Empty description={isNone ? t('supply:inventoryOverview.banner.none') : t('supply:inventoryOverview.empty')} /> }}
      />
    </>
  );

  // ---------------- Tab2：服务站库存 ----------------
  const stationGroups = view?.subordinateStations || [];
  const stationCollapse = (
    <Collapse accordion activeKey={activeStations} onChange={onStationExpand}>
      {stationGroups.length === 0 && (
        <Empty style={{ padding: 24 }} description={isNone ? t('supply:inventoryOverview.banner.none') : t('supply:inventoryOverview.station.empty')} />
      )}
      {stationGroups.map((g) => {
        const rows = stationRowsMap[g.stationId] || g.rows || [];
        const header = (
          <Space size="middle" wrap>
            <span>{stationName(g.stationId)}</span>
            <Tag color="blue">{t('supply:inventoryOverview.station.total', { count: g.total })}</Tag>
            {(g.byStatus || []).map((b) => (
              <Tag key={b.status}>
                <EnumTag value={b.status} labelMap={DEVICE_LIFECYCLE_LABEL} />
                {' '}×{b.count}
              </Tag>
            ))}
          </Space>
        );
        return (
          <Collapse.Panel key={g.stationId} header={header}>
            {g.truncated && (
              <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                message={t('supply:inventoryOverview.truncated', { limit: 500 })} />
            )}
            <Table
              rowKey="id"
              dataSource={rows}
              columns={stationColumns}
              size="middle"
              scroll={{ x: 'max-content' }}
              pagination={{ pageSize: 10, showSizeChanger: true }}
              locale={{ emptyText: <Empty description={t('supply:inventoryOverview.station.loadingRows')} /> }}
            />
          </Collapse.Panel>
        );
      })}
    </Collapse>
  );

  // ---------------- Tab3：统计报表（仅平台管理员） ----------------
  const statsView = (
    <div>
      {!stats && !statsLoading && (
        <Alert type="info" showIcon message={t('supply:inventoryOverview.stats.empty')} />
      )}
      <Row gutter={[16, 16]} style={{ marginTop: 12 }}>
        <Col xs={12} sm={12} md={6}>
          <Card><Statistic title={t('supply:inventoryOverview.stats.total')} value={stats?.total ?? 0} /></Card>
        </Col>
        <Col xs={12} sm={12} md={6}>
          <Card><Statistic title={t('supply:inventoryOverview.stats.ownedByMfg')} value={stats?.ownedByMfgTotal ?? 0} /></Card>
        </Col>
        <Col xs={12} sm={12} md={6}>
          <Card><Statistic title={t('supply:inventoryOverview.stats.consigned')} value={stats?.consignedTotal ?? 0} /></Card>
        </Col>
        <Col xs={12} sm={12} md={6}>
          <Card><Statistic title={t('supply:inventoryOverview.stats.atFactory')} value={stats?.atFactoryCount ?? 0} /></Card>
        </Col>
      </Row>
      <Card style={{ marginTop: 16 }}>
        <div style={{ marginBottom: 8 }}>{t('supply:inventoryOverview.stats.consignedRatio')}</div>
        <Progress
          percent={Math.round((stats?.consignedRatio ?? 0) * 10000) / 100}
          format={(p) => `${p}%`}
        />
      </Card>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} md={8}>
          <Card title={t('supply:inventoryOverview.stats.byStatus')} size="small">
            <Table
              rowKey="status"
              dataSource={stats?.byStatus || []}
              pagination={false}
              size="small"
              columns={[
                {
                  title: t('supply:inventoryOverview.col.currentStatus'),
                  dataIndex: 'status',
                  render: (v) => <EnumTag value={v} labelMap={DEVICE_LIFECYCLE_LABEL} />,
                },
                { title: t('supply:inventoryOverview.col.count'), dataIndex: 'count', width: 100 },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card title={t('supply:inventoryOverview.stats.byStation')} size="small">
            <Table
              rowKey="stationId"
              dataSource={stats?.byStation || []}
              pagination={false}
              size="small"
              columns={[
                {
                  title: t('supply:inventoryOverview.col.holderStationId'),
                  dataIndex: 'stationId',
                  render: (v) => (v == null ? EMPTY : stationName(v)),
                },
                { title: t('supply:inventoryOverview.col.count'), dataIndex: 'count', width: 100 },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card title={t('supply:inventoryOverview.stats.byManufacturer')} size="small">
            <Table
              rowKey="manufacturerId"
              dataSource={stats?.byManufacturer || []}
              pagination={false}
              size="small"
              columns={[
                {
                  title: t('supply:inventoryOverview.col.ownerManufacturerId'),
                  dataIndex: 'manufacturerId',
                  render: (v) => (v == null ? EMPTY : manufacturerName(v)),
                },
                { title: t('supply:inventoryOverview.col.count'), dataIndex: 'count', width: 100 },
              ]}
            />
          </Card>
        </Col>
      </Row>
      {stats?.fullTotal != null && (
        <Card style={{ marginTop: 16 }} size="small">
          {t('supply:inventoryOverview.stats.fullTotal', {
            full: stats.fullTotal, station: stats.stationCount ?? 0, mfg: stats.manufacturerCount ?? 0,
          })}
        </Card>
      )}
    </div>
  );

  const tabs = [
    { key: 'current', label: t('supply:inventoryOverview.tab.current'), children: currentTable },
    ...(isStation ? [] : [{ key: 'station', label: t('supply:inventoryOverview.tab.station'), children: stationCollapse }]),
    ...(isPlatformAdmin ? [{ key: 'stats', label: t('supply:inventoryOverview.tab.stats'), children: statsView }] : []),
  ];

  return (
    <PageCard
      title={t('supply:inventoryOverview.title')}
      subtitle={t('supply:inventoryOverview.subtitle')}
      extra={
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
          {t('action.refresh')}
        </Button>
      }
    >
      <ScopeBanner scope={view?.scope} manufacturerName={manufacturerName} stationName={stationName} />
      {view == null && loading ? (
        <div style={{ padding: 48, textAlign: 'center', color: '#999' }}>{t('msg.loading')}</div>
      ) : (
        <Tabs activeKey={activeTab} onChange={onTabChange} items={tabs} />
      )}
    </PageCard>
  );
}
