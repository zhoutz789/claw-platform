import { useState, useEffect, useCallback } from 'react';
import {
  Segmented, Table, Tag, Drawer, Tabs, Descriptions, Button, Card, Space, Alert,
  message, Modal, Form, Input, Select, Empty, Spin, Typography,
} from 'antd';
import {
  ArrowRightOutlined, QrcodeOutlined, PrinterOutlined, SafetyCertificateOutlined,
  PlusOutlined, EditOutlined, DeleteOutlined, LockOutlined, SendOutlined,
} from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
// 低空经济：飞行安全态 / 安全事件来自真实后端 drone-safety 接口（N5 收敛为只读，
// 锁机触发 / 解除、作业登记统一收口到「作业与安全管控」DroneOps，本 Tab 不再出现任何写按钮 / 本地演示种子）。
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getSafetyStatus, listSafetyEvents } from '../api/drone';
// T8：车辆（地面自动驾驶 / 换电）域接口 + 无依赖 SVG 轨迹回放组件。
import * as vehicleApi from '../api/vehicle';
import TrajectoryPlayback from '../components/TrajectoryPlayback';

const { Text } = Typography;

/* 后端枚举 → 中文标签 / antd 颜色（真实字段，无编造） */
const STATUS_LABEL = {
  IN_STOCK: '在库', IN_USE: '使用中', SHARED: '共享中', REPAIR: '维修中',
  DISABLED: '停用', RETIRED: '退役', RECYCLED: '回收中', SCRAPPED: '已报废',
};
const STATUS_COLOR = {
  IN_STOCK: 'default', IN_USE: 'green', SHARED: 'blue', REPAIR: 'orange',
  DISABLED: 'default', RETIRED: 'red', RECYCLED: 'purple', SCRAPPED: 'red',
};
const statusTag = (s) => <Tag color={STATUS_COLOR[s] || 'default'}>{STATUS_LABEL[s] || s}</Tag>;
const TYPE_LABEL = { VEHICLE: '车辆', BATTERY: '电池', CHARGER: '充电桩', PV_STATION: '光伏站', DRONE: '无人机' };
const OPTYPE_LABEL = { PASSENGER: '客运', LOGISTICS: '物流', MOBILE_SELL: '流动售卖', ADVERTISING: '广告', RECORDING: '录像数据' };
const CAUSE_LABEL = { GEOFENCE_VIOLATION: '越界', LOST_LINK: '失联', LOW_BATTERY: '低电量', MANUAL: '人工' };

// 共享赋权矩阵（权限模型概念，非数据，静态保留）
const AUTH_MATRIX = [
  { f: '开关/使用', o: '✓', r: '可选', rule: '产权人勾选授予' },
  { f: '实时定位', o: '✓', r: '✓ 实时', rule: '实时不隐藏' },
  { f: '历史轨迹', o: '✓', r: '仅本人使用期', rule: '其余隐藏' },
  { f: '收益数据', o: '✓', r: '隐藏', rule: '产权人可见' },
  { f: '产权/转让', o: '✓', r: '禁止', rule: '仅产权人' },
];

export default function ProductIot() {  const { t } = useTranslation(['common', 'task']);

  const [tab, setTab] = useState('dev');
  const [prodId, setProdId] = useState(null);
  const [dev, setDev] = useState(null);
  const [fieldModal, setFieldModal] = useState({ open: false, prodId: null, editing: null });
  const [fieldForm] = Form.useForm();

  // —— 真实后端数据 ——
  const [assets, setAssets] = useState([]);
  const [products, setProducts] = useState([]);
  const [adminProducts, setAdminProducts] = useState([]);
  const [tplProdId, setTplProdId] = useState(null);
  const [tplFields, setTplFields] = useState([]);
  const [tplLoading, setTplLoading] = useState(false);
  const [manufacturers, setManufacturers] = useState([]);
  const [transfers, setTransfers] = useState([]);
  const [loading, setLoading] = useState(false);

  const brandName = (id) => manufacturers.find((m) => m.id === id)?.name || '—';
  const prodName = (id) => products.find((p) => p.id === id)?.name || '—';
  const prodOf = (id) => products.find((p) => p.id === id);
  const prod = prodOf(prodId);

  const loadBasics = useCallback(async () => {
    setLoading(true);
    try {
      const [a, p, m, t] = await Promise.all([
        api.get('/v1/assets'),
        api.get('/v1/admin/manufacturer/products'),
        api.get('/v1/admin/manufacturer/manufacturers'),
        api.get('/v1/admin/custody/transfers'),
      ]);
      setAssets(a || []);
      setProducts(p || []);
      setManufacturers(m || []);
      setTransfers(t || []);
    } catch (e) {
      message.error('加载真实数据失败：' + (e.message || '未知错误'));
      setAssets([]); setProducts([]); setManufacturers([]); setTransfers([]);
    } finally {
      setLoading(false);
    }
    // 平台产品目录（admin products，模板字段 EAV 用）独立拉取，非管理员 403 不影响主数据
    api.get('/v1/admin/products').then((list) => setAdminProducts(Array.isArray(list) ? list : [])).catch(() => setAdminProducts([]));
  }, []);
  useEffect(() => { loadBasics(); }, [loadBasics]);

  // 模板字段 EAV（真实后端 /api/v1/admin/products/{id}/fields）
  const loadTplFields = useCallback(async (pid) => {
    setTplLoading(true);
    try {
      const fs = await api.get(`/v1/admin/products/${pid}/fields`);
      setTplFields(Array.isArray(fs) ? fs : []);
    } catch (e) {
      message.error('加载模板字段失败：' + (e.message || '未知'));
      setTplFields([]);
    } finally {
      setTplLoading(false);
    }
  }, []);

  const openAddField = (pid) => { fieldForm.resetFields(); setFieldModal({ open: true, prodId: pid, editing: null }); };
  const openEditField = (pid, f) => {
    fieldForm.setFieldsValue({ fieldKey: f.fieldKey, label: f.label, type: f.type, unit: f.unit, optionsJson: f.optionsJson, required: f.required, sortNo: f.sortNo });
    setFieldModal({ open: true, prodId: pid, editing: f.id });
  };
  const submitField = () => {
    fieldForm.validateFields().then(async (v) => {
      const pid = fieldModal.prodId;
      try {
        if (fieldModal.editing) {
          await api.put(`/v1/admin/products/${pid}/fields/${fieldModal.editing}`, {
            label: v.label, type: v.type, unit: v.unit, optionsJson: v.optionsJson, required: !!v.required, sortNo: v.sortNo || 0,
          });
        } else {
          await api.post(`/v1/admin/products/${pid}/fields`, {
            fieldKey: v.fieldKey, label: v.label, type: v.type, unit: v.unit, optionsJson: v.optionsJson, required: !!v.required, sortNo: v.sortNo || 0,
          });
        }
        message.success(fieldModal.editing ? '字段已更新' : '字段已新增');
        setFieldModal({ open: false, prodId: null, editing: null });
        loadTplFields(pid);
      } catch (e) {
        message.error('保存字段失败：' + (e.message || '未知'));
      }
    });
  };
  const removeField = (pid, fid) => {
    api.delete(`/v1/admin/products/${pid}/fields/${fid}`)
      .then(() => { message.success(t('common:m122')); loadTplFields(pid); })
      .catch((e) => message.error('删除失败：' + (e.message || '未知')));
  };

  // 合格证：优先用设备 tab 选中的真实资产，否则取真实资产列表首个
  const certAsset = dev || assets[0] || null;

  return (
    <PageCard title={t('common:m123')} extra={<Segmented
      value={tab}
      onChange={setTab}
      options={[
        { label: t('common:m124'), value: 'tpl' },
        { label: t('common:m125'), value: 'prod' },
        { label: t('common:m126'), value: 'dev' },
        { label: t('common:m49'), value: 'cert' },
        ...(dev && (dev.assetType === 'VEHICLE' || dev.assetType === 'EV')
          ? [{ label: t('task:vehicle.tab'), value: 'vehicle' }]
          : []),
      ]}
    />}>
      <Alert
        type="info" showIcon style={{ marginBottom: 14 }}
        message="模型：产品模板(平台级·本地建模辅助) → 品牌产品(真实后端) → 设备(真实资产)。设备详情为「资产数字孪生」入口，聚合定位/赋权/收益/维修/转让。除产品模板为本地建模辅助外，其余数据均来自真实后端，加载失败会报错且不回退假数据。"
      />

      {tab === 'tpl' && (
        <div>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message="产品模板字段（EAV）已接入真实后端 /api/v1/admin/products/{id}/fields：选择一个产品，管理其可扩展字段 schema（字段键/标签/类型/单位/选项/必填/排序）。" />
          <Table
            rowKey="id" pagination={false} loading={loading}
            dataSource={adminProducts}
            columns={[
              { title: t('common:m85'), dataIndex: 'name', render: (v, r) => <a onClick={() => { const next = tplProdId === r.id ? null : r.id; setTplProdId(next); setTplFields([]); if (next) loadTplFields(next); }}>{v}</a> },
              { title: t('common:m88'), dataIndex: 'category', render: (v) => v || '—' },
              { title: t('common:m90'), dataIndex: 'brand', render: (v) => v || '—' },
              { title: t('common:m127'), dataIndex: 'assetType', render: (v) => v || '—' },
            ]}
          />
          {tplProdId && (
            <Card style={{ marginTop: 14 }} title={`${(adminProducts.find((p) => p.id === tplProdId) || {}).name || ''} · 模板字段定义（真实 EAV）`}
              extra={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openAddField(tplProdId)}>{t('common:m128')}</Button>}>
              <p style={{ color: 'var(--muted)', marginTop: -4 }}>{t('common:m129')}</p>
              <Table rowKey="id" pagination={false} size="small" loading={tplLoading}
                dataSource={tplFields}
                columns={[
                  { title: t('common:m130'), dataIndex: 'fieldKey' },
                  { title: t('common:m131'), dataIndex: 'label' },
                  { title: t('common:m36'), dataIndex: 'type', render: (v) => <Tag>{v}</Tag> },
                  { title: t('common:m132'), dataIndex: 'unit', render: (v) => v || '—' },
                  { title: t('common:m133'), dataIndex: 'required', render: (v) => (v ? '是' : '否') },
                  { title: t('common:m134'), dataIndex: 'sortNo' },
                  { title: t('common:m58'), render: (_, r) => (
                    <Space>
                      <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEditField(tplProdId, r)}>{t('common:m82')}</Button>
                      <Button size="small" type="link" danger icon={<DeleteOutlined />} onClick={() => removeField(tplProdId, r.id)}>{t('common:m83')}</Button>
                    </Space>
                  ) },
                ]}
              />
            </Card>
          )}
        </div>
      )}

      {tab === 'prod' && (
        <div>
          {products.length === 0 && !loading && <Empty description={t('common:m135')} />}
          {products.map((p) => (
            <Card key={p.id} style={{ marginBottom: 12, cursor: 'pointer', borderColor: prodId === p.id ? 'var(--brand)' : undefined }}
              onClick={() => setProdId(prodId === p.id ? null : p.id)}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <div>
                  <div style={{ fontWeight: 700 }}>{p.name}</div>
                  <div style={{ fontSize: 12, color: 'var(--muted)' }}>{t('common:m136')}{p.model || '—'}{t('common:m137')}{TYPE_LABEL[p.assetType] || p.assetType}{t('common:m138')}{brandName(p.manufacturerId)}</div>
                  <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 4 }}>{p.description || '—'}</div>
                </div>
                <Tag color="green">{assets.filter((a) => a.productId === p.id).length}{t('common:m139')}</Tag>
              </div>
            </Card>
          ))}
          {prod && (
            <Card title={`${prod.name} · 名下真实设备`}>
              <Table rowKey="id" loading={loading} pagination={false}
                dataSource={assets.filter((a) => a.productId === prod.id)}
                columns={[
                  { title: t('common:m5'), render: (_, r) => <>{r.assetNo} <span style={{ color: 'var(--muted)' }}>#{r.id}</span></> },
                  { title: t('common:m140'), render: (_, r) => (r.ownerId != null ? `用户#${r.ownerId}` : '平台') },
                  { title: t('common:m8'), dataIndex: 'status', render: (v) => statusTag(v) },
                  { title: '', render: (_, r) => <Button size="small" type="link" onClick={(e) => { e.stopPropagation(); setDev(r); }}>{t('common:m141')}</Button> },
                ]}
              />
            </Card>
          )}
        </div>
      )}

      {tab === 'dev' && (
        <Table rowKey="id" loading={loading} pagination={{ pageSize: 10 }}
          dataSource={assets}
          columns={[
            { title: t('common:m5'), render: (_, r) => <>{r.assetNo} <span style={{ color: 'var(--muted)' }}>#{r.id}</span></> },
            { title: t('common:m142'), render: (_, r) => prodName(r.productId) },
            { title: t('common:m90'), render: (_, r) => brandName(r.manufacturerId) },
            { title: t('common:m140'), render: (_, r) => (r.ownerId != null ? `用户#${r.ownerId}` : '平台') },
            { title: t('common:m8'), dataIndex: 'status', render: (v) => statusTag(v) },
            { title: '', render: (_, r) => <Button type="link" onClick={() => setDev(r)}>{t('common:m143')}</Button> },
          ]}
        />
      )}

      {tab === 'cert' && <CertPanel asset={certAsset} products={products} manufacturers={manufacturers} />}

      {tab === 'vehicle' && dev && <VehicleIotTab dev={dev} />}

      <DeviceDrawer dev={dev} products={products} manufacturers={manufacturers} transfers={transfers} onClose={() => setDev(null)} />

      <Modal
        title={fieldModal.editing ? '编辑模板字段' : '新增模板字段（真实 EAV）'}
        open={fieldModal.open}
        onOk={submitField}
        onCancel={() => setFieldModal({ open: false, prodId: null, editing: null })}
        okText={t('common:m97')}
        cancelText={t('common:m96')}
      >
        <Form form={fieldForm} layout="vertical" initialValues={{ type: 'number', required: false, sortNo: 0 }}>
          <Form.Item label={t('common:m144')} name="fieldKey" rules={[{ required: true, message: t('common:m145') }]}>
            <Input placeholder={t('common:m146')} disabled={!!fieldModal.editing} />
          </Form.Item>
          <Form.Item label={t('common:m147')} name="label" rules={[{ required: true, message: t('common:m148') }]}>
            <Input placeholder={t('common:m149')} />
          </Form.Item>
          <Form.Item label={t('common:m150')} name="type" rules={[{ required: true }]}>
            <Select options={[
              { label: t('common:m151'), value: 'number' },
              { label: t('common:m152'), value: 'text' },
              { label: t('common:m153'), value: 'select' },
              { label: t('common:m154'), value: 'date' },
              { label: t('common:m155'), value: 'boolean' },
            ]} />
          </Form.Item>
          <Form.Item label={t('common:m156')} name="unit"><Input placeholder={t('common:m157')} /></Form.Item>
          <Form.Item label={t('common:m158')} name="optionsJson"><Input placeholder={t('common:m159')} /></Form.Item>
          <Form.Item label={t('common:m160')} name="required">
            <Select options={[{ label: t('common:m161'), value: false }, { label: t('common:m162'), value: true }]} />
          </Form.Item>
          <Form.Item label={t('common:m163')} name="sortNo"><Input type="number" /></Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}

function CertPanel({ asset, products, manufacturers }) {  const { t } = useTranslation('common');

  if (!asset) {
    return <Empty description={t('common:m164')} />;
  }
  const brandName = (id) => manufacturers.find((m) => m.id === id)?.name || '—';
  const product = products.find((p) => p.id === asset.productId);
  return (
    <Space size="large" align="start" wrap>
      <Card style={{ width: 320 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
          <strong>{t('common:m165')}</strong><Tag icon={<SafetyCertificateOutlined />} color="gold">{t('common:m166')}</Tag>
        </div>
        <div style={{ height: 96, width: 96, border: '1px solid #eee', borderRadius: 8, display: 'grid', placeItems: 'center', margin: '0 auto 10px', background: '#fafafa' }}>
          <QrcodeOutlined style={{ fontSize: 40, color: '#bbb' }} />
        </div>
        <div style={{ fontSize: 11, color: '#888', textAlign: 'center', wordBreak: 'break-all', marginBottom: 10 }}>{t('common:m167')}{asset.qrCode || '—'}
        </div>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label={t('common:m168')}><Text strong>{asset.assetNo}</Text> <span style={{ color: 'var(--muted)' }}>#{asset.id}</span></Descriptions.Item>
          <Descriptions.Item label={t('common:m142')}>{product?.name || '—'}</Descriptions.Item>
          <Descriptions.Item label={t('common:m90')}>{brandName(asset.manufacturerId)}</Descriptions.Item>
          <Descriptions.Item label={t('common:m169')}>{asset.createdAt || '—'}</Descriptions.Item>
        </Descriptions>
        <Space style={{ marginTop: 10 }}>
          <Button icon={<QrcodeOutlined />} onClick={() => message.success(t('common:m170'))}>{t('common:m171')}</Button>
          <Button icon={<PrinterOutlined />} onClick={() => message.success(t('common:m172'))}>{t('common:m173')}</Button>
        </Space>
      </Card>
      <Card style={{ flex: 1, minWidth: 280 }} title={t('common:m174')}>
        <p style={{ color: 'var(--muted)' }}>{t('common:m175')}</p>
        <ul style={{ color: 'var(--muted)', lineHeight: 1.9 }}>
          <li>{t('common:m176')}</li>
          <li>{t('common:m177')}</li>
        </ul>
      </Card>
    </Space>
  );
}

function DeviceDrawer({ dev, products, manufacturers, transfers, onClose }) {  const { t } = useTranslation('common');

  const [trace, setTrace] = useState(null);
  const [traceLoading, setTraceLoading] = useState(false);

  useEffect(() => {
    if (!dev) return;
    let alive = true;
    setTraceLoading(true); setTrace(null);
    api.get(`/v1/admin/manufacturer/assets/${dev.id}/trace`)
      .then((d) => { if (alive) setTrace(d || null); })
      .catch((e) => { if (alive) { message.error('加载资产溯源失败：' + e.message); setTrace(null); } })
      .finally(() => { if (alive) setTraceLoading(false); });
    return () => { alive = false; };
  }, [dev]);

  if (!dev) return null;

  const brandName = (id) => manufacturers.find((m) => m.id === id)?.name || '—';
  const product = products.find((p) => p.id === dev.productId);
  const vehicleOps = trace?.vehicleOps || [];
  const totalRevenue = trace?.totalRevenue;
  const assetTransfers = (transfers || []).filter((t) => t.assetId === dev.id);

  return (
    <Drawer title={`设备数字孪生 · ${dev.assetNo}`} width={720} open={!!dev} onClose={onClose}>
      <Space wrap style={{ marginBottom: 12 }}>
        {statusTag(dev.status)}
        <Tag color="purple">{t('common:m178')}{dev.ownerId != null ? '用户#' + dev.ownerId : '平台'}</Tag>
        <Tag>{t('common:m179')}{TYPE_LABEL[dev.assetType] || dev.assetType}</Tag>
      </Space>
      <Tabs
        items={[
          { key: 'overview', label: t('common:m180'), children: (
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label={t('common:m92')}>{TYPE_LABEL[dev.assetType] || dev.assetType}</Descriptions.Item>
              <Descriptions.Item label={t('common:m50')}>{product?.name || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common:m136')}>{product?.model || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common:m181')}>{brandName(dev.manufacturerId)}</Descriptions.Item>
              <Descriptions.Item label={t('common:m182')}>{dev.serialNumber || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common:m183')}>{dev.qrCode || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common:m168')}>{dev.assetNo} <span style={{ color: 'var(--muted)' }}>#{dev.id}</span></Descriptions.Item>
              <Descriptions.Item label={t('common:m169')}>{dev.createdAt || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common:m184')}>{t('common:m185')}</Descriptions.Item>
            </Descriptions>
          ) },
          { key: 'loc', label: t('common:m186'), children: (
            <div>
              <Alert type="warning" showIcon style={{ marginBottom: 10 }}
                message="实时定位数据待接入设备遥测（后端资产暂无经纬度字段），当前仅展示电子围栏规则。" />
              <Descriptions column={1} bordered size="small">
                <Descriptions.Item label={t('common:m187')}>{t('common:m188')}</Descriptions.Item>
                <Descriptions.Item label={t('common:m189')}><span style={{ color: 'var(--brand)' }}>{t('common:m190')}</span></Descriptions.Item>
                <Descriptions.Item label={t('common:m191')}>{t('common:m192')}</Descriptions.Item>
              </Descriptions>
            </div>
          ) },
          { key: 'auth', label: t('common:m193'), children: (
            <Table rowKey="f" pagination={false} size="small" dataSource={AUTH_MATRIX}
              columns={[
                { title: t('common:m194'), dataIndex: 'f' },
                { title: t('common:m140'), dataIndex: 'o', render: (v) => <Tag color="green">{v}</Tag> },
                { title: t('common:m195'), dataIndex: 'r', render: (v) => <Tag color={String(v).includes('隐藏') || v === '禁止' ? 'red' : 'blue'}>{v}</Tag> },
                { title: t('common:m196'), dataIndex: 'rule' },
              ]}
            />
          ) },
          { key: 'earn', label: t('common:m197'), children: (
            <Spin spinning={traceLoading}>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Card size="small">
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <strong>{t('common:m198')}</strong>
                    <span style={{ fontWeight: 700, color: 'var(--brand)' }}>{totalRevenue != null ? '¥' + totalRevenue : '—（待接入）'}</span>
                  </div>
                </Card>
                {vehicleOps.length === 0 && !traceLoading && <Empty description={t('common:m199')} />}
                {vehicleOps.map((op, i) => (
                  <Card key={op.id || i} size="small" style={{ marginBottom: 8 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <div>
                        <strong>{OPTYPE_LABEL[op.opType] || op.opType || '运营'}</strong>
                        <div style={{ fontSize: 12, color: 'var(--muted)' }}>{op.startedAt || ''}{op.note ? ' · ' + op.note : ''}</div>
                      </div>
                      <span style={{ fontWeight: 700, color: 'var(--brand)' }}>{op.revenue != null ? '¥' + op.revenue : '—'}</span>
                    </div>
                  </Card>
                ))}
              </Space>
            </Spin>
          ) },
          { key: 'repair', label: t('common:m200'), children: (
            <Spin spinning={traceLoading}>
              {trace?.maintenance?.length ? (
                <Table rowKey="id" pagination={false} size="small" dataSource={trace.maintenance} columns={[
                  { title: t('common:m201'), dataIndex: 'servicedAt' },
                  { title: t('common:m36'), dataIndex: 'mtype' },
                  { title: t('common:m202'), dataIndex: 'vendor' },
                  { title: t('common:m115'), dataIndex: 'cost', render: (v) => <Tag>{v != null ? '¥' + v : '—'}</Tag> },
                ]} />
              ) : !traceLoading ? <Empty description={t('common:m203')} /> : null}
            </Spin>
          ) },
          { key: 'transfer', label: t('common:m204'), children: (
            <div>
              <Alert type="info" showIcon style={{ margin: '0 0 10px' }}
                message="现值核算依赖真实购入成本——后端资产暂无 costPrice 字段，暂不展示现值；下方为真实产权转移链（按本资产过滤）。" />
              {assetTransfers.length === 0 ? (
                <Empty description={t('common:m205')} />
              ) : (
                <Table rowKey="id" pagination={false} size="small" dataSource={assetTransfers} columns={[
                  { title: t('common:m35'), dataIndex: 'transferredAt' },
                  { title: t('common:m36'), dataIndex: 'transferType', render: (v) => <Tag>{v}</Tag> },
                  { title: t('common:m37'), render: (_, r) => (r.fromUserId != null ? '用户#' + r.fromUserId : '平台') },
                  { title: t('common:m38'), render: (_, r) => (r.toUserId != null ? '用户#' + r.toUserId : '平台') },
                ]} />
              )}
            </div>
          ) },
          ...(dev.assetType === 'DRONE' ? [{ key: 'air', label: t('common:m206'), children: <DroneAirTab asset={dev} /> }] : []),
        ]}
      />
    </Drawer>
  );
}

// 无人机专属：飞行安全管控（N5 收敛为只读 + 跳转作业与安全管控）
// 本 Tab 不再有任何写按钮 / 本地演示种子；安全态与事件来自真实后端 drone-safety 接口，
// 锁机触发 / 解除、作业登记统一在「作业与安全管控（/drone-ops）」完成。
function DroneAirTab({ asset }) {
  const { t } = useTranslation(['common', 'drone']);
  const navigate = useNavigate();
  const [safety, setSafety] = useState(null); // 'NORMAL' | 'LOCKED'
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      getSafetyStatus(asset.id),
      listSafetyEvents(asset.id),
    ]).then(([st, ev]) => {
      if (!alive) return;
      setSafety(st);
      setEvents(Array.isArray(ev) ? ev : []);
    }).catch((e) => {
      if (alive) { message.error('加载无人机安全态失败：' + (e.message || '未知错误')); setSafety(null); setEvents([]); }
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [asset.id]);

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Alert type="info" showIcon
        message={<span>{t('drone:common.droneTabHint')} <Link to="/drone-ops">{t('drone:common.gotoOps')}</Link></span>} />

      <Descriptions column={2} bordered size="small">
        <Descriptions.Item label={t('common:m168')}>{asset.assetNo}</Descriptions.Item>
        <Descriptions.Item label={t('common:m36')}>{TYPE_LABEL[asset.assetType] || asset.assetType}</Descriptions.Item>
        <Descriptions.Item label={t('common:m182')}>{asset.serialNumber || '—'}</Descriptions.Item>
      </Descriptions>

      <Card size="small" title={<span>{t('common:m207')}{safety === 'LOCKED' ? <Tag color="red">{t('common:m208')}</Tag> : <Tag color="green">{t('common:m209')}</Tag>}</span>}>
        <Spin spinning={loading}>
          <Alert type={safety === 'LOCKED' ? 'error' : 'success'} showIcon
            message={safety === 'LOCKED'
              ? '已锁机：越界/失联/低电量等风险触发，禁止起飞（类比断缴锁车），须解除后方可放飞。'
              : '飞行安全正常：空域合规、链路在线、电量充足。'} />
        </Spin>
      </Card>

      <Card size="small" title={t('common:m210')}>
        <Table rowKey="id" pagination={false} size="small"
          dataSource={events}
          locale={{ emptyText: '该资产暂无安全事件' }}
          columns={[
            { title: t('common:m35'), dataIndex: 'createdAt', width: 160, render: (v) => <span style={{ fontSize: 12 }}>{v}</span> },
            { title: t('common:m36'), dataIndex: 'cause', render: (v) => <Tag>{CAUSE_LABEL[v] || v}</Tag> },
            { title: t('common:m211'), dataIndex: 'detail' },
            { title: t('common:m8'), dataIndex: 'status',
              render: (v) => <Tag color={v === 'RESOLVED' ? 'green' : 'red'}>{v === 'RESOLVED' ? '已解除' : '未解除'}</Tag> },
          ]} />
      </Card>

      <Button type="primary" block icon={<ArrowRightOutlined />} onClick={() => navigate('/drone-ops')}>
        {t('drone:common.gotoOps')}
      </Button>
    </Space>
  );
}

// 驾驶模式（与后端 /autonomy/drive-mode 约定的常见取值）
const DRIVE_MODE_OPTIONS = ['AUTO', 'ASSISTED', 'MANUAL', 'TELEOP'];

/**
 * 车辆（地面自动驾驶 / 换电）专属 Tab：聚合能源视图、电池绑定历史、自动驾驶模块
 * （驾驶模式 + 安全态 + 遥控进入/退出 + 安全锁机）与轨迹回放。全部对接真实后端，无 mock。
 * @param {{dev: Object}} props dev 为当前选中的车辆资产
 * @returns {JSX.Element}
 */
function VehicleIotTab({ dev }) {
  const { t } = useTranslation(['common', 'task']);
  const [energy, setEnergy] = useState(null);
  const [battery, setBattery] = useState(null);
  const [module, setModule] = useState(null); // { algoVersion, driveMode }
  const [safety, setSafety] = useState(null); // { state, ... }
  const [loading, setLoading] = useState(false);
  const [moduleForm] = Form.useForm();
  const [busy, setBusy] = useState('');

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      vehicleApi.getVehicleEnergy(dev.id).catch(() => null),
      vehicleApi.getVehicleBattery(dev.id).catch(() => null),
      vehicleApi.getAutonomyModule(dev.id).catch(() => null),
      vehicleApi.getAutonomySafety(dev.id).catch(() => null),
    ]).then(([e, b, m, s]) => { setEnergy(e); setBattery(b); setModule(m); setSafety(s); })
      .finally(() => setLoading(false));
  }, [dev.id]);
  useEffect(() => { load(); }, [load]);

  const onSetModule = async () => {
    let v;
    try { v = await moduleForm.validateFields(); } catch { return; }
    setBusy('module');
    try {
      await vehicleApi.setAutonomyModule(dev.id, { algoVersion: v.algoVersion, driveMode: v.driveMode });
      message.success(t('task:vehicle.autonomy.moduleSetSuccess'));
      load();
    } catch (e) {
      message.error(t('task:vehicle.autonomy.actionFailed', { message: e.message }));
    } finally { setBusy(''); }
  };

  const onDriveMode = async (driveMode) => {
    setBusy('drive');
    try {
      await vehicleApi.setDriveMode(dev.id, { driveMode });
      message.success(t('task:vehicle.autonomy.driveModeSetSuccess'));
      load();
    } catch (e) {
      message.error(t('task:vehicle.autonomy.actionFailed', { message: e.message }));
    } finally { setBusy(''); }
  };

  const onTeleop = async (enter) => {
    setBusy(enter ? 'teleopIn' : 'teleopOut');
    try {
      if (enter) await vehicleApi.enterTeleop(dev.id);
      else await vehicleApi.exitTeleop(dev.id);
      message.success(enter ? t('task:vehicle.autonomy.teleopEnterSuccess') : t('task:vehicle.autonomy.teleopExitSuccess'));
      load();
    } catch (e) {
      message.error(t('task:vehicle.autonomy.actionFailed', { message: e.message }));
    } finally { setBusy(''); }
  };

  const onLock = async () => {
    setBusy('lock');
    try {
      await vehicleApi.lockSafety(dev.id);
      message.success(t('task:vehicle.autonomy.lockSuccess'));
      load();
    } catch (e) {
      message.error(t('task:vehicle.autonomy.actionFailed', { message: e.message }));
    } finally { setBusy(''); }
  };

  const safetyState = safety ? (safety.state || safety.safetyState || safety.status) : null;
  const batteryId = energy ? (energy.currentBatteryId ?? (energy.currentBattery && energy.currentBattery.batteryId) ?? '—') : '—';
  const recentSwaps = energy ? (energy.recentSwaps || []) : [];
  const batteryBindings = battery ? (battery.bindings || (Array.isArray(battery) ? battery : [])) : [];

  return (
    <Spin spinning={loading}>
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {/* 能源视图 */}
        <Card size="small" title={t('task:vehicle.energy.title')}>
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label={t('task:vehicle.energy.currentBattery')}>{batteryId}</Descriptions.Item>
            <Descriptions.Item label={t('task:vehicle.energy.recentSwaps')}>
              {Array.isArray(recentSwaps) && recentSwaps.length
                ? `${recentSwaps.length} ${t('task:vehicle.energy.swapCount')}`
                : t('task:vehicle.energy.noSwap')}
            </Descriptions.Item>
          </Descriptions>
          {Array.isArray(recentSwaps) && recentSwaps.length > 0 && (
            <Table rowKey={(r, i) => r.id ?? i} pagination={false} size="small" style={{ marginTop: 10 }} dataSource={recentSwaps}
              columns={[
                { title: t('task:vehicle.energy.swapBattery'), dataIndex: 'batteryId', render: (v) => v || '—' },
                { title: t('common:m35'), dataIndex: 'swappedAt', render: (v) => v || '—' },
                { title: t('task:vehicle.energy.swapStation'), dataIndex: 'stationId', render: (v) => (v != null ? `#${v}` : '—') },
              ]} />
          )}
        </Card>

        {/* 电池绑定历史 */}
        <Card size="small" title={t('task:vehicle.energy.batteryHistory')}>
          {Array.isArray(batteryBindings) && batteryBindings.length > 0 ? (
            <Table rowKey={(r, i) => r.id ?? i} pagination={false} size="small" dataSource={batteryBindings}
              columns={[
                { title: t('task:vehicle.energy.swapBattery'), dataIndex: 'batteryId', render: (v) => v || '—' },
                { title: t('common:m35'), dataIndex: 'boundAt', render: (v) => v || '—' },
                { title: t('common:m8'), dataIndex: 'status', render: (v) => (v ? <Tag>{v}</Tag> : '—') },
              ]} />
          ) : (
            <Empty description={t('task:vehicle.energy.batteryHistoryEmpty')} />
          )}
        </Card>

        {/* 自动驾驶模块 + 安全态 */}
        <Card size="small" title={t('task:vehicle.autonomy.title')}
          extra={
            <Space>
              <Button size="small" icon={<SendOutlined />} loading={busy === 'teleopIn'} onClick={() => onTeleop(true)}>{t('task:vehicle.autonomy.teleopEnter')}</Button>
              <Button size="small" loading={busy === 'teleopOut'} onClick={() => onTeleop(false)}>{t('task:vehicle.autonomy.teleopExit')}</Button>
              <Button size="small" danger icon={<LockOutlined />} loading={busy === 'lock'} onClick={onLock}>{t('task:vehicle.autonomy.lock')}</Button>
            </Space>
          }>
          <Space wrap style={{ marginBottom: 12 }}>
            <Tag color="geekblue">{t('task:vehicle.autonomy.safetyState')}: {safetyState || '—'}</Tag>
            {module && <Tag color="blue">{t('task:vehicle.autonomy.algoVersion')}: {module.algoVersion || '—'}</Tag>}
            {module && <Tag>{t('task:vehicle.autonomy.driveMode')}: {module.driveMode || '—'}</Tag>}
          </Space>
          <Form layout="inline" form={moduleForm} initialValues={{ driveMode: module ? module.driveMode : 'AUTO', algoVersion: module ? module.algoVersion : '' }}>
            <Form.Item label={t('task:vehicle.autonomy.algoVersion')} name="algoVersion" rules={[{ required: true, message: t('task:vehicle.autonomy.algoVersionRequired') }]}>
              <Input placeholder="v1.2.0" style={{ width: 140 }} />
            </Form.Item>
            <Form.Item label={t('task:vehicle.autonomy.driveMode')} name="driveMode" rules={[{ required: true }]}>
              <Select style={{ width: 160 }} options={DRIVE_MODE_OPTIONS.map((m) => ({ label: t(`task:vehicle.autonomy.driveMode.${m}`), value: m }))} />
            </Form.Item>
            <Button type="primary" size="small" loading={busy === 'module'} onClick={onSetModule}>{t('task:vehicle.autonomy.moduleSet')}</Button>
            <Button size="small" style={{ marginLeft: 8 }} loading={busy === 'drive'} onClick={() => onDriveMode(moduleForm.getFieldValue('driveMode'))}>{t('task:vehicle.autonomy.driveModeSet')}</Button>
          </Form>
        </Card>

        {/* 轨迹回放 */}
        <TrajectoryPlayback assetId={dev.id} height={320} />
      </Space>
    </Spin>
  );
}
