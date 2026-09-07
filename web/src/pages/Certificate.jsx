import { useState, useEffect, useMemo } from 'react';
import {
  Card, Descriptions, Tag, Button, Space, Statistic, Row, Col, Select, message, Alert,
  Typography, Empty, Spin, Modal, Form, Input, InputNumber, DatePicker, Switch, Table, Divider, Checkbox,
  Popconfirm,
} from 'antd';
import {
  SafetyCertificateOutlined, QrcodeOutlined, EditOutlined, SettingOutlined,
  PrinterOutlined, PlusOutlined, DeleteOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import PageCard from '../components/PageCard';
import api from '../api';
import certApi from '../api/certificate';
import { useTranslation } from 'react-i18next';

const { Text, Title } = Typography;

const FIELD_TYPES = [
  { label: '数字 (number)', value: 'number' },
  { label: '文本 (text)', value: 'text' },
  { label: '单选 (select)', value: 'select' },
  { label: '日期 (date)', value: 'date' },
  { label: '布尔 (boolean)', value: 'boolean' },
];

const LAYOUT_KEY = 'certPrintLayout';

const safeParse = (s) => {
  try { return s ? JSON.parse(s) : null; } catch (e) { return null; }
};
const parseOptions = (json) => {
  const arr = safeParse(json);
  return Array.isArray(arr) ? arr : [];
};

function loadLayout(fieldKeys) {
  const def = {
    title: '产品合格证',
    subtitle: 'PRODUCT QUALITY CERTIFICATE',
    footer: '本合格证由系统生成，扫码验真。',
    fields: fieldKeys || [],
  };
  try {
    const raw = localStorage.getItem(LAYOUT_KEY);
    if (raw) {
      const l = JSON.parse(raw);
      return {
        title: l.title ?? def.title,
        subtitle: l.subtitle ?? def.subtitle,
        footer: l.footer ?? def.footer,
        fields: l.fields && l.fields.length ? l.fields : def.fields,
      };
    }
  } catch (e) { /* ignore */ }
  return def;
}

// 合格证：生成唯一编号 + 二维码验真；识别信息可编辑（复用 EAV 模板）+ A4 打印/导出PDF。
export default function Certificate() {
  const { t } = useTranslation('common');

  const [assets, setAssets] = useState([]);
  const [products, setProducts] = useState([]);
  const [manufacturers, setManufacturers] = useState([]);
  const [loading, setLoading] = useState(false);
  const [deviceId, setDeviceId] = useState(null);

  const [cert, setCert] = useState(null);       // CertificateDto
  const [certLoading, setCertLoading] = useState(false);
  const [templates, setTemplates] = useState([]); // 合格证模板字段

  const [editOpen, setEditOpen] = useState(false);
  const [editData, setEditData] = useState({});
  const [tplOpen, setTplOpen] = useState(false);
  const [fieldModal, setFieldModal] = useState({ open: false, editing: null });
  const [fieldForm] = Form.useForm();
  const [layoutOpen, setLayoutOpen] = useState(false);
  const [layout, setLayout] = useState({ title: '', subtitle: '', footer: '', fields: [] });

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      api.get('/v1/assets'),
      api.get('/v1/admin/manufacturer/products'),
      api.get('/v1/admin/manufacturer/manufacturers'),
      certApi.listCertTemplate(),
    ]).then(([a, p, m, tpl]) => {
      if (!alive) return;
      const arr = a || [];
      setAssets(arr); setProducts(p || []); setManufacturers(m || []);
      setTemplates(tpl || []);
      setDeviceId((prev) => prev ?? arr[0]?.id ?? null);
    }).catch((e) => {
      if (alive) {
        message.error('加载真实数据失败：' + e.message);
        setAssets([]); setProducts([]); setManufacturers([]);
      }
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  // 选定设备变化时拉取合格证
  useEffect(() => {
    if (deviceId == null) { setCert(null); return; }
    let alive = true;
    setCertLoading(true);
    certApi.getCert(deviceId)
      .then((c) => { if (alive) setCert(c); })
      .catch(() => { if (alive) setCert(null); })
      .finally(() => { if (alive) setCertLoading(false); });
    return () => { alive = false; };
  }, [deviceId]);

  const dev = assets.find((d) => d.id === deviceId) || assets[0] || null;
  const product = products.find((p) => p.id === dev?.productId);
  const brand = manufacturers.find((b) => b.id === dev?.manufacturerId);
  const prodName = (id) => products.find((p) => p.id === id)?.name || '—';

  const spec = useMemo(() => safeParse(cert?.specJson) || {}, [cert]);

  // ---------- 编辑识别信息 ----------
  const openEdit = () => {
    const cur = safeParse(cert?.dataJson) || {};
    setEditData(cur);
    setEditOpen(true);
  };
  const onEditField = (key, val) => setEditData((d) => ({ ...d, [key]: val }));
  const saveEdit = () => {
    for (const f of templates) {
      if (f.required && (editData[f.fieldKey] === undefined || editData[f.fieldKey] === null || editData[f.fieldKey] === '')) {
        message.error(`「${f.label}」为必填项`);
        return;
      }
    }
    certApi.updateCertData(deviceId, JSON.stringify(editData))
      .then((c) => { setCert(c); setEditOpen(false); message.success(t('common:m1069')); })
      .catch((e) => message.error('保存失败：' + e.message));
  };

  // ---------- 模板字段管理 ----------
  const openAddField = () => { fieldForm.resetFields(); setFieldModal({ open: true, editing: null }); };
  const openEditField = (f) => { fieldForm.setFieldsValue(f); setFieldModal({ open: true, editing: f }); };
  const submitField = () => {
    fieldForm.validateFields().then((v) => {
      const body = {
        fieldKey: v.fieldKey, label: v.label, type: v.type,
        unit: v.unit || null, optionsJson: v.optionsJson || null,
        required: !!v.required, sortNo: v.sortNo || 0,
      };
      const p = fieldModal.editing
        ? certApi.updateCertTemplateField(fieldModal.editing.id, body)
        : certApi.createCertTemplateField(body);
      p.then(() => {
        return certApi.listCertTemplate();
      }).then((list) => {
        setTemplates(list || []);
        setFieldModal({ open: false, editing: null });
        message.success(fieldModal.editing ? '字段已更新' : '字段已新增');
      }).catch((e) => message.error('保存失败：' + e.message));
    });
  };
  const removeField = (f) => {
    certApi.deleteCertTemplateField(f.id)
      .then(() => certApi.listCertTemplate())
      .then((list) => { setTemplates(list || []); message.success('字段已删除'); })
      .catch((e) => message.error('删除失败：' + e.message));
  };

  // ---------- 打印布局 ----------
  const openLayout = () => {
    setLayout(loadLayout(templates.map((f) => f.fieldKey)));
    setLayoutOpen(true);
  };
  const saveLayout = () => {
    localStorage.setItem(LAYOUT_KEY, JSON.stringify(layout));
    setLayoutOpen(false);
    message.success(t('common:m1069'));
    setTimeout(() => window.print(), 200);
  };
  const toggleField = (key) => {
    setLayout((l) => ({
      ...l,
      fields: l.fields.includes(key) ? l.fields.filter((k) => k !== key) : [...l.fields, key],
    }));
  };
  const doPrint = () => {
    setLayout(loadLayout(templates.map((f) => f.fieldKey)));
    setTimeout(() => window.print(), 100);
  };

  // 打印展示用的识别信息（按布局筛选）
  const printFields = templates.filter((f) => layout.fields.includes(f.fieldKey));
  const dataVals = safeParse(cert?.dataJson) || {};

  const renderEditControl = (field) => {
    const val = editData[field.fieldKey];
    const set = (v) => onEditField(field.fieldKey, v);
    switch (field.type) {
      case 'number': return <InputNumber value={val} onChange={set} style={{ width: '100%' }} />;
      case 'select': return <Select value={val} onChange={set} style={{ width: '100%' }}
        options={parseOptions(field.optionsJson).map((o) => ({ label: o, value: o }))} allowClear />;
      case 'date': return <DatePicker value={val ? dayjs(val) : null} onChange={(d) => set(d ? d.format('YYYY-MM-DD') : null)} style={{ width: '100%' }} />;
      case 'boolean': return <Switch checked={!!val} onChange={set} />;
      default: return <Input value={val} onChange={(e) => set(e.target.value)} />;
    }
  };

  return (
    <PageCard title={t('common:m786')}
      extra={
        dev ? (
          <Select value={deviceId} style={{ width: 280 }} onChange={setDeviceId}
            options={assets.map((d) => ({
              label: `${d.assetNo} · ${prodName(d.productId)}`,
              value: d.id,
            }))} />
        ) : null
      }>
      {!dev ? (
        loading ? <Spin /> : <Empty description={t('common:m787')} />
      ) : (
        <>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={8}><Card><Statistic title={t('common:m788')} value={assets.length} /></Card></Col>
            <Col span={8}><Card><Statistic title={t('common:m789')} value={'—（待统计）'} valueStyle={{ color: '#1677ff' }} /></Card></Col>
            <Col span={8}><Card><Statistic title={t('common:m790')} value={'—（待统计）'} valueStyle={{ color: '#52c41a' }} /></Card></Col>
          </Row>

          <Space className="no-print" style={{ marginBottom: 12 }}>
            <Button type="primary" icon={<EditOutlined />} disabled={!cert} onClick={openEdit}>{t('common:m1049')}</Button>
            <Button icon={<SettingOutlined />} onClick={openLayout}>{t('common:m1052')}</Button>
            <Button icon={<PrinterOutlined />} disabled={!cert} onClick={doPrint}>{t('common:m1051')}</Button>
            <Button icon={<SettingOutlined />} onClick={() => setTplOpen(true)}>{t('common:m1050')}</Button>
          </Space>

          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <Card style={{ width: 360 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <div>
                  <div style={{ fontWeight: 800 }}>{t('common:m791')}</div>
                  <div style={{ fontSize: 12, color: '#888' }}>{t('common:m792')}</div>
                </div>
                <SafetyCertificateOutlined style={{ fontSize: 22, color: '#1677ff' }} />
              </div>
              <div style={{ height: 96, width: 96, border: '1px solid #eee', borderRadius: 8, display: 'grid', placeItems: 'center', margin: '0 auto 12px', background: '#fafafa' }}>
                <QrcodeOutlined style={{ fontSize: 40, color: '#bbb' }} />
              </div>
              <div style={{ fontSize: 11, color: '#888', textAlign: 'center', wordBreak: 'break-all', marginBottom: 12 }}>{t('common:m167')}{dev.qrCode || '—'}</div>
              <Descriptions column={1} size="small">
                <Descriptions.Item label={t('common:m793')}><Text strong>{dev.assetNo}</Text> <span style={{ color: 'var(--muted)' }}>#{dev.id}</span></Descriptions.Item>
                <Descriptions.Item label={t('common:m794')}>{product?.name || '—'}</Descriptions.Item>
                <Descriptions.Item label={t('common:m181')}>{brand?.name || '—'}</Descriptions.Item>
                <Descriptions.Item label="合格证号">{cert?.certNo || '—'}</Descriptions.Item>
                <Descriptions.Item label="出证时间">{cert?.issuedAt || '—'}</Descriptions.Item>
                <Descriptions.Item label={t('common:m169')}>{dev.createdAt || '—'}</Descriptions.Item>
              </Descriptions>
              {!cert && <Alert type="warning" showIcon style={{ marginTop: 12 }} message={t('common:m1068')} />}
            </Card>

            <Card style={{ flex: 1, minWidth: 300 }} title={t('common:m1058')}>
              {certLoading ? <Spin /> : cert ? (
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="产品名称">{spec.productName || '—'}</Descriptions.Item>
                  <Descriptions.Item label="型号">{spec.model || '—'}</Descriptions.Item>
                  <Descriptions.Item label="资产类型">{spec.assetType || '—'}</Descriptions.Item>
                  {templates.filter((f) => (dataVals[f.fieldKey] !== undefined && dataVals[f.fieldKey] !== null && dataVals[f.fieldKey] !== '')).map((f) => (
                    <Descriptions.Item key={f.fieldKey} label={f.label}>{String(dataVals[f.fieldKey])}</Descriptions.Item>
                  ))}
                </Descriptions>
              ) : <Empty description={t('common:m1068')} />}
              <Alert type="success" showIcon style={{ marginTop: 12 }}
                message="二维码内嵌资产编号 + 平台签名（真实 qrCode），扫码即验真，杜绝套牌 / 假合格证。" />
            </Card>
          </div>

          {/* 编辑识别信息 */}
          <Modal title={t('common:m1049')} open={editOpen} onOk={saveEdit} onCancel={() => setEditOpen(false)} okText={t('common:m1057')} cancelText="取消" width={560}>
            {!cert ? <Alert type="warning" showIcon message={t('common:m1068')} /> : (
              <div style={{ maxHeight: 420, overflow: 'auto' }}>
                {templates.map((f) => (
                  <div key={f.fieldKey} style={{ marginBottom: 14 }}>
                    <div style={{ marginBottom: 4, fontSize: 13 }}>{f.label}{f.required && <Text type="danger"> *</Text>}{f.unit ? `（${f.unit}）` : ''}</div>
                    {renderEditControl(f)}
                  </div>
                ))}
                {templates.length === 0 && <Empty description={t('common:m1067')} />}
              </div>
            )}
          </Modal>

          {/* 模板字段管理 */}
          <Modal title={t('common:m1050')} open={tplOpen} onCancel={() => setTplOpen(false)} footer={null} width={680}>
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openAddField} style={{ marginBottom: 12 }}>{t('common:m1059')}</Button>
            <Table rowKey="id" pagination={false} size="small" dataSource={templates}
              columns={[
                { title: t('common:m1060'), dataIndex: 'label' },
                { title: t('common:m1062'), dataIndex: 'fieldKey' },
                { title: t('common:m1061'), dataIndex: 'type', render: (v) => <Tag>{v}</Tag> },
                { title: t('common:m1063'), dataIndex: 'unit' },
                { title: t('common:m1065'), dataIndex: 'required', render: (v) => v ? <Tag color="red">必填</Tag> : '—' },
                { title: t('common:m1066'), dataIndex: 'sortNo' },
                { title: t('common:m795'), render: (_, r) => (
                  <Space>
                    <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEditField(r)}>编辑</Button>
                    <PopconfirmWrap onConfirm={() => removeField(r)} title="确认删除该字段？">
                      <Button size="small" type="link" danger icon={<DeleteOutlined />}>删除</Button>
                    </PopconfirmWrap>
                  </Space>
                ) },
              ]} />
          </Modal>

          {/* 新增/编辑字段 */}
          <Modal title={fieldModal.editing ? '编辑字段' : t('common:m1059')} open={fieldModal.open}
            onOk={submitField} onCancel={() => setFieldModal({ open: false, editing: null })} okText="保存" cancelText="取消">
            <Form form={fieldForm} layout="vertical" initialValues={{ type: 'text', required: false, sortNo: 0 }}>
              <Form.Item label={t('common:m1060')} name="label" rules={[{ required: true, message: '请输入字段名称' }]}><Input placeholder="如 检验员" /></Form.Item>
              <Form.Item label={t('common:m1062')} name="fieldKey" rules={[{ required: true, message: '请输入字段标识' }]}><Input placeholder="如 inspector（英文，唯一）" disabled={!!fieldModal.editing} /></Form.Item>
              <Form.Item label={t('common:m1061')} name="type" rules={[{ required: true }]}><Select options={FIELD_TYPES} /></Form.Item>
              <Form.Item label={t('common:m1063')} name="unit"><Input placeholder="如 kWh / km（可空）" /></Form.Item>
              <Form.Item label={t('common:m1064')} name="optionsJson"><Input placeholder='select 类型填 JSON 数组，如 ["合格","不合格"]' /></Form.Item>
              <Form.Item label={t('common:m1065')} name="required" valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label={t('common:m1066')} name="sortNo"><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            </Form>
          </Modal>

          {/* 打印设置 */}
          <Modal title={t('common:m1052')} open={layoutOpen} onOk={saveLayout} onCancel={() => setLayoutOpen(false)} okText={t('common:m1057')} cancelText="取消" width={560}>
            <Form layout="vertical">
              <Form.Item label={t('common:m1053')}><Input value={layout.title} onChange={(e) => setLayout({ ...layout, title: e.target.value })} /></Form.Item>
              <Form.Item label={t('common:m1054')}><Input value={layout.subtitle} onChange={(e) => setLayout({ ...layout, subtitle: e.target.value })} /></Form.Item>
              <Form.Item label={t('common:m1055')}><Input.TextArea value={layout.footer} onChange={(e) => setLayout({ ...layout, footer: e.target.value })} rows={2} /></Form.Item>
              <Form.Item label={t('common:m1056')}>
                <div style={{ maxHeight: 200, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 6, padding: 8 }}>
                  {templates.map((f) => (
                    <Checkbox key={f.fieldKey} checked={layout.fields.includes(f.fieldKey)} onChange={() => toggleField(f.fieldKey)}>{f.label}</Checkbox>
                  ))}
                  {templates.length === 0 && <Text type="secondary">暂无可配置字段</Text>}
                </div>
              </Form.Item>
            </Form>
          </Modal>

          {/* A4 打印区（屏幕隐藏，打印时显示） */}
          <div className="cert-print-root">
            <div style={{ textAlign: 'center', borderBottom: '2px solid #000', paddingBottom: 8, marginBottom: 16 }}>
              <div style={{ fontSize: 26, fontWeight: 800, letterSpacing: 4 }}>{layout.title || '产品合格证'}</div>
              <div style={{ fontSize: 12, letterSpacing: 2, color: '#555' }}>{layout.subtitle || 'PRODUCT QUALITY CERTIFICATE'}</div>
            </div>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <tbody>
                <tr><td style={cellStyle}>产品名称</td><td style={cellStyle}>{spec.productName || '—'}</td>
                  <td style={cellStyle}>型号</td><td style={cellStyle}>{spec.model || '—'}</td></tr>
                <tr><td style={cellStyle}>资产类型</td><td style={cellStyle}>{spec.assetType || '—'}</td>
                  <td style={cellStyle}>资产编号</td><td style={cellStyle}>{dev?.assetNo || '—'}</td></tr>
                <tr><td style={cellStyle}>合格证号</td><td style={cellStyle}>{cert?.certNo || '—'}</td>
                  <td style={cellStyle}>出证时间</td><td style={cellStyle}>{cert?.issuedAt || '—'}</td></tr>
                {printFields.map((f) => (
                  <tr key={f.fieldKey}>
                    <td style={cellStyle}>{f.label}</td>
                    <td style={{ ...cellStyle, borderRight: 'none' }} colSpan={3}>{String(dataVals[f.fieldKey] ?? '—')}{f.unit ? ` ${f.unit}` : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div style={{ marginTop: 36, fontSize: 12, color: '#333', borderTop: '1px solid #ccc', paddingTop: 8 }}>
              {layout.footer || '本合格证由系统生成，扫码验真。'}
              <div style={{ marginTop: 8 }}>打印时间：{new Date().toLocaleString()}</div>
            </div>
          </div>
        </>
      )}
    </PageCard>
  );
}

// antd Popconfirm 在列表内联写法封装（避免 JSX 中直接嵌套 confirm 的闭包问题）
function PopconfirmWrap({ onConfirm, title, children }) {
  const [Confirm, setC] = useState(null);
  useEffect(() => { import('antd').then((m) => setC(() => m.Popconfirm)); }, []);
  if (!Confirm) return children;
  return <Confirm title={title} onConfirm={onConfirm}>{children}</Confirm>;
}

const cellStyle = {
  border: '1px solid #999', padding: '6px 8px', width: '18%', background: '#fafafa',
};
// 让最后一列右侧也有边框
cellStyle.borderRight = '1px solid #999';
