import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Empty,
  Input,
  Row,
  Space,
  Spin,
  Statistic,
  Table,
  Tabs,
  Tag,
} from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import api from '../api';

const { RangePicker } = DatePicker;

/** 缺值占位符（后端对"算不出"的指标统一返回 null，不编造）。 */
const EMPTY = '—';

/**
 * 格式化辅助：把后端可能为 null 的 BigDecimal / 数值安全转换为展示串。
 * 一律不要替 null 填 0，避免被误读为"实测量为零"。
 */
const fmtNum = (v, digits = 2) => (v == null ? EMPTY : Number(v).toFixed(digits));
const whToKwh = (v) => (v == null ? null : Number(v) / 1000);
const fmtKwh = (v, digits = 2) => {
  const k = whToKwh(v);
  return k == null ? EMPTY : `${k.toFixed(digits)}`;
};
const fmtPct = (v, digits = 2) => (v == null ? EMPTY : `${Number(v).toFixed(digits)}%`);
const fmtDate = (v) => (v == null ? EMPTY : String(v));

/**
 * 光伏追溯页（组件序列号 / 批次号 / 电站发电量三种溯源模式）。
 *
 * <p>三大只读端点（后端见 PvTraceController）：
 * <ul>
 *   <li>按组件序列号：GET /v1/pv/trace/module?serialNo= → ModuleTraceView</li>
 *   <li>按批次号：     GET /v1/pv/trace/batch?batchNo=   → BatchOverviewView</li>
 *   <li>按电站发电量： GET /v1/pv/trace/station/{assetId}/yield?from=&to= → StationYieldView</li>
 * </ul>
 *
 * <p>所有视图字段防御式渲染（部分可能为 null）。后端不可达/业务报错时显示错误 Alert + 重试，
 * 绝不因异常崩溃白屏。页面内中文文案内联，不新增 i18n key。
 */
export default function PvTrace() {
  const { t } = useTranslation(['nav', 'common']);
  const { message } = App.useApp();

  // 当前激活的溯源模式：module | batch | station
  const [mode, setMode] = useState('module');

  // ---- 模式1：组件序列号 ----
  const [moduleSerial, setModuleSerial] = useState('');
  const [moduleData, setModuleData] = useState(null);
  const [moduleLoading, setModuleLoading] = useState(false);
  const [moduleError, setModuleError] = useState(null);

  // ---- 模式2：批次号 ----
  const [batchNo, setBatchNo] = useState('');
  const [batchData, setBatchData] = useState(null);
  const [batchLoading, setBatchLoading] = useState(false);
  const [batchError, setBatchError] = useState(null);

  // ---- 模式3：电站发电量 ----
  const [stationAssetId, setStationAssetId] = useState('');
  const [stationRange, setStationRange] = useState([
    dayjs().subtract(29, 'day'),
    dayjs(),
  ]);
  const [stationData, setStationData] = useState(null);
  const [stationLoading, setStationLoading] = useState(false);
  const [stationError, setStationError] = useState(null);

  // ---------------- 查询动作 ----------------
  const loadModule = useCallback(async () => {
    const sn = (moduleSerial || '').trim();
    if (!sn) {
      message.warning('请输入组件序列号');
      return;
    }
    setModuleLoading(true);
    setModuleError(null);
    try {
      const d = await api.get('/v1/pv/trace/module', { params: { serialNo: sn } });
      setModuleData(d || null);
    } catch (e) {
      setModuleData(null);
      setModuleError(e?.message || '查询失败');
    } finally {
      setModuleLoading(false);
    }
  }, [moduleSerial, message]);

  const loadBatch = useCallback(async () => {
    const bn = (batchNo || '').trim();
    if (!bn) {
      message.warning('请输入批次号');
      return;    }
    setBatchLoading(true);
    setBatchError(null);
    try {
      const d = await api.get('/v1/pv/trace/batch', { params: { batchNo: bn } });
      setBatchData(d || null);
    } catch (e) {
      setBatchData(null);
      setBatchError(e?.message || '查询失败');
    } finally {
      setBatchLoading(false);
    }
  }, [batchNo, message]);

  const loadStation = useCallback(async () => {
    const aid = (stationAssetId || '').trim();
    if (!aid) {
      message.warning('请输入电站资产 ID');
      return;
    }
    if (!stationRange || stationRange.length < 2 || !stationRange[0] || !stationRange[1]) {
      message.warning('请选择查询日期范围');
      return;
    }
    const from = stationRange[0].format('YYYY-MM-DD');
    const to = stationRange[1].format('YYYY-MM-DD');
    setStationLoading(true);
    setStationError(null);
    try {
      const d = await api.get(
        `/v1/pv/trace/station/${encodeURIComponent(aid)}/yield`,
        { params: { from, to } },
      );
      setStationData(d || null);
    } catch (e) {
      setStationData(null);
      setStationError(e?.message || '查询失败');
    } finally {
      setStationLoading(false);
    }
  }, [stationAssetId, stationRange, message]);

  /** 当前模式对应的重新查询（供错误 Alert 的"重试"使用）。 */
  const retryCurrent = useCallback(() => {
    if (mode === 'module') loadModule();
    else if (mode === 'batch') loadBatch();
    else loadStation();
  }, [mode, loadModule, loadBatch, loadStation]);

  // ---------------- 结果区：统一骨架（loading / error / empty / data） ----------------
  const ResultShell = ({ loading, error, hasData, onRetry, children }) => {
    if (loading) {
      return (
        <div style={{ padding: 48, textAlign: 'center' }}>
          <Spin tip="查询中…" />
        </div>
      );
    }
    if (error) {
      return (
        <Alert
          type="error"
          showIcon
          message="查询失败"
          description={error}
          action={
            <Button icon={<ReloadOutlined />} size="small" onClick={onRetry}>
              重试
            </Button>
          }
          style={{ marginTop: 16 }}
        />
      );
    }
    if (!hasData) {
      return <Empty description="请输入查询条件后点击查询" style={{ padding: 48 }} />;
    }
    return <div style={{ marginTop: 16 }}>{children}</div>;
  };

  // ---------------- 组件溯源视图渲染 ----------------
  const renderModule = (v) => {
    if (!v) return null;
    return (
      <>
        <Descriptions
          title="组件档案"
          bordered
          column={{ xs: 1, sm: 2, md: 3 }}
          size="small"
        >
          <Descriptions.Item label="序列号">{fmtDate(v.serialNo)}</Descriptions.Item>
          <Descriptions.Item label="批次号">{fmtDate(v.batchNo)}</Descriptions.Item>
          <Descriptions.Item label="铭牌功率(pmax)">
            {v.pmaxW == null ? EMPTY : `${Number(v.pmaxW).toFixed(0)} W`}
          </Descriptions.Item>
          <Descriptions.Item label="制造商 ID">{v.manufacturerId == null ? EMPTY : v.manufacturerId}</Descriptions.Item>
          <Descriptions.Item label="产品 ID">{v.productId == null ? EMPTY : v.productId}</Descriptions.Item>
          <Descriptions.Item label="SKU ID">{v.skuId == null ? EMPTY : v.skuId}</Descriptions.Item>
          <Descriptions.Item label="电站资产 ID">{v.stationAssetId == null ? EMPTY : v.stationAssetId}</Descriptions.Item>
          <Descriptions.Item label="组串 ID">{fmtDate(v.stringId)}</Descriptions.Item>
          <Descriptions.Item label="组内位置">{v.position == null ? EMPTY : v.position}</Descriptions.Item>
          <Descriptions.Item label="逆变器设备号">{fmtDate(v.inverterDeviceNo)}</Descriptions.Item>
          <Descriptions.Item label="安装日期">{fmtDate(v.installedAt)}</Descriptions.Item>
          <Descriptions.Item label="衰减率">{fmtPct(v.degradationRate)}</Descriptions.Item>
          <Descriptions.Item label="认证 ID">{v.certificateId == null ? EMPTY : v.certificateId}</Descriptions.Item>
          <Descriptions.Item label="EL 图">
            {v.elImageUrl ? (
              <a href={v.elImageUrl} target="_blank" rel="noreferrer">查看</a>
            ) : EMPTY}
          </Descriptions.Item>
        </Descriptions>

        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          <Col xs={24} sm={12} md={8}>
            <Card>
              <Statistic
                title="累计发电量（逆变器）"
                value={whToKwh(v.inverterEnergyWh) == null ? EMPTY : whToKwh(v.inverterEnergyWh).toFixed(2)}
                suffix="kWh"
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Card>
              <Statistic
                title="估算累计衰减"
                value={v.estimatedDegradationPct == null ? EMPTY : Number(v.estimatedDegradationPct).toFixed(2)}
                suffix={v.estimatedDegradationPct == null ? '' : '%'}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Card>
              <Statistic
                title="质保年限"
                value={v.warrantyYears == null ? EMPTY : v.warrantyYears}
                suffix={v.warrantyYears == null ? '' : '年'}
              />
            </Card>
          </Col>
        </Row>
      </>
    );
  };

  // ---------------- 批次概览视图渲染 ----------------
  const batchColumns = [
    { title: '序列号', dataIndex: 'serialNo', render: (val) => fmtDate(val) },
    { title: '批次号', dataIndex: 'batchNo', render: (val) => fmtDate(val) },
    { title: '铭牌功率(pmax)', dataIndex: 'pmaxW', render: (val) => (val == null ? EMPTY : `${Number(val).toFixed(0)} W`) },
    { title: '电站资产 ID', dataIndex: 'stationAssetId', render: (val) => (val == null ? EMPTY : val) },
    { title: '组串 ID', dataIndex: 'stringId', render: (val) => fmtDate(val) },
    { title: '逆变器设备号', dataIndex: 'inverterDeviceNo', render: (val) => fmtDate(val) },
    { title: '累计发电量', dataIndex: 'inverterEnergyWh', render: (val) => fmtKwh(val) },
    { title: '估算衰减', dataIndex: 'estimatedDegradationPct', render: (val) => fmtPct(val) },
  ];

  const renderBatch = (v) => {
    if (!v) return null;
    return (
      <>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={8}>
            <Card>
              <Statistic title="批次号" value={fmtDate(v.batchNo)} />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Card>
              <Statistic title="组件数量" value={v.moduleCount == null ? 0 : v.moduleCount} />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Card>
              <Statistic
                title="批次性能比(PR)"
                value={v.pr == null ? EMPTY : Number(v.pr).toFixed(2)}
                suffix={v.pr == null ? '' : '%'}
              />
            </Card>
          </Col>
        </Row>
        <Card title="组件明细" size="small" style={{ marginTop: 16 }}>
          <Table
            rowKey="serialNo"
            dataSource={v.modules || []}
            columns={batchColumns}
            size="middle"
            scroll={{ x: 'max-content' }}
            pagination={{ pageSize: 10, showSizeChanger: true }}
            locale={{ emptyText: <Empty description="该批次暂无组件明细" /> }}
          />
        </Card>
      </>
    );
  };

  // ---------------- 电站发电量视图渲染 ----------------
  const yieldColumns = [
    { title: '周期', dataIndex: 'period', render: (val) => fmtDate(val) },
    { title: '逆变器电量(Wh)', dataIndex: 'inverterWh', render: (val) => fmtNum(val) },
    { title: '电表电量(Wh)', dataIndex: 'meterWh', render: (val) => fmtNum(val) },
    { title: '峰值日照(h)', dataIndex: 'peakSunHours', render: (val) => fmtNum(val) },
    {
      title: 'PR(性能比)',
      dataIndex: 'pr',
      render: (val) => fmtPct(val),
    },
    {
      title: '辐照缺失',
      dataIndex: 'irradianceGap',
      width: 100,
      render: (val) =>
        val ? <Tag color="orange">有缺失</Tag> : <Tag color="green">完整</Tag>,
    },
  ];

  const renderStation = (v) => {
    if (!v) return null;
    return (
      <>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="性能比 PR（区间整体）"
                value={v.pr == null ? EMPTY : Number(v.pr).toFixed(2)}
                suffix={v.pr == null ? '' : '%'}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="铭牌装机容量"
                value={v.ratedPowerWp == null ? EMPTY : Number(v.ratedPowerWp).toFixed(0)}
                suffix={v.ratedPowerWp == null ? '' : 'Wp'}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic title="逆变器发电量合计" value={fmtKwh(v.inverterTotalWh)} suffix="kWh" />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic title="电表发电量合计" value={fmtKwh(v.meterTotalWh)} suffix="kWh" />
            </Card>
          </Col>
        </Row>

        <Card title="发电量汇总" size="small" style={{ marginTop: 16 }}>
          <Tabs
            defaultActiveKey="daily"
            items={[
              {
                key: 'daily',
                label: '按天',
                children: (
                  <Table
                    rowKey="period"
                    dataSource={v.daily || []}
                    columns={yieldColumns}
                    size="middle"
                    scroll={{ x: 'max-content' }}
                    pagination={{ pageSize: 10, showSizeChanger: true }}
                    locale={{ emptyText: <Empty description="该区间无按天数据" /> }}
                  />
                ),
              },
              {
                key: 'monthly',
                label: '按月',
                children: (
                  <Table
                    rowKey="period"
                    dataSource={v.monthly || []}
                    columns={yieldColumns}
                    size="middle"
                    scroll={{ x: 'max-content' }}
                    pagination={{ pageSize: 10, showSizeChanger: true }}
                    locale={{ emptyText: <Empty description="该区间无按月数据" /> }}
                  />
                ),
              },
            ]}
          />
        </Card>
      </>
    );
  };

  // ---------------- 搜索栏 Tabs（三种模式） ----------------
  const searchTabs = [
    {
      key: 'module',
      label: '按组件序列号',
      children: (
        <Space wrap>
          <Input
            allowClear
            style={{ width: 320 }}
            placeholder="请输入组件序列号"
            value={moduleSerial}
            onPressEnter={loadModule}
            onChange={(e) => setModuleSerial(e.target.value)}
          />
          <Button type="primary" icon={<SearchOutlined />} loading={moduleLoading} onClick={loadModule}>
            查询
          </Button>
        </Space>
      ),
    },
    {
      key: 'batch',
      label: '按批次号',
      children: (
        <Space wrap>
          <Input
            allowClear
            style={{ width: 320 }}
            placeholder="请输入批次号"
            value={batchNo}
            onPressEnter={loadBatch}
            onChange={(e) => setBatchNo(e.target.value)}
          />
          <Button type="primary" icon={<SearchOutlined />} loading={batchLoading} onClick={loadBatch}>
            查询
          </Button>
        </Space>
      ),
    },
    {
      key: 'station',
      label: '按电站发电量',
      children: (
        <Space wrap>
          <Input
            allowClear
            style={{ width: 200 }}
            placeholder="电站资产 ID"
            value={stationAssetId}
            onChange={(e) => setStationAssetId(e.target.value)}
          />
          <RangePicker value={stationRange} onChange={(val) => setStationRange(val)} />
          <Button type="primary" icon={<SearchOutlined />} loading={stationLoading} onClick={loadStation}>
            查询
          </Button>
        </Space>
      ),
    },
  ];

  // ---------------- 结果区：依据当前模式渲染 ----------------
  const renderResult = () => {
    if (mode === 'module') {
      return (
        <ResultShell
          loading={moduleLoading}
          error={moduleError}
          hasData={!!moduleData}
          onRetry={retryCurrent}
        >
          {renderModule(moduleData)}
        </ResultShell>
      );
    }
    if (mode === 'batch') {
      return (
        <ResultShell
          loading={batchLoading}
          error={batchError}
          hasData={!!batchData}
          onRetry={retryCurrent}
        >
          {renderBatch(batchData)}
        </ResultShell>
      );
    }
    return (
      <ResultShell
        loading={stationLoading}
        error={stationError}
        hasData={!!stationData}
        onRetry={retryCurrent}
      >
        {renderStation(stationData)}
      </ResultShell>
    );
  };

  return (
    <Card title={t('nav:item.pv-trace')} style={{ marginBottom: 16 }}>
      <Tabs activeKey={mode} onChange={setMode} items={searchTabs} />
      {renderResult()}
    </Card>
  );
}
