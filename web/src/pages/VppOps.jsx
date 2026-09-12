import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Alert, App, Button, Card, Col, Descriptions, Empty, Menu, Modal, Row, Space, Statistic, Switch, Table, Tag,
} from 'antd';
import { ExperimentOutlined, ReloadOutlined } from '@ant-design/icons';
import { EMPTY, fmtTime } from '../components/supplyShared';
import api from '../api';

/**
 * 虚拟电厂聚合看板与调度（VPP 切片第一批前端入口）。
 *
 * <p>布局：左侧组合列表（来自 GET /v1/vpp/portfolios，可点击切换），右侧为所选组合的看板：
 * 概览统计卡 + 资源可调容量明细表 + 历史调度指令表 + 「生成调度建议」按钮（POST /dispatch-plan，
 * 影子模式，不真实下发）。所有取数走真实后端，后端不可达时统一错误提示 + 重试，不白屏、不崩溃。
 *
 * <p>页面内文案一律内联中文（不新增 i18n key，避免与并行 worker 改 i18n 冲突）；菜单标题沿用 nav key。
 */
export default function VppOps() {
  const { t } = useTranslation(['nav', 'common']);
  const { message } = App.useApp();

  // ---------------- 状态 ----------------
  const [portfolios, setPortfolios] = useState([]);
  const [portfoliosLoading, setPortfoliosLoading] = useState(false);
  const [portfoliosError, setPortfoliosError] = useState(null);

  const [selectedId, setSelectedId] = useState(null);
  const [capacity, setCapacity] = useState(null);
  const [orders, setOrders] = useState([]);
  const [dashboardLoading, setDashboardLoading] = useState(false);
  const [dashboardError, setDashboardError] = useState(null);

  const [exportAllowed, setExportAllowed] = useState(false);
  const [dispatchLoading, setDispatchLoading] = useState(false);
  const [dispatchError, setDispatchError] = useState(null);
  const [dispatchOutcome, setDispatchOutcome] = useState(null);
  const [dispatchOpen, setDispatchOpen] = useState(false);

  // ---------------- 取数 ----------------
  const loadPortfolios = useCallback(async () => {
    setPortfoliosLoading(true);
    setPortfoliosError(null);
    try {
      const data = await api.get('/v1/vpp/portfolios');
      setPortfolios(Array.isArray(data) ? data : []);
    } catch (e) {
      const msg = e && e.message ? e.message : '未知错误';
      setPortfoliosError(msg);
      message.error(`虚拟电厂组合加载失败：${msg}`);
      setPortfolios([]);
    } finally {
      setPortfoliosLoading(false);
    }
  }, [message]);

  const loadDashboard = useCallback(async (portfolioId) => {
    if (portfolioId == null) return;
    setDashboardLoading(true);
    setDashboardError(null);
    try {
      const [cap, ord] = await Promise.all([
        api.get(`/v1/vpp/${portfolioId}/capacity`),
        api.get(`/v1/vpp/${portfolioId}/orders`),
      ]);
      setCapacity(cap || null);
      setOrders(Array.isArray(ord) ? ord : []);
    } catch (e) {
      const msg = e && e.message ? e.message : '未知错误';
      setDashboardError(msg);
      message.error(`看板数据加载失败：${msg}`);
      setCapacity(null);
      setOrders([]);
    } finally {
      setDashboardLoading(false);
    }
  }, [message]);

  const generateDispatchPlan = useCallback(async () => {
    if (selectedId == null) return;
    setDispatchLoading(true);
    setDispatchError(null);
    try {
      const outcome = await api.post(`/v1/vpp/${selectedId}/dispatch-plan`, { exportAllowed });
      setDispatchOutcome(outcome || null);
      setDispatchOpen(true);
    } catch (e) {
      const isForbidden = e && e.status === 403;
      const tip = isForbidden
        ? '无调度权限（需 vpp:dispatch）'
        : `生成调度建议失败：${e && e.message ? e.message : '未知错误'}`;
      setDispatchError(tip);
      message.error(tip);
      setDispatchOutcome(null);
    } finally {
      setDispatchLoading(false);
    }
  }, [selectedId, exportAllowed, message]);

  // ---------------- 副作用 ----------------
  useEffect(() => { loadPortfolios(); }, [loadPortfolios]);

  // 组合列表就绪后自动选中第一个（未选时）。
  useEffect(() => {
    if (portfolios.length > 0 && (selectedId == null || !portfolios.some((p) => p.id === selectedId))) {
      setSelectedId(portfolios[0].id);
    }
  }, [portfolios, selectedId]);

  // 选中变化即重载看板。
  useEffect(() => {
    if (selectedId != null) loadDashboard(selectedId);
  }, [selectedId, loadDashboard]);

  const selectedPortfolio = portfolios.find((p) => p.id === selectedId) || null;

  // ---------------- 渲染 ----------------
  const menuItems = portfolios.map((p) => ({
    key: String(p.id),
    label: (
      <Space size={4} wrap>
        <span>{p.name || `#${p.id}`}</span>
        {p.status ? <Tag>{p.status}</Tag> : null}
      </Space>
    ),
  }));

  const leftCard = (
    <Card title="虚拟电厂组合" size="small" styles={{ body: { padding: 0 } }}>
      {portfoliosLoading && (
        <div style={{ padding: 24, textAlign: 'center', color: '#999' }}>加载中…</div>
      )}
      {portfoliosError && (
        <Alert
          type="error"
          showIcon
          message={portfoliosError}
          style={{ margin: 12 }}
          action={<Button size="small" onClick={loadPortfolios}>重试</Button>}
        />
      )}
      {!portfoliosLoading && !portfoliosError && portfolios.length === 0 && (
        <Empty style={{ padding: 24 }} description="暂无虚拟电厂组合" />
      )}
      {!portfoliosLoading && !portfoliosError && portfolios.length > 0 && (
        <Menu
          mode="inline"
          selectedKeys={selectedId != null ? [String(selectedId)] : []}
          items={menuItems}
          onClick={({ key }) => setSelectedId(Number(key))}
          style={{ borderRight: 0 }}
        />
      )}
    </Card>
  );

  const rightCard = (
    <Card
      title={selectedPortfolio ? selectedPortfolio.name : '虚拟电厂看板'}
      extra={
        selectedPortfolio ? (
          <Space size={8} wrap>
            <span style={{ color: '#888' }}>
              {selectedPortfolio.regionCode ? `区域 ${selectedPortfolio.regionCode}` : EMPTY}
              {selectedPortfolio.gridNode ? ` · 并网点 ${selectedPortfolio.gridNode}` : ''}
            </span>
            <Button icon={<ReloadOutlined />} onClick={() => loadDashboard(selectedId)} loading={dashboardLoading}>
              刷新
            </Button>
          </Space>
        ) : null
      }
    >
      {!selectedPortfolio && (
        <Empty style={{ padding: 48 }} description="请选择左侧虚拟电厂组合查看看板" />
      )}
      {selectedPortfolio && dashboardError && (
        <Alert
          type="error"
          showIcon
          message={dashboardError}
          style={{ marginBottom: 16 }}
          action={<Button size="small" onClick={() => loadDashboard(selectedId)}>重试</Button>}
        />
      )}
      {selectedPortfolio && !dashboardError && (
        <div>
          <Space style={{ marginBottom: 12 }} wrap>
            <Switch
              checkedChildren="允许上网"
              unCheckedChildren="限发"
              checked={exportAllowed}
              onChange={setExportAllowed}
            />
            <Button
              type="primary"
              icon={<ExperimentOutlined />}
              loading={dispatchLoading}
              onClick={generateDispatchPlan}
            >
              生成调度建议
            </Button>
            {dispatchError && <Tag color="error">{dispatchError}</Tag>}
          </Space>

          <Row gutter={[16, 16]}>
            <Col xs={12} sm={8} md={8}>
              <Card size="small">
                <Statistic title="资源总数" value={selectedPortfolio.resourceCount ?? 0} />
              </Card>
            </Col>
            <Col xs={12} sm={8} md={8}>
              <Card size="small">
                <Statistic title="额定总功率" value={toKw(selectedPortfolio.totalRatedPowerW)} precision={2} suffix="kW" />
              </Card>
            </Col>
            <Col xs={12} sm={8} md={8}>
              <Card size="small">
                <Statistic title="可上调容量" value={toKw(capacity && capacity.adjustableUpW)} precision={2} suffix="kW" />
              </Card>
            </Col>
            <Col xs={12} sm={8} md={8}>
              <Card size="small">
                <Statistic title="可下调容量" value={toKw(capacity && capacity.adjustableDownW)} precision={2} suffix="kW" />
              </Card>
            </Col>
            <Col xs={12} sm={8} md={8}>
              <Card size="small">
                <Statistic title="在线资源" value={capacity ? capacity.onlineCount : 0} />
              </Card>
            </Col>
            <Col xs={12} sm={8} md={8}>
              <Card size="small">
                <Statistic
                  title="不可用资源"
                  value={capacity ? capacity.unavailableCount : 0}
                  valueStyle={capacity && capacity.unavailableCount > 0 ? { color: '#cf1322' } : undefined}
                />
              </Card>
            </Col>
          </Row>

          <Card type="inner" title="资源可调容量明细" style={{ marginTop: 16 }}>
            <Table
              rowKey={(r) => String(r.resourceId)}
              dataSource={capacity && Array.isArray(capacity.resources) ? capacity.resources : []}
              columns={RESOURCE_COLUMNS}
              size="middle"
              pagination={false}
              scroll={{ x: 'max-content' }}
              locale={{ emptyText: <Empty description="暂无资源" /> }}
            />
          </Card>

          <Card type="inner" title="历史调度指令" style={{ marginTop: 16 }}>
            <Table
              rowKey="id"
              dataSource={orders}
              columns={ORDER_COLUMNS}
              size="middle"
              pagination={{ pageSize: 8, showSizeChanger: true }}
              scroll={{ x: 'max-content' }}
              locale={{ emptyText: <Empty description="暂无调度指令" /> }}
            />
          </Card>
        </div>
      )}
    </Card>
  );

  return (
    <Card title={t('nav:item.vpp')} style={{ marginBottom: 16 }}>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={7} lg={6}>
          {leftCard}
        </Col>
        <Col xs={24} md={17} lg={18}>
          {rightCard}
        </Col>
      </Row>
      <DispatchOutcomeModal
        open={dispatchOpen}
        outcome={dispatchOutcome}
        onClose={() => setDispatchOpen(false)}
      />
    </Card>
  );
}

// ==================== 工具 / 常量（页面内联，不新增 i18n key） ====================

/** 把后端 BigDecimal（可能为 number/string/null）安全地转成 kW 数值。 */
function toKw(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n / 1000 : 0;
}

/** 功率格式化：≥1000W 显示 kW，否则显示 W；空值显示占位符。 */
function fmtW(v) {
  if (v === null || v === undefined || v === '') return EMPTY;
  const n = Number(v);
  if (!Number.isFinite(n)) return EMPTY;
  if (n === 0) return '0 W';
  if (Math.abs(n) >= 1000) {
    return `${(n / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 2 })} kW`;
  }
  return `${n.toLocaleString('zh-CN')} W`;
}

const RESOURCE_TYPE_LABEL = {
  PV: '光伏',
  ESS: '储能',
  CHARGER: '充电桩',
  DIESEL_GEN: '柴油发电机',
  CONTROLLABLE_LOAD: '可控负荷',
};
const RESOURCE_TYPE_COLOR = {
  PV: 'orange',
  ESS: 'green',
  CHARGER: 'blue',
  DIESEL_GEN: 'volcano',
  CONTROLLABLE_LOAD: 'purple',
};

const COMMAND_TYPE_LABEL = {
  DERATE_PV: '光伏限发',
  CHARGE_ESS: '储能充电',
  DISCHARGE_ESS: '储能放电',
  CURTAIL_CHARGER: '充电桩削负荷',
  SET_CHARGER_POWER: '充电桩设定功率',
  START_GEN: '启动柴油机组',
};

const ORDER_STATUS_LABEL = {
  ISSUED: '已下发',
  ACKED: '已确认',
  EXECUTING: '执行中',
  DONE: '已完成',
  FAILED: '失败',
  EXPIRED: '已过期',
};
const ORDER_STATUS_COLOR = {
  ISSUED: 'blue',
  ACKED: 'cyan',
  EXECUTING: 'processing',
  DONE: 'green',
  FAILED: 'red',
  EXPIRED: 'default',
};

const RESOURCE_COLUMNS = [
  {
    title: '资源 ID',
    dataIndex: 'resourceId',
    width: 110,
    render: (v) => (v == null ? EMPTY : `#${v}`),
  },
  {
    title: '资源类型',
    dataIndex: 'resourceType',
    width: 130,
    render: (v) => <Tag color={RESOURCE_TYPE_COLOR[v]}>{RESOURCE_TYPE_LABEL[v] || v || EMPTY}</Tag>,
  },
  {
    title: '可上调',
    dataIndex: 'upW',
    width: 120,
    align: 'right',
    render: (v) => fmtW(v),
  },
  {
    title: '可下调',
    dataIndex: 'downW',
    width: 120,
    align: 'right',
    render: (v) => fmtW(v),
  },
  {
    title: '可用状态',
    dataIndex: 'available',
    width: 100,
    render: (v) => (v ? <Tag color="green">可用</Tag> : <Tag color="red">不可用</Tag>),
  },
  {
    title: '说明',
    dataIndex: 'note',
    ellipsis: true,
    render: (v) => v || EMPTY,
  },
];

const ORDER_COLUMNS = [
  {
    title: '指令 ID',
    dataIndex: 'id',
    width: 90,
    render: (v) => (v == null ? EMPTY : `#${v}`),
  },
  {
    title: '资源 ID',
    dataIndex: 'resourceId',
    width: 100,
    render: (v) => (v == null ? EMPTY : `#${v}`),
  },
  {
    title: '指令类型',
    dataIndex: 'commandType',
    width: 140,
    render: (v) => <Tag color="geekblue">{COMMAND_TYPE_LABEL[v] || v || EMPTY}</Tag>,
  },
  {
    title: '目标功率',
    dataIndex: 'targetW',
    width: 120,
    align: 'right',
    render: (v) => fmtW(v),
  },
  {
    title: '状态',
    dataIndex: 'status',
    width: 100,
    render: (v) => <Tag color={ORDER_STATUS_COLOR[v]}>{ORDER_STATUS_LABEL[v] || v || EMPTY}</Tag>,
  },
  {
    title: '模式',
    dataIndex: 'shadow',
    width: 80,
    render: (v) => (v ? <Tag color="gold">影子</Tag> : <Tag>真实</Tag>),
  },
  {
    title: '原因',
    dataIndex: 'reason',
    ellipsis: true,
    render: (v) => v || EMPTY,
  },
  {
    title: '创建时间',
    dataIndex: 'createdAt',
    width: 150,
    render: (v) => fmtTime(v),
  },
];

/**
 * 调度建议结果弹窗（影子模式说明 + 能量平衡 + 指令草稿 + 决策说明）。
 */
function DispatchOutcomeModal({ open, outcome, onClose }) {
  return (
    <Modal
      title="调度建议（影子模式 · 不真实下发）"
      open={open}
      onCancel={onClose}
      footer={<Button onClick={onClose}>关闭</Button>}
      width={760}
    >
      {outcome ? <DispatchOutcomePanel outcome={outcome} /> : <Empty description="无调度结果" />}
    </Modal>
  );
}

function DispatchOutcomePanel({ outcome }) {
  if (!outcome) return null;
  const cmds = Array.isArray(outcome.commands) ? outcome.commands : [];
  const notes = Array.isArray(outcome.notes) ? outcome.notes : [];
  const cmdColumns = [
    {
      title: '资源 ID',
      dataIndex: 'resourceId',
      render: (v) => (v == null ? EMPTY : `#${v}`),
    },
    {
      title: '指令类型',
      dataIndex: 'commandType',
      render: (v) => <Tag color="geekblue">{COMMAND_TYPE_LABEL[v] || v || EMPTY}</Tag>,
    },
    {
      title: '目标功率',
      dataIndex: 'targetW',
      align: 'right',
      render: (v) => fmtW(v),
    },
    {
      title: '说明',
      dataIndex: 'reason',
      ellipsis: true,
    },
  ];
  return (
    <div>
      <Descriptions bordered size="small" column={2}>
        <Descriptions.Item label="决策时间">{fmtTime(outcome.at, 'YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
        <Descriptions.Item label="光伏可调用">{fmtW(outcome.pvAvailableW)}</Descriptions.Item>
        <Descriptions.Item label="本地负荷">{fmtW(outcome.localLoadW)}</Descriptions.Item>
        <Descriptions.Item label="光伏自用">{fmtW(outcome.pvSelfUseW)}</Descriptions.Item>
        <Descriptions.Item label="余电">{fmtW(outcome.surplusW)}</Descriptions.Item>
        <Descriptions.Item label="缺口">{fmtW(outcome.deficitW)}</Descriptions.Item>
        <Descriptions.Item label="限发量">{fmtW(outcome.curtailedPvW)}</Descriptions.Item>
        <Descriptions.Item label="市电/柴机补充">{fmtW(outcome.gridImportW)}</Descriptions.Item>
      </Descriptions>

      <div style={{ margin: '12px 0 8px', fontWeight: 600 }}>建议指令（{cmds.length}）</div>
      <Table
        rowKey={(c, i) => String(c.resourceId ?? i)}
        dataSource={cmds}
        columns={cmdColumns}
        size="small"
        pagination={false}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: <Empty description="无指令" /> }}
      />

      {notes.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <div style={{ fontWeight: 600, marginBottom: 4 }}>决策说明</div>
          <ul style={{ margin: 0, paddingLeft: 18, color: '#666' }}>
            {notes.map((n, i) => <li key={i}>{n}</li>)}
          </ul>
        </div>
      )}
    </div>
  );
}
