import { useCallback, useEffect, useState } from 'react';
import {
  App, Button, Popconfirm, Space, Table, Tag,
} from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { APPLICANT_TYPES, EMPTY, fmtMoney, fmtTime } from '../components/onboardingShared';
import { confirmDeposit, listDeposits, rejectDeposit } from '../api/onboarding';

/**
 * 保证金缴纳确认页（增量 C · 页面 8 · O19 / O20）。
 *
 * 平台 / 财务查看缴款台账与转账凭证，执行「确认到账 / 驳回」。
 * <b>确认到账即触发自动激活</b>（建主体 → 绑账号 → 授模板 → 回填额度 → 入驻状态置 ACTIVATED）。
 * 若激活失败，保证金仍为已确认、申请单停留在「已付款」，由「入驻申请管理」页的
 * 「重试激活」人工处理，不做静默自动重试（Q8）。
 *
 * 对接后端 AdminOnboardingController。权限码 onboarding:deposit:confirm。
 */
export default function OnboardingDepositConfirm() {
  const { message, modal } = App.useApp();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState('PENDING_CONFIRM');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listDeposits(status);
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(`缴款台账加载失败：${e.message}`);
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [status, message]);

  useEffect(() => { load(); }, [load]);

  const doConfirm = async (row) => {
    setLoading(true);
    try {
      const r = await confirmDeposit(row.id);
      message.success(
        r?.principalId
          ? `到账已确认，激活成功：${r.principalType}#${r.principalId}，授信额度 ${r.creditLimit ?? EMPTY}`
          : '到账已确认'
      );
      load();
    } catch (e) {
      // 激活失败时后端已提交「缴款确认」，此处提示人工重试，避免误以为整单回滚
      message.error(`处理失败：${e.message}。若提示激活失败，请到「入驻申请管理」点「重试激活」（保证金已确认不会回滚）`);
      load();
    } finally {
      setLoading(false);
    }
  };

  const doReject = (row) => {
    let reason = '';
    modal.confirm({
      title: '驳回缴款凭证',
      width: 480,
      content: (
        <input
          className="ant-input"
          style={{ marginTop: 12 }}
          placeholder="驳回原因（必填），如：凭证模糊无法辨认金额"
          onChange={(e) => { reason = e.target.value; }}
        />
      ),
      okText: '驳回',
      cancelText: '取消',
      onOk: async () => {
        if (!reason || !reason.trim()) {
          message.error('请填写驳回原因');
          return Promise.reject(new Error('no-reason'));
        }
        await rejectDeposit(row.id, reason.trim());
        message.success('已驳回，申请单回到「待缴保证金」');
        load();
      },
    });
  };

  const columns = [
    { title: '缴款单号', dataIndex: 'depositNo', width: 160 },
    { title: '申请单', dataIndex: 'applicationId', width: 90 },
    {
      title: '主体类型', dataIndex: 'principalType', width: 100,
      render: (v) => <Tag color="blue">{APPLICANT_TYPES.find((x) => x.value === v)?.label || v}</Tag>,
    },
    {
      title: '实缴金额', dataIndex: 'amount', width: 140,
      render: (v, r) => fmtMoney(v, r.currency),
    },
    { title: '付款方式', dataIndex: 'payMethod', width: 120, render: (v) => v || EMPTY },
    { title: '付款人', dataIndex: 'payerName', width: 110, render: (v) => v || EMPTY },
    {
      title: '转账凭证', dataIndex: 'voucherUrl', width: 150,
      render: (v) => (v ? <a href={v} target="_blank" rel="noreferrer">查看凭证</a> : EMPTY),
    },
    {
      title: '状态', dataIndex: 'status', width: 130,
      render: (v) => {
        const map = {
          PENDING_CONFIRM: { label: '待确认到账', color: 'gold' },
          CONFIRMED: { label: '已确认', color: 'green' },
          REJECTED: { label: '已驳回', color: 'red' },
          REFUNDED: { label: '已退款', color: 'default' },
        };
        const m = map[v] || { label: v, color: 'default' };
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    { title: '提交时间', dataIndex: 'createdAt', width: 160, render: (v) => fmtTime(v) },
    { title: '确认时间', dataIndex: 'confirmedAt', width: 160, render: (v) => fmtTime(v) },
    { title: '驳回原因', dataIndex: 'rejectReason', render: (v) => v || EMPTY },
    {
      title: '操作', key: '_actions', width: 160, fixed: 'right',
      render: (_, r) => (
        r.status === 'PENDING_CONFIRM' ? (
          <Space size="small">
            <Perm code="onboarding:deposit:confirm">
              <Popconfirm
                title="确认该笔保证金已到账？确认后将自动激活该主体的全部功能"
                okText="确认到账"
                cancelText="取消"
                onConfirm={() => doConfirm(r)}
              >
                <Button size="small" type="link" icon={<CheckCircleOutlined />}>确认到账</Button>
              </Popconfirm>
              <Button size="small" type="link" danger icon={<CloseCircleOutlined />} onClick={() => doReject(r)}>
                驳回
              </Button>
            </Perm>
          </Space>
        ) : EMPTY
      ),
    },
  ];

  return (
    <PageCard
      title="保证金缴纳确认"
      subtitle="核对转账凭证并确认到账；确认后系统自动激活该主体的全部功能"
      reload={load}
      loading={loading}
    >
      <Space style={{ marginBottom: 12 }}>
        <span>状态</span>
        <select
          style={{ height: 32, minWidth: 150 }}
          value={status || ''}
          onChange={(e) => setStatus(e.target.value || undefined)}
        >
          <option value="">全部</option>
          <option value="PENDING_CONFIRM">待确认到账</option>
          <option value="CONFIRMED">已确认</option>
          <option value="REJECTED">已驳回</option>
          <option value="REFUNDED">已退款</option>
        </select>
        <Button onClick={load}>查询</Button>
      </Space>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />
    </PageCard>
  );
}
