// 「容量预定」抽屉（V81 重做）。
//
// 设计原则（老板反馈）：点按钮进来的数据全部由后端联动带出、只读展示、用户不能改；
// 客户侧只填「份数」→ 付款 → 完成。任何商品 / 业主 / 单价 / 回佣率都不出现输入框。
//
// 接口（后端已定死，勿自行扩展字段）：
//   GET  /v1/admin/capacity/plans?productId=        取该商品容量计划（取第一条）
//   POST /v1/admin/capacity/plans                   建计划（仅 5 个入参）
//   GET  /v1/admin/capacity/plans/{id}/subscriptions 该计划下的预定订单（只读）
//   POST /v1/capacity/subscribe                     {planId, unitCount} 即扣款记账
// 权限码：mfg:capacity:create（建计划）、mfg:capacity:view（打开抽屉）、capacity:subscribe（预定付款）
import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Checkbox, DatePicker, Descriptions, Drawer, Empty, Form, Input,
  InputNumber, Modal, Progress, Space, Spin, Table, Tag,
} from 'antd';
import dayjs from 'dayjs';
import { Perm } from './Perm';
import { createPlan, listPlansByProduct, listPlanSubscriptions, subscribe } from '../api/capacity';

/* ------------------------------- 展示用常量 ------------------------------- */

/** 计划/订单状态 → antd Tag 颜色。 */
const STATUS_COLOR = {
  ACTIVE: 'green',
  OPEN: 'blue',
  DRAFT: 'default',
  INACTIVE: 'default',
  CLOSED: 'red',
  PENDING: 'gold',
  SETTLED: 'green',
  COMPLETED: 'green',
  CANCELLED: 'default',
};

/** 计划/订单状态 → 中文（未命中则回退原值，避免硬编码遗漏导致空白）。 */
const STATUS_TEXT = {
  ACTIVE: '进行中',
  OPEN: '开放预定',
  DRAFT: '草稿',
  INACTIVE: '已停用',
  CLOSED: '已关闭',
  PENDING: '待处理',
  SETTLED: '已结算',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

/** 容量类型 → 中文。 */
const CAPACITY_TYPE_TEXT = {
  SERIAL: '串行（SERIAL）',
  PARALLEL: '并行（PARALLEL）',
};

/**
 * 渲染状态标签（中文 + 颜色）。
 * @param {string} v 状态原值
 * @returns {JSX.Element} Tag
 */
function statusTag(v) {
  return <Tag color={STATUS_COLOR[v] || 'default'}>{STATUS_TEXT[v] || v || '—'}</Tag>;
}

/**
 * 时间格式化：后端 Instant 可能是 ISO 字符串，也可能是秒/毫秒时间戳，这里统一兜底。
 * @param {string|number|null|undefined} v 时间原值
 * @returns {string} 「YYYY-MM-DD HH:mm」或占位符
 */
function fmtInstant(v) {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'number') {
    const ms = v > 1e12 ? v : v * 1000;
    const dn = dayjs(ms);
    return dn.isValid() ? dn.format('YYYY-MM-DD HH:mm') : String(v);
  }
  const d = dayjs(v);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : String(v);
}

/**
 * 回佣率展示：库内既可能是 0.10（小数），也可能是 10（百分数），这里按量级自适应。
 * @param {number|string|null|undefined} v 回佣率
 * @returns {string} 形如「10.00%」
 */
function fmtRate(v) {
  const n = Number(v);
  if (!n && n !== 0) return '—';
  return (n <= 1 ? n * 100 : n).toFixed(2) + '%';
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
 * 容量预定抽屉。
 *
 * @param {Object} props 组件属性
 * @param {boolean} props.open 是否打开
 * @param {Object|null} props.product 当前商品行（至少含 id / name），为 null 时不请求
 * @param {Function} props.onClose 关闭回调
 * @returns {JSX.Element} 抽屉
 */
export default function CapacityPlanDrawer({ open, product, onClose }) {
  const { message } = App.useApp();

  const [loading, setLoading] = useState(false);
  const [plan, setPlan] = useState(null);
  const [subs, setSubs] = useState([]);

  // 建计划弹窗
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createForm] = Form.useForm();

  // 预定付款
  const [unitCount, setUnitCount] = useState(1);
  const [payOpen, setPayOpen] = useState(false);
  const [agreed, setAgreed] = useState(false);
  const [paying, setPaying] = useState(false);

  /**
   * 拉取该商品的容量计划（取第一条）及其预定订单。
   * @returns {Promise<void>}
   */
  const load = useCallback(async () => {
    if (!product || !product.id) return;
    setLoading(true);
    try {
      const list = await listPlansByProduct(product.id);
      const first = Array.isArray(list) && list.length > 0 ? list[0] : null;
      setPlan(first);
      if (first && first.id) {
        const rows = await listPlanSubscriptions(first.id);
        setSubs(Array.isArray(rows) ? rows : []);
      } else {
        setSubs([]);
      }
    } catch (e) {
      message.error('容量计划加载失败：' + ((e && e.message) || '未知错误'));
      setPlan(null);
      setSubs([]);
    } finally {
      setLoading(false);
    }
  }, [product, message]);

  // 每次打开抽屉都重新拉取并重置用户输入，避免残留上一次商品的份数。
  useEffect(() => {
    if (!open) return;
    setUnitCount(1);
    setAgreed(false);
    setPayOpen(false);
    setCreateOpen(false);
    createForm.resetFields();
    load();
  }, [open, load, createForm]);

  const total = Number(plan && plan.totalUnits) || 0;
  const subscribed = Number(plan && plan.subscribedUnits) || 0;
  const remaining = Math.max(total - subscribed, 0);
  const unitPrice = Number(plan && plan.unitPrice) || 0;
  const percent = total > 0 ? Math.min(Math.round((subscribed / total) * 100), 100) : 0;
  const payable = (Number(unitCount) || 0) * unitPrice;

  /**
   * 打开二次确认弹窗：先校验份数，剩余不足直接拦下。
   * @returns {void}
   */
  const openPay = () => {
    const n = Number(unitCount);
    if (!n || n < 1) {
      message.warning('请先填写预定份数');
      return;
    }
    if (n > remaining) {
      message.warning('最多可预定 ' + remaining + ' 份，请调整份数');
      return;
    }
    setAgreed(false);
    setPayOpen(true);
  };

  /**
   * 提交预定并付款。
   * @returns {Promise<void>}
   */
  const doPay = async () => {
    if (!agreed) return;
    setPaying(true);
    try {
      await subscribe({ planId: plan.id, unitCount: Number(unitCount) });
      message.success('容量预定成功，款项已支付');
      setPayOpen(false);
      setAgreed(false);
      setUnitCount(1);
      await load();
    } catch (e) {
      message.error('预定失败：' + ((e && e.message) || '未知错误'));
    } finally {
      setPaying(false);
    }
  };

  /**
   * 提交新建容量计划：只发 5 个字段，商品取自当前行，用户身份由后端带出。
   * @param {Object} v 表单值
   * @returns {Promise<void>}
   */
  const doCreate = async (v) => {
    setCreating(true);
    try {
      const range = Array.isArray(v.window) ? v.window : [];
      const body = {
        productId: product.id,
        totalUnits: v.totalUnits,
        unitPrice: v.unitPrice,
        planDesc: v.planDesc,
      };
      if (range[0]) body.windowStart = range[0].toISOString();
      if (range[1]) body.windowEnd = range[1].toISOString();
      await createPlan(body);
      message.success('容量计划已创建');
      setCreateOpen(false);
      createForm.resetFields();
      await load();
    } catch (e) {
      message.error('创建失败：' + ((e && e.message) || '未知错误'));
    } finally {
      setCreating(false);
    }
  };

  /** 计划信息（只读 Descriptions）。 */
  const planItems = [
    { key: 'product', label: '商品', children: (product && product.name) || '—' },
    { key: 'total', label: '总份数', children: total || '—' },
    { key: 'price', label: '单价（元/份）', children: plan ? fmtMoney(unitPrice) : '—' },
    {
      key: 'progress',
      label: '已预定 / 剩余',
      span: 2,
      children: plan ? (
        <span>
          <Progress percent={percent} size="small" style={{ marginBottom: 0, minWidth: 180 }} />
          <span style={{ fontSize: 12, color: '#46594f' }}>
            已预定 {subscribed} 份，剩余 {remaining} 份
          </span>
        </span>
      ) : '—',
    },
    {
      key: 'window',
      label: '预定窗口期',
      span: 2,
      children: plan
        ? (plan.windowStart || plan.windowEnd
          ? fmtInstant(plan.windowStart) + ' ~ ' + fmtInstant(plan.windowEnd)
          : '不限')
        : '—',
    },
    {
      key: 'rate',
      label: '回佣率',
      children: plan ? fmtRate(plan.rebateRate) : '—',
    },
    {
      key: 'type',
      label: '容量类型',
      children: plan ? (CAPACITY_TYPE_TEXT[plan.capacityType] || plan.capacityType || '—') : '—',
    },
    { key: 'status', label: '状态', children: plan ? statusTag(plan.status) : '—' },
  ];

  /** 预定订单表列（只读，无任何行操作）。 */
  const subColumns = [
    { title: '订单号', dataIndex: 'id', width: 80 },
    { title: '订户', dataIndex: 'subscriberUserId', width: 100 },
    { title: '份数', dataIndex: 'unitCount', width: 70 },
    {
      title: '金额（元）',
      dataIndex: 'prepaidAmount',
      width: 110,
      render: (v) => fmtMoney(v),
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (v) => statusTag(v) },
    {
      title: '时间',
      dataIndex: 'paidAt',
      render: (v, r) => fmtInstant(v || r.createdAt),
    },
  ];

  return (
    <Drawer
      title={'容量预定 · ' + ((product && product.name) || '—')}
      width={720}
      open={open}
      onClose={onClose}
      footer={
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
          <span style={{ color: '#46594f', fontSize: 13 }}>
            {plan
              ? (remaining > 0 ? '剩余可预定 ' + remaining + ' 份' : '该计划份额已全部预定')
              : '该商品暂无容量计划，请先新建'}
          </span>
          <Space>
            {plan && remaining > 0 && (
              <Space size={6}>
                <span style={{ fontSize: 13 }}>份数</span>
                <InputNumber
                  min={1}
                  max={remaining}
                  precision={0}
                  value={unitCount}
                  onChange={(v) => setUnitCount(v)}
                  style={{ width: 100 }}
                />
              </Space>
            )}
            <Button onClick={onClose}>关闭</Button>
            {!plan && (
              <Perm code="mfg:capacity:create">
                <Button type="primary" onClick={() => setCreateOpen(true)}>新建容量计划</Button>
              </Perm>
            )}
            {plan && remaining > 0 && (
              <Perm code="capacity:subscribe">
                <Button type="primary" onClick={openPay}>确认付款</Button>
              </Perm>
            )}
          </Space>
        </div>
      }
    >
      <Spin spinning={loading}>
        {/* ① 计划信息：全部由接口带出，只读 */}
        <Descriptions title="计划信息" bordered column={2} size="small" items={planItems} />

        {/* ② 计划说明：风险提示与操作方法，必须显眼、必须只读 */}
        {plan && (
          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 16 }}
            message="风险提示与操作方法"
            description={
              <div style={{ whiteSpace: 'pre-wrap' }}>
                {plan.planDesc || '（该计划尚未填写说明，请联系发布方补充后再预定）'}
              </div>
            }
          />
        )}

        {/* ③ 预定订单表：只读 */}
        <div style={{ marginTop: 16 }}>
          <div style={{ fontWeight: 600, marginBottom: 8 }}>预定订单</div>
          <Table
            rowKey="id"
            size="small"
            dataSource={subs}
            columns={subColumns}
            pagination={{ pageSize: 5, size: 'small' }}
            locale={{ emptyText: <Empty description="暂无预定订单" /> }}
          />
        </div>

        {/* 新建容量计划：仅 4 个可填字段，商品/业主/回佣率全部由后端带出 */}
        <Modal
          title="新建容量计划"
          open={createOpen}
          onCancel={() => setCreateOpen(false)}
          footer={null}
          width={560}
        >
          <Form form={createForm} layout="vertical" onFinish={doCreate} preserve={false}>
            <Form.Item
              name="totalUnits"
              label="总份数"
              rules={[{ required: true, message: '请填写总份数' }]}
            >
              <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="可对外预定的总份数" />
            </Form.Item>
            <Form.Item
              name="unitPrice"
              label="单价（元/份）"
              rules={[{ required: true, message: '请填写单价' }]}
            >
              <InputNumber min={0} precision={2} style={{ width: '100%' }} placeholder="0.00" />
            </Form.Item>
            <Form.Item name="window" label="预定窗口期（选填）">
              <DatePicker.RangePicker style={{ width: '100%' }} showTime />
            </Form.Item>
            <Form.Item
              name="planDesc"
              label="计划说明"
              rules={[{ required: true, message: '请填写风险提示与操作方法' }]}
            >
              <Input.TextArea
                rows={5}
                placeholder="请填写风险提示与操作方法：如产能交付不达预期的处理方式、资金占用与退出规则、客户操作步骤等，提交后对所有客户只读展示，请一次写清。"
              />
            </Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={creating}>提交</Button>
              <Button onClick={() => setCreateOpen(false)}>取消</Button>
            </Space>
          </Form>
        </Modal>

        {/* 付款二次确认：勾选风险告知后才可付款 */}
        <Modal
          title="确认付款"
          open={payOpen}
          onCancel={() => setPayOpen(false)}
          okText="确认付款"
          cancelText="取消"
          okButtonProps={{ disabled: !agreed, loading: paying }}
          onOk={doPay}
        >
          <p style={{ fontSize: 16 }}>
            {unitCount} 份 × ¥{fmtMoney(unitPrice)} = 应付 <b>¥{fmtMoney(payable)}</b>
          </p>
          <p style={{ color: '#46594f' }}>商品：{(product && product.name) || '—'}（计划 #{plan && plan.id}）</p>
          <Checkbox
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
          >
            我已阅读并理解上述风险提示与操作方法
          </Checkbox>
        </Modal>
      </Spin>
    </Drawer>
  );
}
