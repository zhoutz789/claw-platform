import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Alert, App, Button, Card, Col, DatePicker, Descriptions, Empty, Row, Segmented, Space, Spin, Statistic, Table, Tag,
} from 'antd';
import { LineChartOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import PageCard from '../components/PageCard';
import api from '../api';

/**
 * 光伏电站监控 / PR 曲线页（模块：光伏追溯/监控）。
 *
 * 数据来源（均经 api 拦截器解包到 body.data）：
 *   - GET /v1/pv/stations                      → List<PvStation>            静态档案（含 ratedPowerWp 作 PR 分母）
 *   - GET /v1/telemetry/{assetId}              → Telemetry|null            实时快照（soh/soc/temp/lat/lng/speedKph/updatedAt）
 *   - GET /v1/pv/trace/station/{assetId}/yield → StationYieldView          区间发电量 + PR
 *
 * 防御式渲染：遥测快照可能缺失、可能不含功率字段，PR 在缺少铭牌容量/辐照度时恒为 null（不编造）。
 * 任意接口失败均不崩溃：列表失败显示错误 Alert + 重试；遥测失败逐站吞掉；PR 失败在面板内提示。
 */

// ----------------------------- 工具函数 -----------------------------

/** 安全转数字：null/空/NaN → null，其余 → Number。BigDecimal 经 JSON 多为 number，少数场景为字符串。 */
const num = (v) => (v == null || v === '' || Number.isNaN(Number(v)) ? null : Number(v));

/** 格式化功率 W → W/kW/MW。 */
const fmtPowerW = (w) => {
  const n = num(w);
  if (n == null) return '—';
  const abs = Math.abs(n);
  if (abs >= 1e6) return `${(n / 1e6).toFixed(2)} MW`;
  if (abs >= 1e3) return `${(n / 1e3).toFixed(2)} kW`;
  return `${n.toFixed(0)} W`;
};

/** 格式化电量 Wh → Wh/kWh/MWh/GWh。 */
const fmtEnergyWh = (wh) => {
  const n = num(wh);
  if (n == null) return '—';
  const abs = Math.abs(n);
  if (abs >= 1e9) return `${(n / 1e9).toFixed(2)} GWh`;
  if (abs >= 1e6) return `${(n / 1e6).toFixed(2)} MWh`;
  if (abs >= 1e3) return `${(n / 1e3).toFixed(2)} kWh`;
  return `${n.toFixed(0)} Wh`;
};

/**
 * 格式化性能比 PR：后端常返回比值（0.82）也可能已是百分比（82）。
 * 防御式启发：≤1.5 视为比值按百分比展开，否则视为已是百分比原样展示。
 */
const fmtPr = (pr) => {
  const n = num(pr);
  if (n == null) return '—';
  const pct = n <= 1.5 ? n * 100 : n;
  return `${pct.toFixed(1)}%`;
};

/** 从遥测快照中防御式提取实时功率：兼容多种可能字段名。 */
const readPower = (tel) => {
  if (!tel) return null;
  return num(tel.acActivePowerW) ?? num(tel.meterActivePowerW) ?? num(tel.powerW) ?? null;
};

/** 日期区间预设。 */
const RANGE_PRESETS = [
  { label: '近7天', value: [dayjs().subtract(6, 'day'), dayjs()] },
  { label: '近30天', value: [dayjs().subtract(29, 'day'), dayjs()] },
  { label: '近90天', value: [dayjs().subtract(89, 'day'), dayjs()] },
];

const DEFAULT_RANGE = () => [dayjs().subtract(29, 'day'), dayjs()];

// ----------------------------- 单站卡片 -----------------------------

function StationCard({ station, telemetry, selected, onView }) {
  const power = readPower(telemetry);
  const online = power != null;
  const soh = telemetry ? num(telemetry.soh) : null;
  const soc = telemetry ? num(telemetry.soc) : null;

  return (
    <Card
      size="small"
      style={{
        height: '100%',
        ...(selected
          ? { borderColor: '#1677ff', boxShadow: '0 0 0 2px rgba(22,119,255,0.2)' }
          : {}),
      }}
      title={(
        <Space size={8}>
          <Tag color={online ? 'green' : 'default'}>{online ? '在线' : '离线'}</Tag>
          <span>资产 {station.assetId}</span>
        </Space>
      )}
      extra={online ? <span style={{ color: '#1677ff', fontWeight: 600 }}>{fmtPowerW(power)}</span> : null}
    >
      <Descriptions column={1} size="small" bordered={false} colon={false}>
        <Descriptions.Item label="并网编号">{station.gridConnectionNo || '—'}</Descriptions.Item>
        <Descriptions.Item label="装机容量">{fmtPowerW(station.ratedPowerWp)}</Descriptions.Item>
        <Descriptions.Item label="组件数">{station.moduleCount ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="倾角/方位">
          {station.tiltDeg != null ? `${num(station.tiltDeg)}°` : '—'}
          {' / '}
          {station.azimuthDeg != null ? `${num(station.azimuthDeg)}°` : '—'}
        </Descriptions.Item>
        {soh != null && <Descriptions.Item label="SOH">{`${soh}%`}</Descriptions.Item>}
        {soc != null && <Descriptions.Item label="SOC">{`${soc}%`}</Descriptions.Item>}
      </Descriptions>
      <Button
        type={selected ? 'primary' : 'default'}
        block
        icon={<LineChartOutlined />}
        style={{ marginTop: 8 }}
        onClick={() => onView(station.assetId)}
      >
        查看 PR
      </Button>
    </Card>
  );
}

// ----------------------------- 主页面 -----------------------------

export default function PvStation() {
  const { t } = useTranslation(['nav', 'common']);
  const { message } = App.useApp();

  const [stations, setStations] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const [telemetryMap, setTelemetryMap] = useState({});
  const [telemetryLoading, setTelemetryLoading] = useState(false);

  const [selectedAssetId, setSelectedAssetId] = useState(null);
  const [yieldData, setYieldData] = useState(null);
  const [yieldLoading, setYieldLoading] = useState(false);
  const [yieldError, setYieldError] = useState(null);
  const [bucketView, setBucketView] = useState('daily');
  const [dateRange, setDateRange] = useState(DEFAULT_RANGE);

  // 逐站拉取实时遥测快照（防御式：单站失败不影响整体）。
  const loadTelemetry = useCallback(async (arr) => {
    if (!arr || arr.length === 0) {
      setTelemetryMap({});
      return;
    }
    setTelemetryLoading(true);
    try {
      const results = await Promise.allSettled(
        arr.map((s) => api.get(`/v1/telemetry/${s.assetId}`)),
      );
      const map = {};
      results.forEach((r, i) => {
        if (r.status === 'fulfilled' && r.value) {
          map[arr[i].assetId] = r.value;
        }
      });
      setTelemetryMap(map);
    } catch (e) {
      // 整体异常也不阻断页面，保持空快照。
      setTelemetryMap({});
    } finally {
      setTelemetryLoading(false);
    }
  }, []);

  // 加载电站列表 + 触发遥测快照拉取。
  const loadStations = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await api.get('/v1/pv/stations');
      const arr = Array.isArray(list) ? list : [];
      setStations(arr);
      await loadTelemetry(arr);
    } catch (e) {
      setError(e?.message || '加载光伏电站失败');
      setStations([]);
      setTelemetryMap({});
    } finally {
      setLoading(false);
    }
  }, [loadTelemetry]);

  // 加载选中电站的发电量与 PR（默认近 30 天，支持区间）。
  const loadYield = useCallback(async (assetId, range) => {
    if (assetId == null) return;
    const r = range || dateRange;
    const [from, to] = r;
    const fromStr = dayjs(from).format('YYYY-MM-DD');
    const toStr = dayjs(to).format('YYYY-MM-DD');
    setYieldLoading(true);
    setYieldError(null);
    try {
      const data = await api.get(`/v1/pv/trace/station/${assetId}/yield`, {
        params: { from: fromStr, to: toStr },
      });
      setYieldData(data || null);
    } catch (e) {
      setYieldError(e?.message || '加载发电量/PR 失败');
      setYieldData(null);
      message.error(`PR 加载失败：${e?.message || '未知错误'}`);
    } finally {
      setYieldLoading(false);
    }
  }, [dateRange, message]);

  // 选中电站并加载其 PR。
  const selectStation = useCallback((assetId) => {
    setSelectedAssetId(assetId);
    setYieldData(null);
    setYieldError(null);
    loadYield(assetId, dateRange);
  }, [dateRange, loadYield]);

  // 区间变化：若已选电站则重新拉取。
  const onRangeChange = (range) => {
    const next = range && range.length === 2 ? range : DEFAULT_RANGE();
    setDateRange(next);
    if (selectedAssetId != null) loadYield(selectedAssetId, next);
  };

  useEffect(() => { loadStations(); }, [loadStations]);

  // 汇总指标。
  const summary = useMemo(() => {
    let rated = 0;
    let online = 0;
    stations.forEach((s) => {
      const rp = num(s.ratedPowerWp);
      if (rp != null) rated += rp;
      if (readPower(telemetryMap[s.assetId]) != null) online += 1;
    });
    return { total: stations.length, rated, online };
  }, [stations, telemetryMap]);

  const selectedStation = useMemo(
    () => stations.find((s) => s.assetId === selectedAssetId) || null,
    [stations, selectedAssetId],
  );

  const yieldBuckets = useMemo(() => {
    if (!yieldData) return [];
    return bucketView === 'daily'
      ? (yieldData.daily || [])
      : (yieldData.monthly || []);
  }, [yieldData, bucketView]);

  const bucketColumns = [
    { title: '周期', dataIndex: 'period', key: 'period', width: 140 },
    { title: '逆变器电量', dataIndex: 'inverterWh', key: 'inverterWh', render: (v) => fmtEnergyWh(v) },
    { title: '电表电量', dataIndex: 'meterWh', key: 'meterWh', render: (v) => fmtEnergyWh(v) },
    {
      title: '峰值日照时数',
      dataIndex: 'peakSunHours',
      key: 'peakSunHours',
      render: (v) => (v == null ? '—' : `${num(v).toFixed(2)} h`),
    },
    { title: 'PR', dataIndex: 'pr', key: 'pr', render: (v) => fmtPr(v) },
    {
      title: '辐照度缺口',
      dataIndex: 'irradianceGap',
      key: 'irradianceGap',
      render: (v) => (v
        ? <Tag color="orange">有</Tag>
        : <Tag color="green">无</Tag>),
    },
  ];

  // PR 面板。
  const prPanel = (
    <Card
      title={selectedStation ? `电站 PR 详情 · 资产 ${selectedStation.assetId}` : '电站 PR 详情'}
      style={{ marginTop: 16 }}
      extra={(
        <Space wrap>
          <DatePicker.RangePicker
            allowClear={false}
            value={dateRange}
            presets={RANGE_PRESETS}
            disabled={selectedAssetId == null}
            onChange={onRangeChange}
          />
          <Button
            icon={<ReloadOutlined />}
            loading={yieldLoading}
            disabled={selectedAssetId == null}
            onClick={() => loadYield(selectedAssetId, dateRange)}
          >
            刷新
          </Button>
        </Space>
      )}
    >
      {selectedAssetId == null ? (
        <Empty description="请选择一个电站查看 PR" style={{ padding: 32 }} />
      ) : yieldError ? (
        <Alert
          type="error"
          showIcon
          message="加载发电量/PR 失败"
          description={yieldError}
          action={<Button onClick={() => loadYield(selectedAssetId, dateRange)}>重试</Button>}
        />
      ) : (
        <Spin spinning={yieldLoading}>
          <Row gutter={[16, 16]}>
            <Col xs={12} md={6}>
              <Card size="small">
                <Statistic
                  title="性能比 PR"
                  value={fmtPr(yieldData?.pr)}
                  valueStyle={{ color: yieldData?.pr != null ? '#1677ff' : undefined }}
                />
              </Card>
            </Col>
            <Col xs={12} md={6}>
              <Card size="small">
                <Statistic title="装机容量" value={fmtPowerW(yieldData?.ratedPowerWp)} />
              </Card>
            </Col>
            <Col xs={12} md={6}>
              <Card size="small">
                <Statistic title="逆变器总发电量" value={fmtEnergyWh(yieldData?.inverterTotalWh)} />
              </Card>
            </Col>
            <Col xs={12} md={6}>
              <Card size="small">
                <Statistic title="电表总发电量" value={fmtEnergyWh(yieldData?.meterTotalWh)} />
              </Card>
            </Col>
          </Row>

          {yieldData?.pr == null && (
            <Alert
              type="info"
              showIcon
              style={{ marginTop: 12 }}
              message="铭牌装机容量缺失或区间辐照度缺失，PR 无法计算（不编造）。"
            />
          )}

          <div style={{ marginTop: 16, marginBottom: 8 }}>
            <Segmented
              value={bucketView}
              onChange={setBucketView}
              options={[
                { label: '按日', value: 'daily' },
                { label: '按月', value: 'monthly' },
              ]}
            />
          </div>
          <Table
            rowKey="period"
            dataSource={yieldBuckets}
            columns={bucketColumns}
            size="small"
            pagination={{ pageSize: 10, showSizeChanger: true }}
            locale={{ emptyText: <Empty description="该区间暂无发电量数据" /> }}
          />
        </Spin>
      )}
    </Card>
  );

  return (
    <PageCard
      title={t('nav:item.pv-station')}
      reload={loadStations}
      loading={loading}
    >
      {error ? (
        <Alert
          type="error"
          showIcon
          message="加载光伏电站失败"
          description={error}
          action={<Button icon={<ReloadOutlined />} onClick={loadStations}>重试</Button>}
        />
      ) : (
        <>
          {/* 汇总指标行 */}
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} sm={8}>
              <Card><Statistic title="电站总数" value={summary.total} loading={loading} /></Card>
            </Col>
            <Col xs={24} sm={8}>
              <Card><Statistic title="总装机容量" value={fmtPowerW(summary.rated)} /></Card>
            </Col>
            <Col xs={24} sm={8}>
              <Card>
                <Statistic
                  title="在线电站"
                  value={summary.online}
                  suffix={`/ ${summary.total}`}
                  valueStyle={{ color: summary.online > 0 ? '#52c41a' : undefined }}
                />
              </Card>
            </Col>
          </Row>

          {/* 电站卡片网格 */}
          {!loading && stations.length === 0 ? (
            <Empty description="暂无光伏电站" style={{ padding: 48 }} />
          ) : (
            <Spin spinning={telemetryLoading}>
              <Row gutter={[16, 16]}>
                {stations.map((s) => (
                  <Col key={s.assetId} xs={24} sm={12} md={8} lg={6}>
                    <StationCard
                      station={s}
                      telemetry={telemetryMap[s.assetId]}
                      selected={selectedAssetId === s.assetId}
                      onView={selectStation}
                    />
                  </Col>
                ))}
              </Row>
            </Spin>
          )}

          {prPanel}
        </>
      )}
    </PageCard>
  );
}
