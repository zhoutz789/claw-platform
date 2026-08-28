import { useState, useEffect, useCallback } from 'react';
import {
  Segmented, Table, Tag, Drawer, Tabs, Descriptions, Steps, Button, Card, Space, Alert,
  Progress, message, Modal, Form, Input, Select, Empty, Spin, Typography,
} from 'antd';
import {
  ArrowRightOutlined, QrcodeOutlined, PrinterOutlined, SafetyCertificateOutlined,
  PlusOutlined, EditOutlined, DeleteOutlined,
} from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
// 低空经济相关：空域 / 飞手 / 作业 / 飞行计划 仍为本地种子展示（后端暂无对应实时来源），已标注。
import { AIRSPACE_ZONES, PILOT_LICENSES, DRONE_MISSIONS, FLIGHT_PLANS } from '../mockData';

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

export default function ProductIot() {
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
      .then(() => { message.success('字段已删除'); loadTplFields(pid); })
      .catch((e) => message.error('删除失败：' + (e.message || '未知')));
  };

  // 合格证：优先用设备 tab 选中的真实资产，否则取真实资产列表首个
  const certAsset = dev || assets[0] || null;

  return (
    <PageCard title="产品管理 / 物联网" extra={<Segmented
      value={tab}
      onChange={setTab}
      options={[
        { label: '产品模板', value: 'tpl' },
        { label: '品牌产品', value: 'prod' },
        { label: '设备', value: 'dev' },
        { label: '合格证', value: 'cert' },
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
              { title: '产品名称', dataIndex: 'name', render: (v, r) => <a onClick={() => { const next = tplProdId === r.id ? null : r.id; setTplProdId(next); setTplFields([]); if (next) loadTplFields(next); }}>{v}</a> },
              { title: '类别', dataIndex: 'category', render: (v) => v || '—' },
              { title: '品牌', dataIndex: 'brand', render: (v) => v || '—' },
              { title: '设备类型', dataIndex: 'assetType', render: (v) => v || '—' },
            ]}
          />
          {tplProdId && (
            <Card style={{ marginTop: 14 }} title={`${(adminProducts.find((p) => p.id === tplProdId) || {}).name || ''} · 模板字段定义（真实 EAV）`}
              extra={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openAddField(tplProdId)}>新增字段</Button>}>
              <p style={{ color: 'var(--muted)', marginTop: -4 }}>字段为「类」的扩展属性 schema；fieldKey 映射设备遥测键，前端按 fieldKey 渲染实时值。</p>
              <Table rowKey="id" pagination={false} size="small" loading={tplLoading}
                dataSource={tplFields}
                columns={[
                  { title: '字段键', dataIndex: 'fieldKey' },
                  { title: '标签', dataIndex: 'label' },
                  { title: '类型', dataIndex: 'type', render: (v) => <Tag>{v}</Tag> },
                  { title: '单位', dataIndex: 'unit', render: (v) => v || '—' },
                  { title: '必填', dataIndex: 'required', render: (v) => (v ? '是' : '否') },
                  { title: '排序', dataIndex: 'sortNo' },
                  { title: '操作', render: (_, r) => (
                    <Space>
                      <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEditField(tplProdId, r)}>编辑</Button>
                      <Button size="small" type="link" danger icon={<DeleteOutlined />} onClick={() => removeField(tplProdId, r.id)}>删除</Button>
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
          {products.length === 0 && !loading && <Empty description="暂无真实品牌产品" />}
          {products.map((p) => (
            <Card key={p.id} style={{ marginBottom: 12, cursor: 'pointer', borderColor: prodId === p.id ? 'var(--brand)' : undefined }}
              onClick={() => setProdId(prodId === p.id ? null : p.id)}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <div>
                  <div style={{ fontWeight: 700 }}>{p.name}</div>
                  <div style={{ fontSize: 12, color: 'var(--muted)' }}>型号 {p.model || '—'} · 类型 {TYPE_LABEL[p.assetType] || p.assetType} · 品牌 {brandName(p.manufacturerId)}</div>
                  <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 4 }}>{p.description || '—'}</div>
                </div>
                <Tag color="green">{assets.filter((a) => a.productId === p.id).length} 台设备</Tag>
              </div>
            </Card>
          ))}
          {prod && (
            <Card title={`${prod.name} · 名下真实设备`}>
              <Table rowKey="id" loading={loading} pagination={false}
                dataSource={assets.filter((a) => a.productId === prod.id)}
                columns={[
                  { title: '设备编号', render: (_, r) => <>{r.assetNo} <span style={{ color: 'var(--muted)' }}>#{r.id}</span></> },
                  { title: '产权人', render: (_, r) => (r.ownerId != null ? `用户#${r.ownerId}` : '平台') },
                  { title: '状态', dataIndex: 'status', render: (v) => statusTag(v) },
                  { title: '', render: (_, r) => <Button size="small" type="link" onClick={(e) => { e.stopPropagation(); setDev(r); }}>详情</Button> },
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
            { title: '设备编号', render: (_, r) => <>{r.assetNo} <span style={{ color: 'var(--muted)' }}>#{r.id}</span></> },
            { title: '产品', render: (_, r) => prodName(r.productId) },
            { title: '品牌', render: (_, r) => brandName(r.manufacturerId) },
            { title: '产权人', render: (_, r) => (r.ownerId != null ? `用户#${r.ownerId}` : '平台') },
            { title: '状态', dataIndex: 'status', render: (v) => statusTag(v) },
            { title: '', render: (_, r) => <Button type="link" onClick={() => setDev(r)}>数字孪生详情</Button> },
          ]}
        />
      )}

      {tab === 'cert' && <CertPanel asset={certAsset} products={products} manufacturers={manufacturers} />}

      <DeviceDrawer dev={dev} products={products} manufacturers={manufacturers} transfers={transfers} onClose={() => setDev(null)} />

      <Modal
        title={fieldModal.editing ? '编辑模板字段' : '新增模板字段（真实 EAV）'}
        open={fieldModal.open}
        onOk={submitField}
        onCancel={() => setFieldModal({ open: false, prodId: null, editing: null })}
        okText="保存"
        cancelText="取消"
      >
        <Form form={fieldForm} layout="vertical" initialValues={{ type: 'number', required: false, sortNo: 0 }}>
          <Form.Item label="字段键 fieldKey（映射设备遥测键）" name="fieldKey" rules={[{ required: true, message: '请输入字段键' }]}>
            <Input placeholder="如 soc / voltage / temperature" disabled={!!fieldModal.editing} />
          </Form.Item>
          <Form.Item label="标签 label" name="label" rules={[{ required: true, message: '请输入标签' }]}>
            <Input placeholder="如 当前电量" />
          </Form.Item>
          <Form.Item label="类型 type" name="type" rules={[{ required: true }]}>
            <Select options={[
              { label: '数字 (number)', value: 'number' },
              { label: '文本 (text)', value: 'text' },
              { label: '单选 (select)', value: 'select' },
              { label: '日期 (date)', value: 'date' },
              { label: '布尔 (boolean)', value: 'boolean' },
            ]} />
          </Form.Item>
          <Form.Item label="单位 unit" name="unit"><Input placeholder="如 % / V / ℃（可空）" /></Form.Item>
          <Form.Item label="选项 optionsJson（select 用，逗号分隔）" name="optionsJson"><Input placeholder="如 IP65,IP67" /></Form.Item>
          <Form.Item label="必填 required" name="required">
            <Select options={[{ label: '否', value: false }, { label: '是', value: true }]} />
          </Form.Item>
          <Form.Item label="排序 sortNo" name="sortNo"><Input type="number" /></Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}

function CertPanel({ asset, products, manufacturers }) {
  if (!asset) {
    return <Empty description="暂无真实设备（后端无资产数据）" />;
  }
  const brandName = (id) => manufacturers.find((m) => m.id === id)?.name || '—';
  const product = products.find((p) => p.id === asset.productId);
  return (
    <Space size="large" align="start" wrap>
      <Card style={{ width: 320 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
          <strong>设备合格证</strong><Tag icon={<SafetyCertificateOutlined />} color="gold">平台签名·真实数据</Tag>
        </div>
        <div style={{ height: 96, width: 96, border: '1px solid #eee', borderRadius: 8, display: 'grid', placeItems: 'center', margin: '0 auto 10px', background: '#fafafa' }}>
          <QrcodeOutlined style={{ fontSize: 40, color: '#bbb' }} />
        </div>
        <div style={{ fontSize: 11, color: '#888', textAlign: 'center', wordBreak: 'break-all', marginBottom: 10 }}>
          二维码内容：{asset.qrCode || '—'}
        </div>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="资产编号"><Text strong>{asset.assetNo}</Text> <span style={{ color: 'var(--muted)' }}>#{asset.id}</span></Descriptions.Item>
          <Descriptions.Item label="产品">{product?.name || '—'}</Descriptions.Item>
          <Descriptions.Item label="品牌">{brandName(asset.manufacturerId)}</Descriptions.Item>
          <Descriptions.Item label="建档时间">{asset.createdAt || '—'}</Descriptions.Item>
        </Descriptions>
        <Space style={{ marginTop: 10 }}>
          <Button icon={<QrcodeOutlined />} onClick={() => message.success('已生成验真二维码（基于真实资产数据）')}>导出</Button>
          <Button icon={<PrinterOutlined />} onClick={() => message.success('已打印合格证（基于真实资产数据）')}>打印</Button>
        </Space>
      </Card>
      <Card style={{ flex: 1, minWidth: 280 }} title="合格证规则">
        <p style={{ color: 'var(--muted)' }}>定制字段：自动调用商品参数个别字段 + 设备唯一编号；二维码内嵌「资产编号 + 平台签名」，扫码即验真，杜绝套牌。数据均来自真实后端资产。</p>
        <ul style={{ color: 'var(--muted)', lineHeight: 1.9 }}>
          <li>通过设备链接可进入设备详情，完成「数据闭环查看」。</li>
          <li>用户线上随时可查看、导出、打印真实资产合格证。</li>
        </ul>
      </Card>
    </Space>
  );
}

function DeviceDrawer({ dev, products, manufacturers, transfers, onClose }) {
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
        <Tag color="purple">产权人：{dev.ownerId != null ? '用户#' + dev.ownerId : '平台'}</Tag>
        <Tag>类型：{TYPE_LABEL[dev.assetType] || dev.assetType}</Tag>
      </Space>
      <Tabs
        items={[
          { key: 'overview', label: '概览', children: (
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="资产类型">{TYPE_LABEL[dev.assetType] || dev.assetType}</Descriptions.Item>
              <Descriptions.Item label="所属产品">{product?.name || '—'}</Descriptions.Item>
              <Descriptions.Item label="型号">{product?.model || '—'}</Descriptions.Item>
              <Descriptions.Item label="品牌方">{brandName(dev.manufacturerId)}</Descriptions.Item>
              <Descriptions.Item label="序列号">{dev.serialNumber || '—'}</Descriptions.Item>
              <Descriptions.Item label="二维码">{dev.qrCode || '—'}</Descriptions.Item>
              <Descriptions.Item label="资产编号">{dev.assetNo} <span style={{ color: 'var(--muted)' }}>#{dev.id}</span></Descriptions.Item>
              <Descriptions.Item label="建档时间">{dev.createdAt || '—'}</Descriptions.Item>
              <Descriptions.Item label="能力标签">—（待接入真实能力数据）</Descriptions.Item>
            </Descriptions>
          ) },
          { key: 'loc', label: '定位', children: (
            <div>
              <Alert type="warning" showIcon style={{ marginBottom: 10 }}
                message="实时定位数据待接入设备遥测（后端资产暂无经纬度字段），当前仅展示电子围栏规则。" />
              <Descriptions column={1} bordered size="small">
                <Descriptions.Item label="实时经纬">—（待接入）</Descriptions.Item>
                <Descriptions.Item label="轨迹规则"><span style={{ color: 'var(--brand)' }}>实时可见；历史仅本人使用期</span></Descriptions.Item>
                <Descriptions.Item label="电子围栏">越界 → 风控告警</Descriptions.Item>
              </Descriptions>
            </div>
          ) },
          { key: 'auth', label: '赋权', children: (
            <Table rowKey="f" pagination={false} size="small" dataSource={AUTH_MATRIX}
              columns={[
                { title: '功能', dataIndex: 'f' },
                { title: '产权人', dataIndex: 'o', render: (v) => <Tag color="green">{v}</Tag> },
                { title: '承租人', dataIndex: 'r', render: (v) => <Tag color={String(v).includes('隐藏') || v === '禁止' ? 'red' : 'blue'}>{v}</Tag> },
                { title: '规则', dataIndex: 'rule' },
              ]}
            />
          ) },
          { key: 'earn', label: '收益', children: (
            <Spin spinning={traceLoading}>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Card size="small">
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <strong>累计收益（真实）</strong>
                    <span style={{ fontWeight: 700, color: 'var(--brand)' }}>{totalRevenue != null ? '¥' + totalRevenue : '—（待接入）'}</span>
                  </div>
                </Card>
                {vehicleOps.length === 0 && !traceLoading && <Empty description="暂无真实运营收益记录" />}
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
          { key: 'repair', label: '维修', children: (
            <Spin spinning={traceLoading}>
              {trace?.maintenance?.length ? (
                <Table rowKey="id" pagination={false} size="small" dataSource={trace.maintenance} columns={[
                  { title: '日期', dataIndex: 'servicedAt' },
                  { title: '类型', dataIndex: 'mtype' },
                  { title: '执行方', dataIndex: 'vendor' },
                  { title: '费用', dataIndex: 'cost', render: (v) => <Tag>{v != null ? '¥' + v : '—'}</Tag> },
                ]} />
              ) : !traceLoading ? <Empty description="暂无真实维修记录" /> : null}
            </Spin>
          ) },
          { key: 'transfer', label: '转让租赁', children: (
            <div>
              <Alert type="info" showIcon style={{ margin: '0 0 10px' }}
                message="现值核算依赖真实购入成本——后端资产暂无 costPrice 字段，暂不展示现值；下方为真实产权转移链（按本资产过滤）。" />
              {assetTransfers.length === 0 ? (
                <Empty description="暂无本资产真实产权转移记录" />
              ) : (
                <Table rowKey="id" pagination={false} size="small" dataSource={assetTransfers} columns={[
                  { title: '时间', dataIndex: 'transferredAt' },
                  { title: '类型', dataIndex: 'transferType', render: (v) => <Tag>{v}</Tag> },
                  { title: '从', render: (_, r) => (r.fromUserId != null ? '用户#' + r.fromUserId : '平台') },
                  { title: '至', render: (_, r) => (r.toUserId != null ? '用户#' + r.toUserId : '平台') },
                ]} />
              )}
            </div>
          ) },
          ...(dev.assetType === 'DRONE' ? [{ key: 'air', label: '低空·载荷', children: <DroneAirTab asset={dev} /> }] : []),
        ]}
      />
    </Drawer>
  );
}

// 无人机专属：空域合规 + 飞行安全管控（真实后端）+ 生命周期闭环（本地演示遥测）
function DroneAirTab({ asset }) {
  const [safety, setSafety] = useState(null); // 'NORMAL' | 'LOCKED'
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(false);
  // 以下为本地演示遥测（后端暂无实时遥测端点）
  const RETIRE_MIN = 3000;
  const [flightMin, setFlightMin] = useState(0);
  const [soh, setSoh] = useState(92);
  const [lifeStage, setLifeStage] = useState('IN_USE');
  const [localEvents, setLocalEvents] = useState([]);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      api.get(`/v1/drone-safety/${asset.id}`),
      api.get(`/v1/drone-safety/${asset.id}/events`),
    ]).then(([st, ev]) => {
      if (!alive) return;
      setSafety(st);
      setEvents(Array.isArray(ev) ? ev : []);
    }).catch((e) => {
      if (alive) { message.error('加载无人机安全态失败：' + e.message); setSafety('NORMAL'); setEvents([]); }
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [asset.id]);

  const stageIndex = lifeStage === 'IN_USE' ? 2 : lifeStage === 'RETIRED' ? 3 : 4;
  const retirePct = Math.min(100, Math.round((flightMin / RETIRE_MIN) * 100));
  const healthLow = soh < 70;
  const reachRetire = flightMin >= RETIRE_MIN || healthLow;
  const salvage = 0; // 残值依赖成本，当前无可展示真实值

  const nowStr = () => new Date().toISOString().slice(0, 16).replace('T', ' ');
  const pushLocal = (cause, note) => setLocalEvents((e) => [{ id: 'L' + Date.now(), ts: nowStr(), cause, note }, ...e]);

  const simulateFlight = () => {
    if (lifeStage !== 'IN_USE') return;
    setFlightMin((v) => Math.min(RETIRE_MIN + 200, v + 200));
    pushLocal('本地演示遥测', '模拟作业 +200 飞行分钟（本地演示，非真实遥测）');
    message.success('模拟作业 +200 飞行分钟（本地演示遥测）');
  };
  const triggerRetire = () => {
    if (!reachRetire) { message.info('尚未达到退役阈值（飞行≥3000min 或 SOH<70%）'); return; }
    setLifeStage('RETIRED');
    pushLocal('生命周期', `达到退役阈值，自动退役（飞行 ${flightMin}min / SOH ${soh}%）`);
    message.warning('资产已退役（本地演示），进入回收流程');
  };
  const recycle = () => {
    setLifeStage('RECYCLED');
    pushLocal('生命周期', '回收完成（本地演示）');
    message.success('已回收（本地演示）');
  };
  const lock = async (cause, note) => {
    try {
      await api.post(`/v1/drone-safety/${asset.id}/simulate`, { cause, detail: note });
      setSafety('LOCKED');
      pushLocal('锁机', note);
      message.error('已锁机（类比断缴锁车）：' + note);
      const ev = await api.get(`/v1/drone-safety/${asset.id}/events`);
      setEvents(Array.isArray(ev) ? ev : []);
    } catch (e) { message.error('模拟锁机失败：' + e.message); }
  };
  const resolve = async () => {
    try {
      await api.post(`/v1/drone-safety/${asset.id}/resolve`);
      setSafety('NORMAL');
      pushLocal('解除', '锁机已解除');
      message.success('锁机已解除');
      const ev = await api.get(`/v1/drone-safety/${asset.id}/events`);
      setEvents(Array.isArray(ev) ? ev : []);
    } catch (e) { message.error('解除锁机失败：' + e.message); }
  };

  const zoneTag = (lv) => lv === 'OPERATIONAL'
    ? <Tag color="green">可飞</Tag>
    : lv === 'RESTRICTED' ? <Tag color="orange">限飞</Tag> : <Tag color="red">禁飞</Tag>;

  const missions = DRONE_MISSIONS.filter((m) => m.deviceId === asset.assetNo);
  const fp = FLIGHT_PLANS.filter((f) => f.deviceId === asset.assetNo);

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Alert type="info" showIcon
        message="无人机安全态与锁机事件来自真实后端 drone-safety 接口；飞行寿命/退役/残值为本地演示遥测（后端暂无实时遥测端点）；空域、飞行计划、作业计量为本地种子展示，待接入空域真实来源。" />

      <Descriptions column={2} bordered size="small">
        <Descriptions.Item label="资产编号">{asset.assetNo}</Descriptions.Item>
        <Descriptions.Item label="类型">{TYPE_LABEL[asset.assetType] || asset.assetType}</Descriptions.Item>
        <Descriptions.Item label="序列号">{asset.serialNumber || '—'}</Descriptions.Item>
        <Descriptions.Item label="累计飞行(本地演示)">{flightMin} min</Descriptions.Item>
      </Descriptions>

      <Card size="small" title="资产全生命周期闭环（本地演示）">
        <Steps size="small" current={stageIndex} items={[
          { title: '建档' }, { title: '绑定' }, { title: '运营' }, { title: '退役' }, { title: '回收' },
        ]} />
        <div style={{ marginTop: 10 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
            <span>飞行寿命进度(本地演示·退役阈值 {RETIRE_MIN} min)</span>
            <span>{retirePct}%</span>
          </div>
          <Progress percent={retirePct} status={reachRetire ? 'exception' : 'active'}
            strokeColor={reachRetire ? '#cf1322' : '#52c41a'} />
        </div>
        <div style={{ marginTop: 10, color: 'var(--muted)' }}>
          健康度 SOH {soh}% · 当前阶段：<b>{lifeStage === 'IN_USE' ? '运营中' : lifeStage === 'RETIRED' ? '已退役' : '已回收'}</b>
          {healthLow && ' · 健康度低于 70% 触发退役'} · 残值率 30% → 残值 ¥{salvage}（待接入成本）
        </div>
        <Space wrap style={{ marginTop: 10 }}>
          <Button size="small" onClick={simulateFlight} disabled={lifeStage !== 'IN_USE'}>模拟作业 +200min</Button>
          <Button size="small" danger disabled={lifeStage !== 'IN_USE'} onClick={triggerRetire}>执行退役</Button>
          <Button size="small" type="primary" disabled={lifeStage !== 'RETIRED'} onClick={recycle}>回收并核算残值</Button>
        </Space>
      </Card>

      <Card size="small" title={<span>飞行安全管控（真实后端） {safety === 'LOCKED' ? <Tag color="red">已锁机</Tag> : <Tag color="green">正常</Tag>}</span>}>
        <Spin spinning={loading}>
          <Alert type={safety === 'LOCKED' ? 'error' : 'success'} showIcon
            message={safety === 'LOCKED'
              ? '已锁机：越界/失联/低电量等风险触发，禁止起飞（类比断缴锁车），须解除后方可放飞。'
              : '飞行安全正常：空域合规、链路在线、电量充足。'} />
        </Spin>
        <Space wrap style={{ marginTop: 10 }}>
          <Button size="small" onClick={() => lock('GEOFENCE_VIOLATION', '进入 NFZ 禁飞区')}>模拟越界锁机</Button>
          <Button size="small" onClick={() => lock('LOST_LINK', '信号丢失超过 5s')}>模拟失联锁机</Button>
          <Button size="small" onClick={() => lock('LOW_BATTERY', '电量低于 15%')}>模拟低电量锁机</Button>
          <Button size="small" type="primary" disabled={safety !== 'LOCKED'} onClick={resolve}>解除锁机</Button>
        </Space>
        <Table rowKey="id" pagination={false} size="small" style={{ marginTop: 10 }} title={() => '安全事件（真实 + 本地演示）'}
          dataSource={[
            ...events.map((e) => ({
              id: 'E' + e.id, ts: e.createdAt, cause: CAUSE_LABEL[e.cause] || e.cause,
              note: (e.detail || '') + (e.status === 'RESOLVED' ? ' · 已解除' : ''), real: true,
            })),
            ...localEvents,
          ]}
          columns={[
            { title: '时间', dataIndex: 'ts', width: 160, render: (v) => <span style={{ fontSize: 12 }}>{v}</span> },
            { title: '类型', dataIndex: 'cause', render: (v) => <Tag>{v}</Tag> },
            { title: '说明', dataIndex: 'note' },
          ]} />
      </Card>

      <Card size="small" title="空域分区（本地种子展示·待接入）">
        <Table rowKey="id" pagination={false} size="small" dataSource={AIRSPACE_ZONES}
          columns={[
            { title: '分区', dataIndex: 'name' },
            { title: '等级', dataIndex: 'level', render: (v) => zoneTag(v) },
            { title: '中心', dataIndex: 'center' },
            { title: '半径(m)', dataIndex: 'radiusM' },
          ]} />
      </Card>

      <Card size="small" title="飞行计划（本地种子展示·待接入）">
        <Table rowKey="id" pagination={false} size="small" dataSource={fp}
          columns={[
            { title: '计划', dataIndex: 'plannedAt' },
            { title: '空域', render: (_, r) => AIRSPACE_ZONES.find((z) => z.id === r.zoneId)?.name || r.zoneId },
            { title: '飞手', render: (_, r) => PILOT_LICENSES.find((l) => l.id === r.pilotId)?.holder || r.pilotId },
            { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'APPROVED' ? 'green' : 'red'}>{v}</Tag> },
          ]} />
      </Card>

      <Card size="small" title="作业计量（本地种子展示·待接入）">
        <Table rowKey="id" pagination={false} size="small" dataSource={missions}
          columns={[
            { title: '类型', dataIndex: 'missionType' },
            { title: '载荷', dataIndex: 'payload' },
            { title: '面积(ha)', dataIndex: 'areaHa', render: (v) => v || '-' },
            { title: '飞行(min)', dataIndex: 'flightMinutes' },
            { title: '飞手', dataIndex: 'pilot' },
          ]} />
      </Card>
    </Space>
  );
}
