import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Row, Col, Card, Statistic, List, Tag, Button, Space, Typography, Badge, Empty, Spin,
  Progress, Tooltip, Divider,
} from 'antd';
import {
  MessageOutlined, FileTextOutlined, AlertOutlined, SolutionOutlined,
  ArrowRightOutlined, ClockCircleOutlined, AppstoreOutlined, RiseOutlined,
} from '@ant-design/icons';
import api from '../api';
import { getFlatNav } from '../menuStore';
import { ASSET_STATUS_LABEL } from '../enums';

const { Text, Title } = Typography;

// 待我处理项定义：每项的拉取动作容错，失败不影响其它项。
const TODO_DEFS = [
  { key: 'complaints', label: '待处理投诉', path: '/complaints', icon: <MessageOutlined />, color: 'var(--coral)',
    fetch: () => api.get('/v1/admin/complaints?status=OPEN').then((d) => (Array.isArray(d) ? d.length : 0)) },
  { key: 'insurance', label: '待核理赔', path: '/insurance', icon: <FileTextOutlined />, color: 'var(--amber)',
    fetch: () => api.get('/v1/admin/insurance/claims/pending').then((d) => (Array.isArray(d) ? d.length : 0)) },
  { key: 'risk', label: '未处置风控事件', path: '/alerts', icon: <AlertOutlined />, color: 'var(--blue)',
    fetch: () => api.get('/v1/admin/risk/events', { params: { resolved: false } }).then((d) => (Array.isArray(d) ? d.length : 0)) },
  { key: 'opRisk', label: '运营方风险事件', path: '/operator', icon: <SolutionOutlined />, color: 'var(--brand)',
    fetch: () => api.get('/v1/admin/operator/risk-events/unresolved').then((d) => (Array.isArray(d) ? d.length : 0)) },
];

function useClock() {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);
  return now;
}

export default function Workbench() {
  const navigate = useNavigate();
  const now = useClock();
  const [dash, setDash] = useState(null);
  const [counts, setCounts] = useState({});
  const [riskFeed, setRiskFeed] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    (async () => {
      const [dRes, rRes] = await Promise.allSettled([
        api.get('/v1/admin/dashboard'),
        api.get('/v1/admin/risk/events', { params: { resolved: false } }),
      ]);
      const todoResults = await Promise.all(TODO_DEFS.map((t) => t.fetch().then((n) => [t.key, n]).catch(() => [t.key, null])));
      if (!alive) return;
      if (dRes.status === 'fulfilled') setDash(dRes.value);
      if (rRes.status === 'fulfilled') setRiskFeed(Array.isArray(rRes.value) ? rRes.value : []);
      setCounts(Object.fromEntries(todoResults));
      setLoading(false);
    })();
    return () => { alive = false; };
  }, []);

  const totalPending = TODO_DEFS.reduce((s, t) => {
    const c = counts[t.key];
    return s + (typeof c === 'number' ? c : 0);
  }, 0);

  const kpis = [
    { label: '累计换电订单', value: dash?.totalSwapOrders ?? '—', color: 'var(--brand)' },
    { label: '在线电池(满电+充电)', value: dash ? (dash.readyBatteries || 0) + (dash.chargingBatteries || 0) : '—', color: 'var(--energy)' },
    { label: '资产总数', value: dash?.assetCount ?? '—', color: 'var(--ink)' },
    { label: '累计采购额', value: dash?.purchaseTotal != null ? `$${dash.purchaseTotal}` : '—', color: 'var(--amber)' },
    { label: '待我处理', value: loading ? '…' : totalPending, color: totalPending > 0 ? 'var(--coral)' : 'var(--energy)' },
    { label: '未处置风控', value: loading ? '…' : (counts.risk ?? '—'), color: 'var(--blue)' },
  ];

  const stageData = dash?.assetByStage ? Object.entries(dash.assetByStage).map(([k, v]) => ({ k, v })) : [];

  return (
    <Spin spinning={loading}>
      {/* Hero */}
      <div className="wb-hero">
        <div>
          <div style={{ fontSize: 12, color: 'rgba(255,255,255,.8)', letterSpacing: '.06em' }}>新能源资产运营管理平台</div>
          <Title level={3} style={{ color: '#fff', margin: '4px 0 2px' }}>下午好，管理员 👋</Title>
          <Text style={{ color: 'rgba(255,255,255,.85)', fontSize: 13 }}>
            这里有 <b style={{ color: '#fff' }}>{loading ? '…' : totalPending}</b> 项待办等你处理，先看一眼全局吧。
          </Text>
        </div>
        <div style={{ textAlign: 'right', color: '#fff' }}>
          <div style={{ fontSize: 26, fontWeight: 800, fontFamily: 'var(--font-num, inherit)', lineHeight: 1.1 }}>
            {now.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
          </div>
          <div style={{ fontSize: 12, color: 'rgba(255,255,255,.8)' }}>
            {now.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })}
          </div>
          <span className="demo-badge" style={{ background: 'rgba(255,255,255,.2)', color: '#fff', marginTop: 6, display: 'inline-block' }}>演示数据</span>
        </div>
      </div>

      {/* KPI */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        {kpis.map((k) => (
          <Col xs={12} sm={8} lg={4} key={k.label}>
            <Card className="wb-kpi" styles={{ body: { padding: 16 } }}>
              <Statistic title={k.label} value={k.value} valueStyle={{ color: k.color, fontSize: 24, fontWeight: 800 }} />
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={16}>
        {/* 左：待我处理 + 实时风控 */}
        <Col xs={24} lg={16}>
          <Card
            className="wb-card"
            title={<Space><ClockCircleOutlined style={{ color: 'var(--coral)' }} />待我处理</Space>}
            extra={<Button type="link" size="small" onClick={() => navigate('/complaints')}>全部待办 →</Button>}
            style={{ marginBottom: 16 }}
          >
            <List
              grid={{ gutter: 12, xs: 1, sm: 2 }}
              dataSource={TODO_DEFS}
              renderItem={(t) => {
                const c = counts[t.key];
                const n = typeof c === 'number' ? c : 0;
                const done = n === 0;
                return (
                  <List.Item>
                    <div className="wb-todo" onClick={() => navigate(t.path)} style={{ borderLeftColor: t.color }}>
                      <div className="wb-todo-ico" style={{ background: t.color }}>{t.icon}</div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontWeight: 600, fontSize: 13 }}>{t.label}</div>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {c == null ? '加载中…' : done ? '已全部处理 ✓' : `共 ${n} 项待处理`}
                        </Text>
                      </div>
                      {typeof c === 'number' && !done && <Badge count={n} style={{ background: t.color }} />}
                      <ArrowRightOutlined style={{ color: 'var(--muted)' }} />
                    </div>
                  </List.Item>
                );
              }}
            />
          </Card>

          <Card className="wb-card" title={<Space><AlertOutlined style={{ color: 'var(--blue)' }} />实时风控动态</Space>}
            extra={<Button type="link" size="small" onClick={() => navigate('/alerts')}>查看全部 →</Button>}>
            {!riskFeed ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
            ) : riskFeed.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前无未处置风控事件" />
            ) : (
              <List
                dataSource={riskFeed.slice(0, 6)}
                renderItem={(e) => (
                  <List.Item>
                    <Space size={10} style={{ width: '100%' }}>
                      <Tag color={e.severity === 'HIGH' ? 'red' : e.severity === 'MEDIUM' ? 'orange' : 'blue'}>
                        {e.eventType || e.riskType || 'RISK'}
                      </Tag>
                      <span style={{ flex: 1, fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {e.description || e.note || '风控事件'}
                      </span>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {e.createdAt ? new Date(e.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''}
                      </Text>
                    </Space>
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>

        {/* 右：快捷入口 + 资产分布 */}
        <Col xs={24} lg={8}>
          <Card className="wb-card" title={<Space><AppstoreOutlined style={{ color: 'var(--brand)' }} />快捷入口</Space>} style={{ marginBottom: 16 }}>
            <div className="wb-quick">
              {getFlatNav().map((m) => (
                <div key={m.key} className="wb-quick-tile" onClick={() => navigate(m.path)} title={m.group}>
                  <span className="wb-quick-label">{m.label}</span>
                  <span className="wb-quick-group">{m.group}</span>
                </div>
              ))}
            </div>
          </Card>

          <Card className="wb-card" title={<Space><RiseOutlined style={{ color: 'var(--energy)' }} />资产状态分布</Space>}>
            {stageData.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无资产" />
            ) : (
              <div>
                {stageData.map((s) => {
                  const max = Math.max(...stageData.map((x) => x.v), 1);
                  return (
                    <div key={s.k} style={{ marginBottom: 10 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 }}>
                        <span>{ASSET_STATUS_LABEL[s.k] || s.k}</span>
                        <Text type="secondary">{s.v}</Text>
                      </div>
                      <Progress percent={Math.round((s.v / max) * 100)} showInfo={false} strokeColor="var(--brand)" trailColor="var(--paper-2)" />
                    </div>
                  );
                })}
              </div>
            )}
            {dash?.escrowAccounts?.length > 0 && (
              <>
                <Divider style={{ margin: '12px 0' }} />
                <Text type="secondary" style={{ fontSize: 12 }}>三专户余额</Text>
                <List
                  size="small"
                  dataSource={dash.escrowAccounts}
                  renderItem={(a) => (
                    <List.Item style={{ padding: '4px 0' }}>
                      <Text style={{ fontSize: 12 }}>{a.escrowType}</Text>
                      <Text strong style={{ fontSize: 12 }}>${a.balance}</Text>
                    </List.Item>
                  )}
                />
              </>
            )}
          </Card>
        </Col>
      </Row>
    </Spin>
  );
}
