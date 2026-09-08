// 容量预定 · 只读查询页（V81 重做）。
//
// 老板反馈：原页面把「建计划 9 个字段 + 定购 3 个字段」摊在页面上让使用者手填裸 ID，
// 既填得多又容易填错。V81 起建计划与预定全部收敛到「商品列表 → 容量预定」抽屉里联动完成，
// 本页只保留查询表格：容量计划 / 我的预订 / 回佣看板。
//
// 说明：后端三个查询接口均已改为由登录态带出身份（/plans 不传 productId 即返回当前用户发布的计划、
// /subscriptions 与 /rebates 无参），因此本页不再出现任何让用户填 ID 的输入框：
// 进页面自动加载当前 Tab，切 Tab 自动加载对应数据。
import { useCallback, useEffect, useState } from 'react';
import { Alert, App, Card, Empty, Table, Tabs, Tag } from 'antd';
import PageCard from '../components/PageCard';
import { listMyPlans, listSubscriptions, listRebates } from '../api/capacity';

// 状态 → antd Tag 颜色，覆盖容量计划 / 预订 / 回佣常见状态值。
const STATUS_COLOR = {
  ACTIVE: 'green',
  INACTIVE: 'default',
  OPEN: 'blue',
  CLOSED: 'red',
  PENDING: 'gold',
  SETTLED: 'green',
  COMPLETED: 'green',
  CANCELLED: 'default',
};

/** 状态 → 中文（未命中回退原值）。 */
const STATUS_TEXT = {
  ACTIVE: '进行中',
  INACTIVE: '已停用',
  OPEN: '开放预定',
  CLOSED: '已关闭',
  PENDING: '待处理',
  SETTLED: '已结算',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

const statusTag = (v) => <Tag color={STATUS_COLOR[v] || 'default'}>{STATUS_TEXT[v] || v || '—'}</Tag>;

/**
 * 时间格式化（后端 Instant 可能是 ISO 串，也可能是秒/毫秒时间戳）。
 * @param {string|number|null|undefined} v 时间原值
 * @returns {string} 「YYYY-MM-DD HH:mm」或占位符
 */
function fmtTime(v) {
  if (v === null || v === undefined || v === '') return '—';
  const d = new Date(typeof v === 'number' ? (v > 1e12 ? v : v * 1000) : v);
  if (Number.isNaN(d.getTime())) return String(v);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * 金额展示（两位小数）。
 * @param {number|string|null|undefined} v 金额
 * @returns {string} 形如「1,200.00」
 */
function fmtMoney(v) {
  const n = Number(v);
  if (!n && n !== 0) return '—';
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * 回佣率展示：库内既可能是 0.10（小数），也可能是 10（百分数），按量级自适应。
 * @param {number|string|null|undefined} v 回佣率
 * @returns {string} 形如「10.00%」
 */
function fmtRate(v) {
  const n = Number(v);
  if (!n && n !== 0) return '—';
  return (n <= 1 ? n * 100 : n).toFixed(2) + '%';
}

export default function CapacityBooking() {
  const { message } = App.useApp();

  // 当前 Tab（进页面即自动加载该 Tab 数据，切 Tab 重新加载）。
  const [activeTab, setActiveTab] = useState('plans');

  // 容量计划：当前登录用户自己发布的计划（无参）。
  const [plans, setPlans] = useState([]);
  const [loadingPlans, setLoadingPlans] = useState(false);

  // 我的预订 / 回佣看板：均由登录态带出订户（无参）。
  const [subs, setSubs] = useState([]);
  const [loadingSubs, setLoadingSubs] = useState(false);
  const [rebates, setRebates] = useState([]);
  const [loadingRebates, setLoadingRebates] = useState(false);

  /** 统一的失败提示。 */
  const fail = useCallback((e) => message.error((e && e.message) || '查询失败'), [message]);

  /** 查询我发布的容量计划（无参，后端按登录态返回）。 */
  const loadPlans = useCallback(() => {
    setLoadingPlans(true);
    listMyPlans()
      .then((d) => setPlans(Array.isArray(d) ? d : []))
      .catch((e) => { fail(e); setPlans([]); })
      .finally(() => setLoadingPlans(false));
  }, [fail]);

  /** 查询我的预订（无参，订户由登录态带出）。 */
  const loadSubs = useCallback(() => {
    setLoadingSubs(true);
    listSubscriptions()
      .then((d) => setSubs(Array.isArray(d) ? d : []))
      .catch((e) => { fail(e); setSubs([]); })
      .finally(() => setLoadingSubs(false));
  }, [fail]);

  /** 查询我的回佣结算明细（无参，订户由登录态带出）。 */
  const loadRebates = useCallback(() => {
    setLoadingRebates(true);
    listRebates()
      .then((d) => setRebates(Array.isArray(d) ? d : []))
      .catch((e) => { fail(e); setRebates([]); })
      .finally(() => setLoadingRebates(false));
  }, [fail]);

  // 进页面 / 切 Tab 自动加载，无需任何手填查询条件。
  useEffect(() => {
    if (activeTab === 'plans') loadPlans();
    else if (activeTab === 'subs') loadSubs();
    else loadRebates();
  }, [activeTab, loadPlans, loadSubs, loadRebates]);

  const planTab = (
    <Card size="small" title="容量计划">
      <Table
        rowKey="id"
        dataSource={plans}
        loading={loadingPlans}
        pagination={false}
        size="small"
        locale={{ emptyText: <Empty description="暂无数据，请在「商品列表 → 容量预定」中创建" /> }}
        columns={[
          { title: '计划ID', dataIndex: 'id', width: 80 },
          { title: '商品ID', dataIndex: 'productId', width: 90 },
          { title: '总份数', dataIndex: 'totalUnits', width: 90 },
          { title: '已预定', dataIndex: 'subscribedUnits', width: 90 },
          { title: '单价（元）', dataIndex: 'unitPrice', width: 110, render: (v) => fmtMoney(v) },
          { title: '容量类型', dataIndex: 'capacityType', width: 120, render: (v) => v || '—' },
          { title: '回佣率', dataIndex: 'rebateRate', width: 90, render: (v) => fmtRate(v) },
          { title: '状态', dataIndex: 'status', width: 100, render: (v) => statusTag(v) },
          {
            title: '计划说明',
            dataIndex: 'planDesc',
            render: (v) => (v ? String(v).slice(0, 60) + (String(v).length > 60 ? '…' : '') : '—'),
          },
        ]}
      />
    </Card>
  );

  const subTab = (
    <Card size="small" title="我的预订">
      <Table
        rowKey="id"
        dataSource={subs}
        loading={loadingSubs}
        pagination={false}
        size="small"
        locale={{ emptyText: <Empty description="暂无预订记录，请在「商品列表 → 容量预定」中预定" /> }}
        columns={[
          { title: '订单号', dataIndex: 'id', width: 80 },
          { title: '计划ID', dataIndex: 'planId', width: 90 },
          { title: '订户', dataIndex: 'subscriberUserId', width: 100 },
          { title: '份数', dataIndex: 'unitCount', width: 80 },
          { title: '金额（元）', dataIndex: 'prepaidAmount', width: 110, render: (v) => fmtMoney(v) },
          { title: '状态', dataIndex: 'status', width: 100, render: (v) => statusTag(v) },
          { title: '时间', dataIndex: 'paidAt', render: (v, r) => fmtTime(v || r.createdAt) },
        ]}
      />
    </Card>
  );

  const rebateTab = (
    <Card size="small" title="回佣看板">
      <Table
        rowKey="id"
        dataSource={rebates}
        loading={loadingRebates}
        pagination={false}
        size="small"
        locale={{ emptyText: <Empty description="暂无回佣记录" /> }}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 70 },
          { title: '结算单号', dataIndex: 'settlementNo', width: 140 },
          { title: '租赁订单ID', dataIndex: 'rentalOrderId', width: 120 },
          { title: '份数', dataIndex: 'unitCount', width: 80 },
          { title: '比例', dataIndex: 'ratio', width: 90, render: (v) => fmtRate(v) },
          { title: '金额（元）', dataIndex: 'amount', width: 110, render: (v) => fmtMoney(v) },
          { title: '状态', dataIndex: 'status', width: 100, render: (v) => statusTag(v) },
        ]}
      />
    </Card>
  );

  return (
    <PageCard title="容量预定查询">
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="容量计划请在『商品列表 → 容量预定』中创建与预订，本页仅供查询。"
      />
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          { key: 'plans', label: '容量计划', children: planTab },
          { key: 'subs', label: '我的预订', children: subTab },
          { key: 'rebates', label: '回佣看板', children: rebateTab },
        ]}
      />
    </PageCard>
  );
}
