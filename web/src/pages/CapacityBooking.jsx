// 容量预定 · 只读查询页（V81 重做）。
//
// 老板反馈：原页面把「建计划 9 个字段 + 定购 3 个字段」摊在页面上让使用者手填裸 ID，
// 既填得多又容易填错。V81 起建计划与预定全部收敛到「商品列表 → 容量预定」抽屉里联动完成，
// 本页只保留查询表格：容量计划 / 我的预订 / 回佣看板。
//
// 说明：后端没有「当前登录用户」接口，因此「我的预订」「回佣看板」保留一个用户 ID 查询条件
// （纯查询，不是新增/编辑入口）；容量计划改为按商品查询（GET /plans?productId=）。
import { useState } from 'react';
import { Alert, App, Button, Card, Empty, InputNumber, Space, Table, Tabs, Tag } from 'antd';
import PageCard from '../components/PageCard';
import {
  listPlansByProduct,
  listSubscriptions,
  listRebates,
} from '../api/capacity';

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

  // 容量计划：按商品查询。
  const [productId, setProductId] = useState(null);
  const [plans, setPlans] = useState([]);
  const [loadingPlans, setLoadingPlans] = useState(false);

  // 我的预订 / 回佣看板：按用户查询（后端无当前登录用户接口，故保留查询条件）。
  const [userId, setUserId] = useState(null);
  const [subs, setSubs] = useState([]);
  const [loadingSubs, setLoadingSubs] = useState(false);
  const [rebates, setRebates] = useState([]);
  const [loadingRebates, setLoadingRebates] = useState(false);

  /** 按商品查询容量计划。 */
  const loadPlans = () => {
    if (!productId) {
      message.warning('请先填写商品 ID');
      return;
    }
    setLoadingPlans(true);
    listPlansByProduct(productId)
      .then((d) => setPlans(Array.isArray(d) ? d : []))
      .catch((e) => { message.error(e.message || '查询失败'); setPlans([]); })
      .finally(() => setLoadingPlans(false));
  };

  /** 按用户查询我的预订。 */
  const loadSubs = () => {
    if (!userId) {
      message.warning('请先填写用户 ID');
      return;
    }
    setLoadingSubs(true);
    listSubscriptions(userId)
      .then((d) => setSubs(Array.isArray(d) ? d : []))
      .catch((e) => { message.error(e.message || '查询失败'); setSubs([]); })
      .finally(() => setLoadingSubs(false));
  };

  /** 按用户查询回佣结算明细。 */
  const loadRebates = () => {
    if (!userId) {
      message.warning('请先填写用户 ID');
      return;
    }
    setLoadingRebates(true);
    listRebates(userId)
      .then((d) => setRebates(Array.isArray(d) ? d : []))
      .catch((e) => { message.error(e.message || '查询失败'); setRebates([]); })
      .finally(() => setLoadingRebates(false));
  };

  /** 查询条件行（纯查询，不含任何新增/编辑入口）。
   * @param {Object} o 参数
   * @param {string} o.label 输入框前缀
   * @param {number|null} o.value 当前值
   * @param {Function} o.onChange 变更回调
   * @param {Function} o.onQuery 查询回调
   * @param {boolean} o.loading 查询中
   * @returns {JSX.Element} 查询条
   */
  const filterBar = ({ label, value, onChange, onQuery, loading }) => (
    <Space style={{ marginBottom: 12 }}>
      <span>{label}</span>
      <InputNumber
        min={1}
        precision={0}
        value={value}
        onChange={onChange}
        placeholder="ID"
        style={{ width: 160 }}
      />
      <Button type="primary" onClick={onQuery} loading={loading}>查询</Button>
    </Space>
  );

  const planTab = (
    <Card size="small" title="容量计划">
      {filterBar({
        label: '商品 ID',
        value: productId,
        onChange: setProductId,
        onQuery: loadPlans,
        loading: loadingPlans,
      })}
      <Table
        rowKey="id"
        dataSource={plans}
        loading={loadingPlans}
        pagination={false}
        size="small"
        locale={{ emptyText: <Empty description="暂无数据，请在「商品列表 → 容量预定」中创建，或输入商品 ID 查询" /> }}
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
      {filterBar({
        label: '用户 ID',
        value: userId,
        onChange: setUserId,
        onQuery: loadSubs,
        loading: loadingSubs,
      })}
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
      {filterBar({
        label: '用户 ID',
        value: userId,
        onChange: setUserId,
        onQuery: loadRebates,
        loading: loadingRebates,
      })}
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
        items={[
          { key: 'plans', label: '容量计划', children: planTab },
          { key: 'subs', label: '我的预订', children: subTab },
          { key: 'rebates', label: '回佣看板', children: rebateTab },
        ]}
      />
    </PageCard>
  );
}
