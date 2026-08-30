import { useCallback, useEffect, useState } from 'react';
import {
  App, Button, Descriptions, Drawer, Form, Input, Radio, Space, Table, Tag, Timeline,
} from 'antd';
import { CheckOutlined, CloseOutlined, RedoOutlined, ReloadOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import {
  APPLICANT_TYPES, APP_STATUS_FILTER, AppStatusTag, EMPTY, fmtTime,
} from '../components/onboardingShared';
import {
  getAdminApplication, listApplications, listPendingActivations, reviewApplication,
  retryActivation, setKycStatus, sweepExpiredApplications,
} from '../api/onboarding';

/**
 * 入驻申请管理页（增量 C · 页面 4–5 · O34/O35/O16）。
 *
 * 按主体类型 / 状态（待审 / 待缴保证金 / 已付款 / 已激活 / 已驳回 / 已禁用）筛选，
 * 行内「审核」按钮打开详情抽屉：一屏看完全部材料、身份认证结果、场地定位、
 * 合同签署版本与审批时间轴，并执行通过 / 驳回（到材料项）/ 退回补正。
 *
 * 对接后端 AdminOnboardingController（/api/v1/admin/onboarding）。权限码 onboarding:review:manage。
 */
export default function OnboardingReview() {
  const { message } = App.useApp();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [applicantType, setApplicantType] = useState(undefined);
  const [status, setStatus] = useState(undefined);

  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [action, setAction] = useState('APPROVE');
  const [reviewForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [itemResults, setItemResults] = useState({});   // attachmentId → {status, remark}
  const [pendingCount, setPendingCount] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listApplications({ applicantType, status });
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(`申请列表加载失败：${e.message}`);
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [applicantType, status, message]);

  const loadPending = useCallback(async () => {
    try {
      const d = await listPendingActivations();
      setPendingCount(Array.isArray(d) ? d.length : 0);
    } catch {
      setPendingCount(0);
    }
  }, []);

  useEffect(() => {
    load();
    loadPending();
  }, [load, loadPending]);

  const openDetail = async (id) => {
    setDetailOpen(true);
    setDetailLoading(true);
    setItemResults({});
    setAction('APPROVE');
    reviewForm.resetFields();
    try {
      const d = await getAdminApplication(id);
      setDetail(d);
    } catch (e) {
      message.error(`申请详情加载失败：${e.message}`);
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  };

  const doReview = async () => {
    const v = await reviewForm.validateFields().catch(() => ({}));
    if (action !== 'APPROVE' && !v.reason) {
      message.error('驳回 / 退回时必须填写原因');
      return;
    }
    setSubmitting(true);
    try {
      await reviewApplication(detail.application.id, {
        action,
        reason: v.reason,
        items: Object.entries(itemResults).map(([attachmentId, r]) => ({
          attachmentId: Number(attachmentId),
          reviewStatus: r.status,
          reviewRemark: r.remark,
        })),
      });
      message.success('审核已提交');
      setDetailOpen(false);
      load();
      loadPending();
    } catch (e) {
      message.error(`审核失败：${e.message}`);
    } finally {
      setSubmitting(false);
    }
  };

  const doKyc = async (kycStatus) => {
    try {
      await setKycStatus(detail.application.id, { kycStatus });
      message.success(`身份证认证结果已置为 ${kycStatus}`);
      const d = await getAdminApplication(detail.application.id);
      setDetail(d);
    } catch (e) {
      message.error(`操作失败：${e.message}`);
    }
  };

  const doSweep = async () => {
    try {
      const n = await sweepExpiredApplications();
      message.success(`缴款超时扫描完成，${n} 条转已超时`);
      load();
    } catch (e) {
      message.error(`扫描失败：${e.message}`);
    }
  };

  const doRetry = async (id) => {
    try {
      const r = await retryActivation(id);
      message.success(`激活成功：${r?.principalType}#${r?.principalId}`);
      load();
      loadPending();
    } catch (e) {
      message.error(`重试激活失败：${e.message}（保证金已确认到账不回滚，可稍后重试）`);
    }
  };

  const columns = [
    { title: '申请单号', dataIndex: 'applicationNo', width: 170 },
    {
      title: '主体类型', dataIndex: 'applicantType', width: 100,
      render: (v) => <Tag color="blue">{APPLICANT_TYPES.find((x) => x.value === v)?.label || v}</Tag>,
    },
    { title: '申请人', dataIndex: 'applicantName', width: 110, render: (v) => v || EMPTY },
    { title: '联系电话', dataIndex: 'contactPhone', width: 130, render: (v) => v || EMPTY },
    {
      title: '状态', dataIndex: 'status', width: 130,
      render: (v) => <AppStatusTag value={v} />,
    },
    { title: '合同版本', dataIndex: 'contractVersion', width: 100, render: (v) => v || EMPTY },
    { title: '提交时间', dataIndex: 'submittedAt', width: 160, render: (v) => fmtTime(v) },
    {
      title: '操作', key: '_actions', width: 190, fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Perm code="onboarding:review:manage">
            <Button size="small" type="link" onClick={() => openDetail(r.id)}>审核</Button>
          </Perm>
          {r.status === 'DEPOSIT_PAID' && (
            <Perm code="onboarding:review:manage">
              <Button size="small" type="link" onClick={() => doRetry(r.id)}>重试激活</Button>
            </Perm>
          )}
        </Space>
      ),
    },
  ];

  const app = detail?.application;

  return (
    <PageCard
      title="入驻申请管理"
      subtitle="按主体类型与状态筛选，审核 / 驳回（精确到材料项）/ 退回补正"
      reload={load}
      loading={loading}
      extra={(
        <Space>
          <Button icon={<ReloadOutlined />} onClick={doSweep}>缴款超时扫描</Button>
        </Space>
      )}
    >
      {pendingCount > 0 && (
        <div style={{ marginBottom: 12 }}>
          <Tag color="red">有 {pendingCount} 条保证金已确认但激活未完成，请在列表中点「重试激活」</Tag>
        </div>
      )}

      <Space wrap style={{ marginBottom: 12 }}>
        <span>主体类型</span>
        <select
          className="ant-select-selector"
          style={{ height: 32, minWidth: 120 }}
          value={applicantType || ''}
          onChange={(e) => setApplicantType(e.target.value || undefined)}
        >
          <option value="">全部</option>
          {APPLICANT_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
        <span>状态</span>
        <select
          style={{ height: 32, minWidth: 140 }}
          value={status || ''}
          onChange={(e) => setStatus(e.target.value || undefined)}
        >
          <option value="">全部</option>
          {APP_STATUS_FILTER.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
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

      {/* 申请详情 + 审核（一屏决策） */}
      <Drawer
        title={app ? `入驻申请详情 · ${app.applicationNo}` : '入驻申请详情'}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        width={980}
        destroyOnClose
        extra={(
          <Space>
            <Button icon={<CheckOutlined />} type="primary" loading={submitting} onClick={doReview}>
              提交审核结论
            </Button>
          </Space>
        )}
      >
        {detailLoading ? (
          <div>加载中…</div>
        ) : app ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="申请单号">{app.applicationNo}</Descriptions.Item>
              <Descriptions.Item label="主体类型">
                <Tag color="blue">{APPLICANT_TYPES.find((x) => x.value === app.applicantType)?.label}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="状态"><AppStatusTag value={app.status} orgStatus={detail.orgOnboardingStatus} /></Descriptions.Item>
              <Descriptions.Item label="申请人">{app.applicantName || EMPTY}</Descriptions.Item>
              <Descriptions.Item label="联系方式">{app.contactPhone || EMPTY}</Descriptions.Item>
              <Descriptions.Item label="家庭地址">{app.homeAddress || EMPTY}</Descriptions.Item>
              <Descriptions.Item label="身份证（脱敏）">{detail.idCardMasked || EMPTY}</Descriptions.Item>
              <Descriptions.Item label="身份证认证">
                <Tag color={app.kycStatus === 'VERIFIED' ? 'green' : app.kycStatus === 'REJECTED' ? 'red' : 'orange'}>
                  {app.kycStatus}
                </Tag>
                <Space size="small">
                  <Button size="small" type="link" onClick={() => doKyc('VERIFIED')}>置为已认证</Button>
                  <Button size="small" type="link" danger onClick={() => doKyc('REJECTED')}>置为认证失败</Button>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="经营范围" span={2}>{app.businessScope || EMPTY}</Descriptions.Item>
              <Descriptions.Item label="场地定位">
                {app.lat != null && app.lng != null ? `${app.lat}, ${app.lng}` : EMPTY}
                <div style={{ color: '#8c8c8c' }}>{app.geoAddress || ''}</div>
              </Descriptions.Item>
              <Descriptions.Item label="所有权 / 租赁">{app.ownershipType || EMPTY}</Descriptions.Item>
              <Descriptions.Item label="合同签署版本">
                {app.contractVersion || EMPTY}
                <div style={{ color: '#8c8c8c' }}>
                  {detail.contract ? `${detail.contract.title}（签署时锁定）` : '历史数据无合同快照'}
                </div>
              </Descriptions.Item>
              <Descriptions.Item label="保证金档位">
                {detail.tier ? `${detail.tier.tierName} · ${detail.tier.depositAmount}` : EMPTY}
              </Descriptions.Item>
              <Descriptions.Item label="驳回原因" span={2}>{app.rejectReason || EMPTY}</Descriptions.Item>
            </Descriptions>

            <b>材料清单（逐项审核，驳回请填原因）</b>
            <Table
              rowKey="id"
              size="small"
              dataSource={detail.attachments || []}
              pagination={false}
              columns={[
                { title: '材料项', dataIndex: 'attachType', width: 160 },
                {
                  title: '文件', dataIndex: 'fileUrl', width: 260,
                  render: (v) => (v ? <a href={v} target="_blank" rel="noreferrer">查看 / 下载</a> : EMPTY),
                },
                { title: '说明', dataIndex: 'remark', render: (v) => v || EMPTY },
                {
                  title: '审核结论', dataIndex: 'reviewStatus', width: 100,
                  render: (v) => {
                    const color = v === 'PASSED' ? 'green' : v === 'REJECTED' ? 'red' : 'orange';
                    return <Tag color={color}>{v}</Tag>;
                  },
                },
                {
                  title: '逐项结论', key: '_review', width: 280,
                  render: (_, r) => (
                    <Space size="small">
                      <Button
                        size="small"
                        type={itemResults[r.id]?.status === 'PASSED' ? 'primary' : 'default'}
                        onClick={() => setItemResults((p) => ({ ...p, [r.id]: { ...p[r.id], status: 'PASSED' } }))}
                      >
                        通过
                      </Button>
                      <Button
                        size="small"
                        danger={itemResults[r.id]?.status === 'REJECTED'}
                        onClick={() => setItemResults((p) => ({ ...p, [r.id]: { ...p[r.id], status: 'REJECTED' } }))}
                      >
                        驳回
                      </Button>
                      <Input
                        size="small"
                        style={{ width: 150 }}
                        placeholder="如：营业执照照片模糊"
                        value={itemResults[r.id]?.remark || ''}
                        onChange={(e) => setItemResults((p) => ({
                          ...p, [r.id]: { ...p[r.id], remark: e.target.value },
                        }))}
                      />
                    </Space>
                  ),
                },
              ]}
            />

            <b>审批时间轴</b>
            <Timeline
              items={(detail.logs || []).map((l) => ({
                children: (
                  <span>
                    <Tag>{l.action}</Tag>
                    {l.fromStatus || '—'} → {l.toStatus || '—'}
                    <span style={{ color: '#8c8c8c' }}> · {l.operatorType} · {fmtTime(l.createdAt)}</span>
                    {l.remark ? <div>{l.remark}</div> : null}
                  </span>
                ),
              }))}
            />

            <b>审核结论</b>
            <Form form={reviewForm} layout="vertical">
              <Form.Item name="action" initialValue="APPROVE">
                <Radio.Group value={action} onChange={(e) => setAction(e.target.value)}>
                  <Radio value="APPROVE">通过（生成缴款通知）</Radio>
                  <Radio value="REJECT">驳回（到材料项）</Radio>
                  <Radio value="RETURN">退回补正</Radio>
                </Radio.Group>
              </Form.Item>
              <Form.Item name="reason" label="审核意见（驳回 / 退回必填）">
                <Input.TextArea rows={3} placeholder="如：营业执照照片模糊，请重新上传" />
              </Form.Item>
            </Form>
            <Space>
              <Button icon={<CheckOutlined />} type="primary" loading={submitting} onClick={doReview}>提交</Button>
              <Button icon={<RedoOutlined />} onClick={() => setItemResults({})}>重置逐项结论</Button>
              <Button icon={<CloseOutlined />} onClick={() => setDetailOpen(false)}>关闭</Button>
            </Space>
          </Space>
        ) : (
          <div>无数据</div>
        )}
      </Drawer>
    </PageCard>
  );
}
