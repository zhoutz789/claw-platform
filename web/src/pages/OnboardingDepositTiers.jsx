import { useCallback, useEffect, useState } from 'react';
import {
  App, Alert, Button, Card, Form, Input, InputNumber, Popconfirm, Space, Switch, Table, Tag,
} from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { APPLICANT_TYPES, EMPTY, fmtMoney } from '../components/onboardingShared';
import { listTiersAdmin, upsertTier } from '../api/onboarding';

/**
 * 保证金档位配置页（增量 C · 页面 7 · O24 / Q1 / Q2b）。
 *
 * 按主体类型配置档位：档位名 / 保证金金额 / <b>授信倍率</b> 或 <b>绝对额度覆盖</b> /
 * 权益说明 / 排序 / 启停。授信额度 = 保证金 × 倍率（周老板拍板 3 倍，15,000 / 60,000 / 150,000），
 * 倍率落库可配、<b>不在代码里硬编码</b>，公司调政策无需改代码。
 *
 * 对接后端 AdminOnboardingController。权限码 onboarding:deposit:manage。
 */
export default function OnboardingDepositTiers() {
  const { message } = App.useApp();
  const [applicantType, setApplicantType] = useState('STATION');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listTiersAdmin(applicantType);
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(`档位列表加载失败：${e.message}`);
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [applicantType, message]);

  useEffect(() => { load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      applicantType, depositAmount: 5000, creditMultiplier: 3, enabled: true, sortNo: 10,
    });
  };

  const openEdit = (r) => {
    setEditing(r);
    form.resetFields();
    form.setFieldsValue({
      id: r.id, applicantType: r.applicantType, tierCode: r.tierCode, tierName: r.tierName,
      depositAmount: Number(r.depositAmount), creditMultiplier: Number(r.creditMultiplier),
      creditLimitOverride: r.creditLimitOverride == null ? null : Number(r.creditLimitOverride),
      benefitDesc: r.benefitDesc, sortNo: r.sortNo, enabled: r.enabled,
    });
  };

  const submit = async () => {
    const v = await form.validateFields().catch(() => null);
    if (!v) return;
    setSubmitting(true);
    try {
      await upsertTier(v);
      message.success(editing ? '档位已更新' : '档位已创建');
      setEditing(null);
      load();
    } catch (e) {
      message.error(`保存失败：${e.message}`);
    } finally {
      setSubmitting(false);
    }
  };

  /** 有效授信额度（与后端 CreditLimitService.resolveTierCreditLimit 同一口径）。 */
  const effectiveLimit = (r) => (r.creditLimitOverride != null
    ? Number(r.creditLimitOverride)
    : Number(r.depositAmount) * Number(r.creditMultiplier));

  const columns = [
    { title: '档位码', dataIndex: 'tierCode', width: 110 },
    { title: '档位名', dataIndex: 'tierName', width: 110 },
    {
      title: '保证金', dataIndex: 'depositAmount', width: 140,
      render: (v, r) => fmtMoney(v, r.currency),
    },
    {
      title: '授信倍率', dataIndex: 'creditMultiplier', width: 100,
      render: (v) => `× ${Number(v)}`,
    },
    {
      title: '绝对额度覆盖', dataIndex: 'creditLimitOverride', width: 140,
      render: (v, r) => (v == null ? EMPTY : fmtMoney(v, r.currency)),
    },
    {
      title: '授信额度（寄售货值上限）', key: '_limit', width: 200,
      render: (_, r) => <b>{fmtMoney(effectiveLimit(r), r.currency)}</b>,
    },
    { title: '权益说明', dataIndex: 'benefitDesc', render: (v) => v || EMPTY },
    { title: '排序', dataIndex: 'sortNo', width: 70 },
    {
      title: '启停', dataIndex: 'enabled', width: 80,
      render: (v) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag>,
    },
    {
      title: '操作', key: '_actions', width: 90,
      render: (_, r) => (
        <Perm code="onboarding:deposit:manage">
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(r)}>编辑</Button>
        </Perm>
      ),
    },
  ];

  return (
    <PageCard
      title="保证金档位配置"
      subtitle="按主体类型配置档位：保证金金额 → 授信额度（寄售设备名义货值上限）"
      reload={load}
      loading={loading}
      extra={(
        <Perm code="onboarding:deposit:manage">
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增档位</Button>
        </Perm>
      )}
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="授信额度 = 保证金 × 授信倍率（拍板默认 3 倍）；填写「绝对额度覆盖」可绕过倍率直接定额度。倍率为可配置参数，改动立即生效。"
      />

      <Space style={{ marginBottom: 12 }}>
        <span>主体类型</span>
        <select
          style={{ height: 32, minWidth: 120 }}
          value={applicantType}
          onChange={(e) => setApplicantType(e.target.value)}
        >
          {APPLICANT_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
      </Space>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        pagination={false}
        scroll={{ x: 'max-content' }}
      />

      {/* 新增 / 编辑（内联表单，避免弹窗遮挡表格） */}
      <Card size="small" title={editing ? `编辑档位 · ${editing.tierCode}` : '新增档位'} style={{ marginTop: 12 }}>
        <Form form={form} layout="vertical">
          <Space wrap align="start">
            <Form.Item name="id" hidden><Input /></Form.Item>
            <Form.Item
              name="applicantType" label="主体类型"
              rules={[{ required: true, message: '请选择主体类型' }]}
            >
              <select className="ant-input" style={{ height: 32, width: 130 }}>
                {APPLICANT_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
            </Form.Item>
            <Form.Item
              name="tierCode" label="档位码"
              rules={[{ required: true, message: '请填写档位码' }]}
            >
              <Input placeholder="BASIC / STANDARD / PREMIUM" style={{ width: 160 }} />
            </Form.Item>
            <Form.Item
              name="tierName" label="档位名"
              rules={[{ required: true, message: '请填写档位名' }]}
            >
              <Input placeholder="如：第一档" style={{ width: 120 }} />
            </Form.Item>
            <Form.Item
              name="depositAmount" label="保证金金额"
              rules={[{ required: true, message: '请填写保证金金额' }]}
            >
              <InputNumber min={0} precision={2} style={{ width: 150 }} />
            </Form.Item>
            <Form.Item
              name="creditMultiplier" label="授信倍率"
              rules={[{ required: true, message: '请填写授信倍率' }]}
              extra="可配，默认 3"
            >
              <InputNumber min={0} precision={4} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="creditLimitOverride" label="绝对额度覆盖" extra="留空则按倍率计算">
              <InputNumber min={0} precision={2} style={{ width: 150 }} />
            </Form.Item>
            <Form.Item name="sortNo" label="排序">
              <InputNumber min={0} precision={0} style={{ width: 90 }} />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Form.Item name="benefitDesc" label="权益说明（引导升档）">
            <Input.TextArea rows={2} placeholder="如：可持有寄售设备名义货值上限 150,000 USD" />
          </Form.Item>
          <Space>
            <Perm code="onboarding:deposit:manage">
              <Button type="primary" loading={submitting} onClick={submit}>
                {editing ? '保存修改' : '创建档位'}
              </Button>
            </Perm>
            {editing && (
              <Popconfirm title="放弃编辑？" onConfirm={() => setEditing(null)}>
                <Button>取消</Button>
              </Popconfirm>
            )}
          </Space>
        </Form>
      </Card>
    </PageCard>
  );
}
