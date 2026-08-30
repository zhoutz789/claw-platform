import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App, Alert, Button, Card, Checkbox, Descriptions, Drawer, Empty, Form, Input, InputNumber,
  Select, Space, Spin, Steps, Table, Tag, Upload,
} from 'antd';
import { FileTextOutlined, PlusOutlined, SendOutlined, SaveOutlined, UploadOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import {
  APPLICANT_TYPES, APP_STATUS_FILTER, AppStatusTag, EMPTY, fmtMoney, fmtTime, isAttachmentInput,
  isTextInput, beforeUploadAttachment, uploadFile,
} from '../components/onboardingShared';
import {
  getDraft, getOnboardingContent, listDepositTiers, listMaterials, listMyApplications,
  replaceAttachments, saveDraft, submitApplication, submitVoucher,
} from '../api/onboarding';

/**
 * 入驻申请页（增量 C · 页面 1–3）：入驻说明 + 申请表单 Drawer + 我的入驻进度。
 *
 * 说明区展示当前生效版本的富文本合作要点（含<b>版本号</b>）与<b>公司签章合同扫描件</b>
 * （在线预览 + 下载）；点「申请入驻」打开分步 Drawer，表单按 applicant_type
 * 从后端材料清单<b>动态渲染</b>；支持保存草稿与提交（提交时才做全量必填校验）。
 *
 * 对接后端 OnboardingController（/api/v1/onboarding）。权限码 onboarding:apply:self。
 */
export default function OnboardingApply() {
  const { message, modal } = App.useApp();

  const [applicantType, setApplicantType] = useState('STATION');
  const [content, setContent] = useState(null);
  const [contentLoading, setContentLoading] = useState(false);

  const [materials, setMaterials] = useState([]);
  const [tiers, setTiers] = useState([]);
  const [rows, setRows] = useState([]);
  const [listLoading, setListLoading] = useState(false);

  // 申请抽屉
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [step, setStep] = useState(0);
  const [applicationId, setApplicationId] = useState(null);
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [files, setFiles] = useState({});        // materialCode → file url 列表
  const [agreed, setAgreed] = useState(false);
  /** 我的入驻进度筛选（留空 = 全部）。 */
  const [statusFilter, setStatusFilter] = useState(null);

  const loadContent = useCallback(async (type) => {
    setContentLoading(true);
    try {
      const d = await getOnboardingContent(type, 'zh');
      setContent(d);
    } catch (e) {
      setContent(null);
      message.error(`入驻说明加载失败：${e.message}`);
    } finally {
      setContentLoading(false);
    }
  }, [message]);

  const loadMeta = useCallback(async (type) => {
    try {
      const m = await listMaterials(type);
      setMaterials(Array.isArray(m) ? m : []);
    } catch {
      setMaterials([]);
    }
    try {
      const t = await listDepositTiers(type);
      setTiers(Array.isArray(t) ? t : []);
    } catch {
      setTiers([]);
    }
  }, []);

  const loadMine = useCallback(async () => {
    setListLoading(true);
    try {
      const d = await listMyApplications();
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(`我的入驻进度加载失败：${e.message}`);
      setRows([]);
    } finally {
      setListLoading(false);
    }
  }, [message]);

  useEffect(() => {
    loadContent(applicantType);
    loadMeta(applicantType);
    loadMine();
  }, [applicantType, loadContent, loadMeta, loadMine]);

  /** 材料清单按步骤分组：主体信息 / 资质证照 / 场地信息 / 合作计划 / 协议签署。 */
  const stepGroups = useMemo(() => {
    const textCodes = ['APPLICANT_NAME', 'CONTACT', 'HOME_ADDRESS', 'ID_CARD'];
    const licenseCodes = ['BUSINESS_LICENSE', 'BUSINESS_SCOPE', 'LEGAL_REP_CERT', 'MFG_QUALIFICATION',
      'BRAND_AUTH', 'MERCH_CATEGORY'];
    const siteCodes = ['LAND_CERT', 'OWNERSHIP_LEASE', 'SIGNBOARD', 'SITE_PHOTO', 'LAND_INTRO', 'LOCATION'];
    const planCodes = ['COOPERATION_PLAN'];
    const groups = [
      { title: '主体信息', items: [] },
      { title: '资质证照', items: [] },
      { title: '场地信息', items: [] },
      { title: '合作计划', items: [] },
      { title: '协议签署', items: [] },
    ];
    materials.forEach((m) => {
      if (textCodes.includes(m.materialCode)) groups[0].items.push(m);
      else if (licenseCodes.includes(m.materialCode)) groups[1].items.push(m);
      else if (siteCodes.includes(m.materialCode)) groups[2].items.push(m);
      else if (planCodes.includes(m.materialCode)) groups[3].items.push(m);
      else groups[4].items.push(m);
    });
    return groups.filter((g) => g.items.length > 0);
  }, [materials]);

  const openApply = async () => {
    setStep(0);
    setAgreed(false);
    setFiles({});
    form.resetFields();
    try {
      const d = await getDraft(applicantType, 'zh');
      setApplicationId(d?.id || null);
      form.setFieldsValue({
        APPLICANT_NAME: d?.applicantName,
        CONTACT: d?.contactPhone,
        HOME_ADDRESS: d?.homeAddress,
        BUSINESS_SCOPE: d?.businessScope,
        LAND_INTRO: d?.landIntro,
        COOPERATION_PLAN: d?.cooperationPlan,
        ownershipType: d?.ownershipType,
        lat: d?.lat,
        lng: d?.lng,
        geoAddress: d?.geoAddress,
        depositTierId: d?.depositTierId,
      });
    } catch (e) {
      message.error(`草稿加载失败：${e.message}`);
      setApplicationId(null);
    }
    setDrawerOpen(true);
  };

  /** 上传某材料项的附件。 */
  const handleUpload = async (materialCode, file) => {
    try {
      const url = await uploadFile(file);
      setFiles((prev) => ({ ...prev, [materialCode]: [...(prev[materialCode] || []), url] }));
      if (applicationId) {
        await replaceAttachments(applicationId, { attachType: materialCode, fileUrls: [...(files[materialCode] || []), url] });
      }
      message.success('上传成功');
    } catch (e) {
      message.error(`上传失败：${e.message}`);
    }
    return false;   // 阻止 antd 默认上传行为（已自行处理）
  };

  const buildPayload = (values) => {
    const fields = {};
    materials.filter((m) => isTextInput(m.inputType)).forEach((m) => {
      const v = values?.[m.materialCode];
      if (v !== undefined && v !== null && v !== '') fields[m.materialCode] = String(v);
    });
    return {
      applicationId,
      applicantType,
      lang: 'zh',
      fields,
      attachments: files,
      ownershipType: values?.ownershipType,
      lat: values?.lat,
      lng: values?.lng,
      geoAddress: values?.geoAddress,
      depositTierId: values?.depositTierId,
      agreed,
    };
  };

  const doSaveDraft = async () => {
    const v = await form.validateFields().catch(() => ({}));
    setSaving(true);
    try {
      const d = await saveDraft(buildPayload(v));
      setApplicationId(d?.id || applicationId);
      message.success('草稿已保存');
    } catch (e) {
      message.error(`保存草稿失败：${e.message}`);
    } finally {
      setSaving(false);
    }
  };

  const doSubmit = async () => {
    let v = {};
    try {
      v = await form.validateFields();
    } catch {
      message.error('请先补全必填项');
      return;
    }
    if (!agreed) {
      message.error('请先阅读并勾选同意入驻协议');
      return;
    }
    setSubmitting(true);
    try {
      const d = await submitApplication(buildPayload(v));
      setApplicationId(d?.id || applicationId);
      message.success('申请已提交，请等待平台初审');
      setDrawerOpen(false);
      loadMine();
    } catch (e) {
      message.error(`提交失败：${e.message}`);
    } finally {
      setSubmitting(false);
    }
  };

  /** 上传缴款凭证（待缴保证金状态）。 */
  const doVoucher = (row) => {
    let url = '';
    modal.confirm({
      title: '上传缴款凭证',
      width: 520,
      content: (
        <div style={{ marginTop: 12 }}>
          <Upload
            accept="image/jpeg,image/png,application/pdf"
            beforeUpload={beforeUploadAttachment}
            maxCount={1}
            customRequest={async ({ file, onSuccess, onError }) => {
              try {
                url = await uploadFile(file);
                onSuccess({ url }, file);
              } catch (err) { onError(err); }
            }}
          >
            <Button icon={<UploadOutlined />}>选择凭证文件（JPG/PNG/PDF，≤10MB）</Button>
          </Upload>
        </div>
      ),
      okText: '提交凭证',
      cancelText: '取消',
      onOk: async () => {
        if (!url) {
          message.error('请先上传凭证文件');
          return Promise.reject(new Error('no-file'));
        }
        await submitVoucher(row.id, { voucherUrl: url, amount: row.depositAmount });
        message.success('凭证已提交，等待财务确认到账');
        loadMine();
      },
    });
  };

  const columns = [
    { title: '申请单号', dataIndex: 'applicationNo', width: 170 },
    {
      title: '主体类型', dataIndex: 'applicantType', width: 100,
      render: (v) => <Tag color="blue">{APPLICANT_TYPES.find((x) => x.value === v)?.label || v}</Tag>,
    },
    { title: '申请人', dataIndex: 'applicantName', width: 110, render: (v) => v || EMPTY },
    {
      title: '状态', dataIndex: 'status', width: 120,
      render: (v, r) => <AppStatusTag value={v} orgStatus={r.orgOnboardingStatus} />,
    },
    { title: '合同版本', dataIndex: 'contractVersion', width: 100, render: (v) => v || EMPTY },
    { title: '驳回原因', dataIndex: 'rejectReason', render: (v) => v || EMPTY },
    { title: '提交时间', dataIndex: 'submittedAt', width: 160, render: (v) => fmtTime(v) },
    {
      title: '操作', key: '_actions', width: 150,
      render: (_, r) => (
        <Space size="small">
          {r.status === 'APPROVED' && (
            <Button size="small" type="link" onClick={() => doVoucher(r)}>上传凭证</Button>
          )}
          {(r.status === 'DRAFT' || r.status === 'RETURNED' || r.status === 'REJECTED') && (
            <Button size="small" type="link" onClick={openApply}>继续填写</Button>
          )}
        </Space>
      ),
    },
  ];

  const filteredRows = statusFilter
    ? rows.filter((r) => r.status === statusFilter)
    : rows;

  return (
    <PageCard
      title="入驻申请"
      subtitle="查看合作要点与合同条款，在线提交入驻申请并跟踪审批进度"
      reload={loadMine}
      loading={listLoading}
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card size="small" extra={(
          <Select
            value={applicantType}
            onChange={setApplicantType}
            style={{ width: 140 }}
            options={APPLICANT_TYPES}
          />
        )}>
          {contentLoading ? (
            <Spin />
          ) : content ? (
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Space wrap>
                <b>{content.title}</b>
                <Tag color="blue">版本 {content.version}</Tag>
                <Tag>语言 {content.lang}</Tag>
                {content.publishedAt && <Tag color="green">生效于 {fmtTime(content.publishedAt, 'YYYY-MM-DD')}</Tag>}
              </Space>
              {content.contractFileUrl && (
                <Space>
                  <FileTextOutlined />
                  <a href={content.contractFileUrl} target="_blank" rel="noreferrer">在线预览签章合同</a>
                  <a href={content.contractFileUrl} download>下载</a>
                </Space>
              )}
              {/* 富文本合作要点：内容由平台在「入驻说明与合同」页维护（后端富文本编辑器产出），
                  此处直接渲染。后端已做内容版本化与快照，前端不做二次转义。 */}
              <div
                className="onb-content"
                dangerouslySetInnerHTML={{ __html: content.contentHtml || '' }}
              />
            </Space>
          ) : (
            <Empty description="该类主体尚未发布入驻说明" />
          )}
          <Perm code="onboarding:apply:self">
            <Button type="primary" icon={<PlusOutlined />} style={{ marginTop: 12 }} onClick={openApply}>
              申请入驻
            </Button>
          </Perm>
        </Card>

        <Card size="small" title="我的入驻进度" extra={(
          <Space>
            <select
              style={{ height: 32, minWidth: 140 }}
              value={statusFilter || ''}
              onChange={(e) => setStatusFilter(e.target.value || null)}
            >
              <option value="">全部状态</option>
              {APP_STATUS_FILTER.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
            </select>
          </Space>
        )}>
          <Table
            rowKey="id"
            loading={listLoading}
            dataSource={filteredRows}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            pagination={{ pageSize: 10, showSizeChanger: true }}
          />
        </Card>
      </Space>

      {/* 申请表单：分步 Drawer（非单层弹窗，O9） */}
      <Drawer
        title={`申请入驻 · ${APPLICANT_TYPES.find((x) => x.value === applicantType)?.label || applicantType}`}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={860}
        destroyOnClose
        extra={(
          <Space>
            <Button icon={<SaveOutlined />} loading={saving} onClick={doSaveDraft}>保存草稿</Button>
            <Button type="primary" icon={<SendOutlined />} loading={submitting} onClick={doSubmit}>提交申请</Button>
          </Space>
        )}
      >
        <Steps
          current={step}
          onChange={setStep}
          size="small"
          style={{ marginBottom: 16 }}
          items={stepGroups.map((g) => ({ title: g.title }))}
        />

        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="材料清单按主体类型动态渲染：服务站需土地/场地照片与定位，厂家需生产资质，商家需经营品类且无需土地证明。"
        />

        <Form form={form} layout="vertical">
          {(stepGroups[step]?.items || []).map((m) => {
            const rules = m.required ? [{ required: true, message: `请填写/上传${m.materialName}` }] : [];
            if (isAttachmentInput(m.inputType)) {
              return (
                <Form.Item key={m.materialCode} label={`${m.materialName}${m.required ? '（必填）' : ''}`} required={m.required}>
                  <Upload
                    listType="picture-card"
                    accept="image/jpeg,image/png,application/pdf"
                    multiple={m.inputType === 'IMAGES'}
                    beforeUpload={beforeUploadAttachment}
                    fileList={(files[m.materialCode] || []).map((u, i) => ({
                      uid: `${m.materialCode}-${i}`, name: `附件${i + 1}`, status: 'done', url: u,
                    }))}
                    onRemove={(f) => {
                      setFiles((prev) => ({
                        ...prev,
                        [m.materialCode]: (prev[m.materialCode] || []).filter((u) => u !== f.url),
                      }));
                    }}
                    customRequest={async ({ file, onSuccess, onError }) => {
                      try {
                        await handleUpload(m.materialCode, file);
                        onSuccess({}, file);
                      } catch (e) { onError(e); }
                    }}
                  >
                    <div><PlusOutlined /><div style={{ marginTop: 4 }}>上传</div></div>
                  </Upload>
                  <div style={{ color: '#8c8c8c', fontSize: 12 }}>
                    {m.hint || (m.inputType === 'IMAGES'
                      ? `请上传 ${m.minCount || 1}–${m.maxCount || 9} 张`
                      : '支持 JPG / PNG / PDF，单张 ≤ 10MB')}
                  </div>
                </Form.Item>
              );
            }
            if (m.materialCode === 'LOCATION') {
              return (
                <Form.Item key={m.materialCode} label={`${m.materialName}（必填）`} required>
                  <Space wrap>
                    <Form.Item name="lat" noStyle>
                      <InputNumber placeholder="纬度" precision={6} style={{ width: 160 }} />
                    </Form.Item>
                    <Form.Item name="lng" noStyle>
                      <InputNumber placeholder="经度" precision={6} style={{ width: 160 }} />
                    </Form.Item>
                    <Form.Item name="geoAddress" noStyle>
                      <Input placeholder="定位反查地址" style={{ width: 260 }} />
                    </Form.Item>
                  </Space>
                  <div style={{ color: '#8c8c8c', fontSize: 12 }}>
                    {m.hint || '在地图上点选场地位置，用户将据此在 App 上找到您的站'}
                  </div>
                </Form.Item>
              );
            }
            return (
              <Form.Item
                key={m.materialCode}
                name={m.materialCode}
                label={`${m.materialName}${m.required ? '（必填）' : ''}`}
                rules={rules}
                extra={m.hint}
              >
                {m.inputType === 'TEXTAREA'
                  ? <Input.TextArea rows={3} />
                  : <Input />}
              </Form.Item>
            );
          })}

          {step === 1 && (
            <Form.Item name="ownershipType" label="所有权 / 租赁">
              <Select
                allowClear
                style={{ width: 200 }}
                options={[{ value: 'OWNED', label: '自有' }, { value: 'LEASED', label: '租赁' }]}
              />
            </Form.Item>
          )}

          {step === stepGroups.length - 1 && (
            <>
              <Form.Item
                name="depositTierId"
                label="保证金档位"
                rules={[{ required: true, message: '请选择保证金档位' }]}
              >
                <Select
                  style={{ width: 420 }}
                  options={tiers.map((t) => ({
                    value: t.id,
                    label: `${t.tierName}（保证金 ${fmtMoney(t.depositAmount, t.currency)}，授信额度 ${fmtMoney(
                      t.creditLimitOverride ?? Number(t.depositAmount) * Number(t.creditMultiplier), t.currency)})`,
                  }))}
                />
              </Form.Item>
              <Descriptions size="small" column={1} bordered style={{ marginBottom: 12 }}>
                <Descriptions.Item label="签署合同版本">
                  {content ? `${content.title} · ${content.version}` : EMPTY}
                </Descriptions.Item>
              </Descriptions>
              <Checkbox checked={agreed} onChange={(e) => setAgreed(e.target.checked)}>
                我已阅读并同意《{content?.title || '入驻合作协议'}》（版本 {content?.version || EMPTY}）
              </Checkbox>
              {content?.contractFileUrl && (
                <div style={{ marginTop: 8 }}>
                  <a href={content.contractFileUrl} target="_blank" rel="noreferrer">查看签章合同扫描件</a>
                </div>
              )}
            </>
          )}
        </Form>

        <Space style={{ marginTop: 16 }}>
          <Button disabled={step === 0} onClick={() => setStep((s) => Math.max(0, s - 1))}>上一步</Button>
          <Button
            disabled={step >= stepGroups.length - 1}
            onClick={() => setStep((s) => Math.min(stepGroups.length - 1, s + 1))}
          >
            下一步
          </Button>
        </Space>
      </Drawer>
    </PageCard>
  );
}
