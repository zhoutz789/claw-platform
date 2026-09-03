import { useState, useEffect, useRef } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Alert, Button, Form, Input, InputNumber, Modal, Select, Table, Tag, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { SearchOutlined, PlusOutlined, DeleteOutlined, EditOutlined, PictureOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
// 设备电子围栏真实后端封装（C2 裁定：围栏落到 /v1/iot/geofences，与平台级 airspace_zones 无关）
import { listGeofences, createGeofence, updateGeofence, deleteGeofence } from '../api/drone';
import { useDroneError } from '../components/droneShared';

/* 设备状态映射（后端枚举 → 中文 + 颜色） */
const STATUS_LABEL = {
  IN_STOCK: '在库', IN_USE: '使用中', SHARED: '共享中', REPAIR: '维修中',
  DISABLED: '停用', RETIRED: '退役', RECYCLED: '回收中', SCRAPPED: '已报废',
};
const statusTag = (s) => <span className={'tag ' + (s === 'REPAIR' ? 'orange' : s === 'IN_USE' || s === 'SHARED' ? 'green' : 'gray')}>{STATUS_LABEL[s] || s || '—'}</span>;

/* 实时数据源（与产品「实时API绑定」对应） */
const API_SOURCES = ['定位', '速度', '当前电量', '电压', '温度', '湿度', '风力', '高度', 'SOC'];
const ICONS = {
  定位: ['📍', '#1677ff'], 速度: ['🚀', '#722ed1'], 当前电量: ['🔋', '#18a058'],
  电压: ['⚡', '#fa8c16'], 温度: ['🌡', '#eb2f96'], 湿度: ['💧', '#13c2c 2'],
  风力: ['🌀', '#1890ff'], 高度: ['📏', '#2f54eb'], SOC: ['🔋', '#18a058'],
};

/* 设备详情左侧菜单（可在「菜单管理」中隐藏不用的项） */
const MENUS = [
  { key: 'overview', label: '概况' },
  { key: 'rt', label: '实时数据' },
  { key: 'track', label: '历史轨迹回放' },
  { key: 'fence', label: '电子围栏设置' },
  { key: 'revenue', label: '收益中心' },
  { key: 'maint', label: '维修记录' },
  { key: 'video', label: '录像记录' },
  { key: 'transfer', label: '转让记录' },
  { key: 'ops', label: '设备操作设定' },
  { key: 'cert', label: '合格证' },
];

const ALL_API = ['定位', '速度', '当前电量', '电压', '温度', '湿度', '风力', '高度'];

export default function ProductCenter() {
  const navigate = useNavigate();
  const { t } = useTranslation(['common', 'drone']);
  const { report } = useDroneError();
  const [fenceForm] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [products, setProducts] = useState([]);
  const [manufacturers, setManufacturers] = useState([]);
  const [assets, setAssets] = useState([]);
  const [q, setQ] = useState('');

  // 产品新建/编辑
  const [editOpen, setEditOpen] = useState(false);
  const [editId, setEditId] = useState(null);
  const [editName, setEditName] = useState('');
  const [editInterval, setEditInterval] = useState(30);
  const [editFields, setEditFields] = useState([]); // [{name,type,opts,api,apiSource}]
  const [editCategory, setEditCategory] = useState('');
  const [editBrand, setEditBrand] = useState('');
  const [editAssetType, setEditAssetType] = useState('');
  const [editManufacturerId, setEditManufacturerId] = useState(null);

  // 设备弹窗
  const [devListOpen, setDevListOpen] = useState(false);
  const [curProduct, setCurProduct] = useState(null);
  const [devListQ, setDevListQ] = useState('');

  // 设备详情
  const [devOpen, setDevOpen] = useState(false);
  const [curDev, setCurDev] = useState(null);
  const [telemetry, setTelemetry] = useState(null);
  const [devRight, setDevRight] = useState('overview');
  const [menuVisible, setMenuVisible] = useState({});
  const [mngOpen, setMngOpen] = useState(false);
  const [maintOpen, setMaintOpen] = useState(false);
  const [curMaint, setCurMaint] = useState(null);
  // 设备电子围栏（真实后端 /v1/iot/geofences，ownerType=ASSET，与平台级 airspace_zones 无关）
  const [fences, setFences] = useState([]);
  const [fenceLoading, setFenceLoading] = useState(false);
  const [fenceModal, setFenceModal] = useState({ open: false, editing: null });
  const [fenceSaving, setFenceSaving] = useState(false);
  const liveTimer = useRef(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      api.get('/v1/admin/manufacturer/products'),
      api.get('/v1/admin/manufacturer/manufacturers'),
      api.get('/v1/assets'),
    ]).then(([p, m, a]) => {
      if (!alive) return;
      setProducts(p || []);
      setManufacturers(m || []);
      setAssets(a || []);
    }).catch(() => {
      if (alive) {
        message.error(t('common:m1'));
        setProducts([]); setManufacturers([]); setAssets([]);
      }
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  useEffect(() => { aliveRef.current = true; return () => { aliveRef.current = false; }; }, []);

  // 实时遥测轮询
  useEffect(() => {
    if (!devOpen || !curDev) return undefined;
    const pull = () => {
      api.get(`/v1/assets/${curDev.id}/telemetry`).then((d) => {
        if (aliveRef.current) setTelemetry(d || {});
      }).catch(() => {});
    };
    pull();
    liveTimer.current = setInterval(pull, 2000);
    return () => { if (liveTimer.current) clearInterval(liveTimer.current); };
    // eslint-disable-next-line
  }, [devOpen, curDev]);

  // 设备电子围栏：进入 fence 菜单时按当前设备加载（与平台级空域分区无关）
  useEffect(() => {
    if (devRight !== 'fence' || !curDev) return undefined;
    let alive = true;
    setFenceLoading(true);
    listGeofences({ ownerType: 'ASSET', ownerId: curDev.id })
      .then((list) => { if (alive) setFences(Array.isArray(list) ? list : []); })
      .catch((e) => { if (alive) { report(e, 'drone:fence.msg.loadFailed'); setFences([]); } })
      .finally(() => { if (alive) setFenceLoading(false); });
    return () => { alive = false; };
  }, [devRight, curDev, report]);

  const filteredProducts = products.filter((p) =>
    !q || (p.name + (p.category || '') + (p.brand || '')).toLowerCase().includes(q.toLowerCase()));

  const devicesOf = (pid) => assets.filter((a) => a.productId === pid);

  // 从 paramsJson 解析动态字段（原型 product.fields）
  const parseFields = (p) => {
    try { const j = JSON.parse(p.paramsJson || '[]'); return Array.isArray(j) ? j : []; }
    catch { return []; }
  };
  const apiFields = (p) => parseFields(p).filter((f) => f.api);

  /* ---------- 产品 CRUD ---------- */
  const openEdit = (p) => {
    if (p) {
      setEditId(p.id);
      setEditName(p.name || '');
      setEditInterval(p.reportIntervalSeconds || 30);
      setEditCategory(p.category || '');
      setEditBrand(p.brand || '');
      setEditAssetType(p.assetType || '');
      setEditManufacturerId(p.manufacturerId || (manufacturers[0] && manufacturers[0].id) || null);
      setEditFields(parseFields(p));
    } else {
      setEditId(null);
      setEditName(''); setEditInterval(30);
      setEditCategory(''); setEditBrand(''); setEditAssetType('');
      setEditManufacturerId(manufacturers[0] ? manufacturers[0].id : null);
      setEditFields([{ name: '额定功率', type: 'text', opts: '', api: false, apiSource: '' },
        { name: '当前电量', type: 'number', opts: '', api: true, apiSource: '当前电量' }]);
    }
    setEditOpen(true);
  };
  const addField = () => setEditFields((prev) => [...prev, { name: '', type: 'text', opts: '', api: false, apiSource: '' }]);
  const onApiChange = (i, v) => setEditFields((prev) => prev.map((f, j) => (j === i ? { ...f, api: !!v, apiSource: v } : f)));

  const saveProduct = () => {
    if (!editName.trim()) { message.warning(t('common:m2')); return; }
    const fields = editFields.filter((f) => f.name.trim()).map((f) => ({
      name: f.name.trim(), type: f.type, opts: f.type === 'select' ? f.opts : '', api: f.api, apiSource: f.api ? f.apiSource : '',
    }));
    const body = {
      name: editName.trim(),
      category: editCategory || null,
      brand: editBrand || null,
      assetType: editAssetType || null,
      manufacturerId: editManufacturerId,
      reportIntervalSeconds: editInterval,
      paramsJson: JSON.stringify(fields),
    };
    const req = editId
      ? api.put(`/v1/admin/manufacturer/products/${editId}`, body)
      : api.post('/v1/admin/manufacturer/products', body);
    req.then(() => {
      message.success(editId ? '产品已更新' : '产品已创建');
      setEditOpen(false);
      return api.get('/v1/admin/manufacturer/products');
    }).then((p) => setProducts(p || [])).catch((e) => message.error('保存失败：' + e.message));
  };
  const delProduct = (p) => {
    if (!window.confirm('删除产品将移除其全部字段定义与设备关联，确认？')) return;
    api.delete(`/v1/admin/manufacturer/products/${p.id}`)
      .then(() => { message.success(t('common:m3')); setProducts(products.filter((x) => x.id !== p.id)); })
      .catch((e) => message.error('删除失败：' + e.message));
  };

  /* ---------- 设备电子围栏（真实后端 /v1/iot/geofences） ---------- */
  // 多边形 WKT 校验：要求 POLYGON/LINESTRING 等前缀、括号成对、至少 3 个坐标对。
  const validateWkt = (rule, value) => {
    if (!value || !value.trim()) return Promise.reject(new Error(t('drone:fence.err.invalid')));
    const v = value.trim().toUpperCase();
    if (!/^(POLYGON|LINESTRING|MULTIPOINT|MULTIPOLYGON)/.test(v)) {
      return Promise.reject(new Error(t('drone:fence.err.invalid')));
    }
    const open = (value.match(/\(/g) || []).length;
    const close = (value.match(/\)/g) || []).length;
    if (open === 0 || open !== close) return Promise.reject(new Error(t('drone:fence.err.invalid')));
    const pairs = value.match(/[-+]?\d*\.?\d+\s+[-+]?\d*\.?\d+/g) || [];
    if (pairs.length < 3) return Promise.reject(new Error(t('drone:fence.err.invalid')));
    return Promise.resolve();
  };

  const openFenceCreate = () => {
    fenceForm.resetFields();
    setFenceModal({ open: true, editing: null });
  };

  const openFenceEdit = (f) => {
    fenceForm.setFieldsValue({
      name: f.name || '',
      fenceType: f.fenceType || 'RADIUS',
      centerLat: f.centerLat,
      centerLng: f.centerLng,
      radiusM: f.radiusM,
      polygonWkt: f.polygonWkt || '',
      triggerAction: f.triggerAction || 'ALERT',
      status: f.status || 'ENABLED',
    });
    setFenceModal({ open: true, editing: f });
  };

  const submitFence = async () => {
    let v;
    try { v = await fenceForm.validateFields(); } catch { return; }
    const base = {
      ownerType: 'ASSET',
      ownerId: curDev.id,
      name: v.name,
      fenceType: v.fenceType,
      triggerAction: v.triggerAction || 'ALERT',
    };
    const body = v.fenceType === 'RADIUS'
      ? { ...base, centerLat: Number(v.centerLat), centerLng: Number(v.centerLng), radiusM: Number(v.radiusM) }
      : { ...base, polygonWkt: (v.polygonWkt || '').trim() };
    setFenceSaving(true);
    try {
      if (fenceModal.editing) {
        const patch = { name: v.name, triggerAction: v.triggerAction || 'ALERT', status: v.status };
        if (v.fenceType === 'RADIUS') {
          patch.centerLat = Number(v.centerLat);
          patch.centerLng = Number(v.centerLng);
          patch.radiusM = Number(v.radiusM);
        } else {
          patch.polygonWkt = (v.polygonWkt || '').trim();
        }
        await updateGeofence(fenceModal.editing.id, patch);
        message.success(t('drone:fence.msg.updated'));
      } else {
        await createGeofence(body);
        message.success(t('drone:fence.msg.created'));
      }
      setFenceModal({ open: false, editing: null });
      const list = await listGeofences({ ownerType: 'ASSET', ownerId: curDev.id });
      setFences(Array.isArray(list) ? list : []);
    } catch (e) {
      report(e, fenceModal.editing ? 'drone:fence.msg.updated' : 'drone:fence.msg.created');
    } finally {
      setFenceSaving(false);
    }
  };

  const deleteFence = (f) => {
    if (!window.confirm(t('drone:fence.confirmDelete'))) return;
    deleteGeofence(f.id)
      .then(() => { message.success(t('drone:fence.msg.deleted')); setFences((list) => list.filter((x) => x.id !== f.id)); })
      .catch((e) => report(e, 'drone:fence.msg.deleted'));
  };

  const toggleFence = (f) => {
    const next = f.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    updateGeofence(f.id, { status: next })
      .then(() => {
        message.success(next === 'ENABLED' ? t('drone:fence.msg.enabled') : t('drone:fence.msg.disabled'));
        setFences((list) => list.map((x) => (x.id === f.id ? { ...x, status: next } : x)));
      })
      .catch((e) => report(e, 'drone:fence.msg.updated'));
  };

  /* ---------- 设备弹窗 ---------- */
  const openDevList = (p) => { setCurProduct(p); setDevListQ(''); setDevListOpen(true); };
  const closeDevList = () => setDevListOpen(false);
  const openDev = (d) => {
    setCurDev(d); setDevRight('overview'); setTelemetry(null); setMaintOpen(false);
    setDevListOpen(false); setDevOpen(true);
  };
  const closeDev = () => { setDevOpen(false); setCurDev(null); };
  const gotoProduct = () => { message.info('跳转商品「' + (curProduct ? curProduct.name : '') + '」详情（D 期商品管理）'); };

  const liveVal = (src, dT) => {
    if (telemetry && telemetry[src] != null) return telemetry[src] + (src === '定位' ? '' : '');
    return '—';
  };

  /* ---------- 设备详情各菜单内容 ---------- */
  const renderDevContent = () => {
    if (!curDev) return null;
    const p = curProduct;
    const d = curDev;
    const af = apiFields(p);
    switch (devRight) {
      case 'overview':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m4')}{d.assetNo}</h3>
            <div className="kv">
              <div className="k">{t('common:m5')}</div><div>{d.assetNo}<span style={{ color: '#8a9099' }}> #{d.id}</span></div>
              <div className="k">{t('common:m6')}</div>
              <div><a onClick={gotoProduct} style={{ color: '#1677ff', cursor: 'pointer' }}>{(p && p.name) || '—'}</a></div>
              <div className="k">{t('common:m7')}</div><div>{(p && p.category) || '—'} · {(p && p.brand) || '—'} · {d.assetType || '—'}</div>
              <div className="k">{t('common:m8')}</div><div>{statusTag(d.status)}</div>
              <div className="k">{t('common:m9')}</div><div>{d.ownerId != null ? '用户#' + d.ownerId : '平台(资产所有人)'}</div>
              <div className="k">{t('common:m10')}</div><div>{d.userId != null ? '用户#' + d.userId : '—'}</div>
              <div className="k">{t('common:m11')}</div><div>{d.projectName || '—'}</div>
              <div className="k">{t('common:m12')}</div><div>{d.certNo || (p && p.shareCode) || '—'}</div>
            </div>
          </div>
        );
      case 'rt': {
        const items = [{ k: 'SOC', label: 'SOC', val: telemetry && telemetry.SOC != null ? telemetry.SOC + '%' : '—' }]
          .concat(af.map((f) => ({ k: f.apiSource, label: f.apiSource, val: liveVal(f.apiSource, d) })));
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m13')}<span style={{ fontSize: 10, background: '#e8f7ee', color: '#18a058', padding: '1px 6px', borderRadius: 4, marginLeft: 6 }}>LIVE</span></h3>
            <div className="rt-grid">
              {items.map((it) => {
                const ic = ICONS[it.k] || ['•', '#1677ff'];
                return (
                  <div className="rt-cell" key={it.k}>
                    <div className="ic" style={{ background: ic[1] }}>{ic[0]}</div>
                    <div className="v" id={'rtd_' + it.k}>{it.val}</div>
                    <div className="l">{it.label}</div>
                  </div>
                );
              })}
            </div>
            <div style={{ fontSize: 12, color: '#8a9099', margin: '12px 0 6px' }}>{t('common:m14')}</div>
            <div className="map-box"><div className="map-dot" style={{ left: '46%', top: '52%' }}></div>{t('common:m15')}<span id="mapLoc">{telemetry && telemetry['定位'] ? telemetry['定位'] : (d.location || '—')}</span></div>
            <p className="note">{t('common:m16')}</p>
          </div>
        );
      }
      case 'track':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m17')}</h3>
            <div className="map-box" style={{ minHeight: 280 }}>
              <div className="map-dot" style={{ left: '30%', top: '40%' }}></div>
              <div className="map-dot" style={{ left: '55%', top: '60%' }}></div>
              <div className="map-dot" style={{ left: '70%', top: '35%' }}></div>{t('common:m18')}</div>
            <p className="note">{t('common:m19')}{d.id}{t('common:m20')}</p>
          </div>
        );
      case 'fence':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m21')}</h3>
            {/* U6：自动锁机待接通，保存围栏当前不会触发自动锁机 */}
            <Alert type="info" showIcon style={{ marginBottom: 10 }} message={t('drone:fence.autoLockNote')} />
            <Alert type="warning" showIcon style={{ marginBottom: 10 }}
              message={<span>{t('drone:fence.platformNote')} <Link to="/airspace-zones">{t('drone:fence.gotoAirspace')}</Link></span>} />
            <Space style={{ marginBottom: 10 }}>
              <Button type="primary" icon={<PlusOutlined />} onClick={openFenceCreate}>{t('common:m22')}</Button>
            </Space>
            <Table
              rowKey="id" size="small" loading={fenceLoading} pagination={false}
              dataSource={fences}
              columns={[
                { title: t('drone:fence.col.id'), dataIndex: 'id', width: 70 },
                { title: t('drone:fence.col.name'), dataIndex: 'name' },
                { title: t('drone:fence.col.type'), dataIndex: 'fenceType', width: 90,
                  render: (v) => t(`drone:fence.type.${v}`, { defaultValue: v }) },
                {
                  title: t('drone:fence.col.params'), width: 240, ellipsis: true,
                  render: (_, r) => r.fenceType === 'RADIUS'
                    ? `${r.centerLat ?? '—'}, ${r.centerLng ?? '—'} · ${r.radiusM ?? '—'}m`
                    : (r.polygonWkt ? r.polygonWkt : '—'),
                },
                { title: t('drone:fence.col.trigger'), dataIndex: 'triggerAction', width: 110,
                  render: (v) => t(`drone:fence.trigger.${v}`, { defaultValue: v }) },
                { title: t('drone:fence.col.status'), dataIndex: 'status', width: 80,
                  render: (v) => <Tag color={v === 'ENABLED' ? 'green' : 'default'}>{t(`drone:fence.status.${v}`, { defaultValue: v })}</Tag> },
                {
                  title: t('drone:fence.col.actions'), width: 200, fixed: 'right',
                  render: (_, r) => (
                    <Space>
                      <Button size="small" type="link" onClick={() => openFenceEdit(r)}>{t('drone:fence.action.edit')}</Button>
                      <Button size="small" type="link" onClick={() => toggleFence(r)}>
                        {r.status === 'ENABLED' ? t('drone:fence.action.disable') : t('drone:fence.action.enable')}
                      </Button>
                      <Button size="small" type="link" danger onClick={() => deleteFence(r)}>{t('drone:fence.action.delete')}</Button>
                    </Space>
                  ),
                },
              ]}
            />
          </div>
        );
      case 'revenue': {
        const ops = [['物流货运', '$30/单'], ['客运', '$25/趟'], ['公交', '$18/趟'], ['顺风车', '$12/单'], ['打的', '$40/趟'], ['广告', '$200/周']];
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m23')}</h3>
            <div className="task-grid">
              {ops.map((t) => (
                <div className="task-card" key={t[0]}>
                  <div><b>{t[0]}</b></div>
                  <div className="earn">{t[1]}</div>
                  <div className="note">{t('common:m24')}</div>
                  <button className="btn sm" style={{ marginTop: 8 }} onClick={() => alert('进入「' + t[0] + '」接单（TaskPublish 任务发布域）')}>{t('common:m25')}</button>
                </div>
              ))}
            </div>
            <p className="note">{t('common:m26')}</p>
          </div>
        );
      }
      case 'maint': {
        const rows = (curTrace && curTrace.maintenance) || [];
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m27')}</h3>
            {rows.length ? rows.map((m) => (
              <div key={m.id} style={{ padding: '10px 12px', border: '1px solid var(--border)', borderRadius: 6, marginBottom: 8, cursor: 'pointer' }}
                onClick={() => { setCurMaint(m); setMaintOpen(true); }}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}><b>{m.id || m.servicedAt}</b><span className="tag orange">{m.cost != null ? '¥' + m.cost : '—'}</span></div>
                <div className="note">{m.servicedAt} · {m.vendor || m.person || ''} · {(m.images ? m.images.length : 0)}{t('common:m28')}</div>
              </div>
            )) : <p className="note">{t('common:m29')}</p>}
            <p className="note">{t('common:m30')}</p>
          </div>
        );
      }
      case 'video':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m31')}</h3>
            <p className="note">{t('common:m32')}</p>
            <button className="btn" onClick={() => navigate('/task-video')}>{t('common:m33')}</button>
          </div>
        );
      case 'transfer':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m34')}</h3>
            {curTrace && curTrace.transfers && curTrace.transfers.length ? (
              <table>
                <thead><tr><th>{t('common:m35')}</th><th>{t('common:m36')}</th><th>{t('common:m37')}</th><th>{t('common:m38')}</th></tr></thead>
                <tbody>
                  {curTrace.transfers.map((t) => (
                    <tr key={t.id}>
                      <td>{t.transferredAt}</td>
                      <td><span className="tag gray">{t.transferType}</span></td>
                      <td>{t.fromUserId != null ? '用户#' + t.fromUserId : '平台'}</td>
                      <td>{t.toUserId != null ? '用户#' + t.toUserId : '平台'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : <p className="note">{t('common:m39')}</p>}
          </div>
        );
      case 'ops':
        return (
          <div>
            <div className="warn-box">{t('common:m40')}</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderBottom: '1px dashed var(--border)' }}>
                <span>{t('common:m41')}</span>
                <button className="btn sm" onClick={() => api.post(`/v1/iot/devices/${d.assetNo}/command`, { cmd: 'LOCK' }).then(() => message.success(t('common:m42'))).catch((e) => message.error('下发失败：' + e.message))}>{t('common:m43')}</button>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderBottom: '1px dashed var(--border)' }}>
                <span>{t('common:m44')}</span>
                <button className="btn sm" onClick={() => api.post(`/v1/iot/devices/${d.assetNo}/command`, { cmd: 'RESTART' }).then(() => message.success(t('common:m45'))).catch((e) => message.error('下发失败：' + e.message))}>{t('common:m46')}</button>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderBottom: '1px dashed var(--border)' }}>
                <span>{t('common:m47')}</span>
                <input style={{ width: 90, padding: 5, border: '1px solid var(--border)', borderRadius: 5 }} defaultValue={40}
                  onChange={(e) => api.post(`/v1/iot/devices/${d.assetNo}/command`, { cmd: 'SPEED', value: e.target.value }).then(() => message.success(t('common:m48'))).catch((e) => message.error('下发失败：' + e.message))} />
              </div>
            </div>
          </div>
        );
      case 'cert':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{t('common:m49')}</h3>
            <div className="kv">
              <div className="k">{t('common:m12')}</div><div>{d.certNo || (p && p.shareCode) || '—'}</div>
              <div className="k">{t('common:m50')}</div><div>{(p && p.name) || '—'}</div>
              <div className="k">{t('common:m8')}</div><div><span className="tag green">{t('common:m51')}</span></div>
            </div>
            <div className="toolbar" style={{ marginTop: 16 }}>
              <button className="btn" onClick={() => message.success(t('common:m52'))}>{t('common:m53')}</button>
              <button className="btn ghost" onClick={() => navigate('/certificate')}>{t('common:m54')}</button>
            </div>
          </div>
        );
      default:
        return null;
    }
  };

  const renderDevList = () => {
    if (!curProduct) return null;
    const q2 = (devListQ || '').trim().toLowerCase();
    const list = devicesOf(curProduct.id).filter((d) =>
      !q2 || (d.assetNo + (d.location || '') + (d.ownerId != null ? '用户#' + d.ownerId : '') + (d.projectName || '')).toLowerCase().includes(q2));
    const af = apiFields(curProduct);
    const keyField = af[0];
    return (
      <table>
        <thead><tr><th>{t('common:m55')}</th><th>{t('common:m56')}</th><th>{t('common:m9')}</th><th>{t('common:m10')}</th><th>{t('common:m8')}</th><th>{t('common:m57')}</th><th>{t('common:m11')}</th><th>{t('common:m58')}</th></tr></thead>
        <tbody>
          {list.length ? list.map((d) => (
            <tr key={d.id} style={{ cursor: 'pointer' }} onClick={() => openDev(d)}>
              <td><b>{d.assetNo}</b><div className="note">#{d.id}</div></td>
              <td>{d.location || '—'}</td>
              <td>{d.ownerId != null ? '用户#' + d.ownerId : '平台(资产所有人)'}</td>
              <td>{d.userId != null ? '用户#' + d.userId : '—'}</td>
              <td>{statusTag(d.status)}</td>
              <td>{keyField ? keyField.apiSource + ' ' + liveVal(keyField.apiSource, d) : (telemetry && telemetry.SOC != null ? 'SOC ' + telemetry.SOC : '—')}</td>
              <td>{d.projectName || '—'}</td>
              <td>
                <div className="row-ops" onClick={(e) => e.stopPropagation()}>
                  <button className="btn ghost sm" onClick={() => message.info(t('common:m59'))}>{t('common:m60')}</button>
                  <button className="btn ghost sm" onClick={() => message.info(t('common:m61'))}>{t('common:m62')}</button>
                  <button className="btn ghost sm" onClick={() => message.info(t('common:m63'))}>{t('common:m64')}</button>
                </div>
              </td>
            </tr>
          )) : <tr><td colSpan={8} className="note">{t('common:m65')}</td></tr>}
        </tbody>
      </table>
    );
  };

  const renderEditFields = () => (
    <div>
      <div className="field-head"><span>{t('common:m66')}</span><span>{t('common:m36')}</span><span>{t('common:m67')}</span><span>{t('common:m68')}</span><span></span></div>
      {editFields.map((f, i) => (
        <div className="field-row" key={i}>
          <input placeholder={t('common:m66')} value={f.name} onChange={(e) => setEditFields((prev) => prev.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
          <select value={f.type} onChange={(e) => setEditFields((prev) => prev.map((x, j) => (j === i ? { ...x, type: e.target.value } : x)))}>
            <option value="text">{t('common:m69')}</option><option value="number">{t('common:m70')}</option><option value="select">{t('common:m71')}</option>
          </select>
          <input placeholder={t('common:m72')} value={f.opts || ''} onChange={(  e) => setEditFields((prev) => prev.map((x, j) => (j === i ? { ...x, opts: e.target.value } : x)))} />
          <select value={f.apiSource || ''} onChange={(e) => onApiChange(i, e.target.value)}>
            <option value="">{t('common:m73')}</option>
            {ALL_API.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          <button className="btn sm danger" onClick={() => setEditFields((prev) => prev.filter((_, j) => j !== i))}>×</button>
        </div>
      ))}
      <button className="btn ghost sm" onClick={addField}>{t('common:m74')}</button>
    </div>
  );

  return (
    <PageCard title={t('common:m75')}
      extra={<span style={{ fontSize: 12, color: '#8a9099' }}>{t('common:m76')}</span>}>
      <style>{`
        .btn{background:var(--primary,#1677ff);color:#fff;border:none;border-radius:6px;padding:6px 14px;cursor:pointer;font-size:13px;}
        .btn.ghost{background:#fff;color:var(--primary,#1677ff);border:1px solid var(--primary,#1677ff);}
        .btn.danger{background:#ff4d4f;color:#fff;}
        .btn.sm{padding:3px 10px;font-size:12px;}
        .toolbar{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap;align-items:center;}
        .search{width:280px;padding:7px 10px;border:1px solid #e5e7eb;border-radius:6px;font-size:13px;}
        .prod-item{padding:12px 14px;border:1px solid #e5e7eb;border-radius:6px;margin-bottom:10px;cursor:pointer;display:flex;justify-content:space-between;align-items:center;}
        .prod-item:hover{border-color:#1677ff;background:#f0f6ff;}
        table{width:100%;border-collapse:collapse;font-size:13px;}
        th,td{padding:8px 10px;text-align:left;border-bottom:1px solid #e5e7eb;}
        th{background:#fafafa;color:#8a9099;font-weight:600;}
        .tag{display:inline-block;padding:1px 8px;border-radius:10px;font-size:12px;background:#e6f0ff;color:#1677ff;}
        .tag.green{background:#e8f7ee;color:#18a058;}
        .tag.orange{background:#fff3e0;color:#d46b08;}
        .tag.gray{background:#f0f0f0;color:#8a9099;}
        .row-ops{display:flex;gap:6px;}
        .note{font-size:12px;color:#8a9099;margin-top:4px;}
        .kv{display:grid;grid-template-columns:120px 1fr;gap:6px 12px;font-size:13px;}
        .kv .k{color:#8a9099;}
        .mask{position:fixed;inset:0;background:rgba(0,0,0,.45);display:flex;align-items:flex-start;justify-content:center;padding:40px 0;z-index:50;overflow:auto;}
        .modal{background:#fff;width:920px;border-radius:8px;padding:24px;max-width:94vw;}
        .modal h3{margin:0 0 14px;font-size:16px;}
        .modal-foot{display:flex;justify-content:flex-end;gap:8px;margin-top:16px;border-top:1px solid #e5e7eb;padding-top:14px;}
        .field-head,.field-row{display:grid;grid-template-columns:1.2fr 1fr 1.4fr 1fr 36px;gap:6px;align-items:center;margin-bottom:8px;}
        .field-head{font-size:12px;color:#8a9099;}
        .field-row input,.field-row select{padding:6px 8px;border:1px solid #e5e7eb;border-radius:5px;font-size:12px;}
        .dev-mask{position:fixed;inset:0;background:rgba(0,0,0,.45);display:flex;align-items:center;justify-content:center;z-index:60;}
        .dev-panel{background:#fff;width:1040px;height:700px;border-radius:8px;display:flex;flex-direction:column;overflow:hidden;max-width:96vw;}
        .dev-head{height:54px;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;justify-content:space-between;padding:0 16px;flex:none;}
        .dev-wrap{flex:1;display:flex;min-height:0;}
        .dev-nav{width:200px;border-right:1px solid #e5e7eb;padding:12px 0;overflow:auto;flex:none;}
        .dev-nav .nt{font-size:12px;color:#8a9099;padding:8px 16px 4px;}
        .dev-nav ul{list-style:none;margin:0;padding:0;}
        .dev-nav li{padding:11px 16px;cursor:pointer;font-size:14px;display:flex;align-items:center;gap:8px;border-left:3px solid transparent;}
        .dev-nav li:hover{background:#f5f7fa;}
        .dev-nav li.active{background:#e6f0ff;color:#1677ff;border-left-color:#1677ff;font-weight:600;}
        .dev-main{flex:1;display:flex;flex-direction:column;min-width:0;}
        .dev-top{display:flex;gap:12px;padding:14px 16px;border-bottom:1px solid #e5e7eb;flex:none;align-items:stretch;}
        .white-img{width:150px;border:1px dashed #e5e7eb;border-radius:10px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#8a9099;font-size:12px;background:#fafbfc;cursor:pointer;}
        .white-img:hover{border-color:#1677ff;color:#1677ff;}
        .white-img .ph{width:100px;height:84px;background:#fff;border:1px solid #e5e7eb;border-radius:6px;display:flex;align-items:center;justify-content:center;margin-bottom:6px;font-size:18px;}
        .rt-entry{flex:1;border:1px solid #e5e7eb;border-radius:10px;padding:10px 14px;display:flex;align-items:center;gap:14px;cursor:pointer;background:#fff;}
        .rt-entry:hover{border-color:#1677ff;}
        .rt-entry .ic{width:40px;height:40px;border-radius:10px;display:flex;align-items:center;justify-content:center;font-size:20px;color:#fff;background:#1677ff;}
        .rt-entry .v{font-size:20px;font-weight:700;color:#1f2329;}
        .rt-entry .l{font-size:12px;color:#8a9099;}
        .rt-entry .arrow{margin-left:auto;color:#1677ff;font-size:13px;}
        .dev-content{flex:1;padding:16px;overflow:auto;}
        .dev-content h3{margin:0 0 12px;font-size:15px;}
        .map-box{flex:1;border-radius:8px;background:linear-gradient(135deg,#e8f0ff,#f6f8fa);border:1px solid #e5e7eb;position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;color:#8a9099;font-size:13px;min-height:200px;}
        .map-dot{position:  absolute;width:14px;height:14px;border-radius:50%;background:#1677ff;box-shadow:0 0 0 5px rgba(22,119,255,.2);}
        .task-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;}
        .task-card{border:1px solid #e5e7eb;border-radius:8px;padding:14px;}
        .task-card .earn{color:#18a058;font-weight:600;font-size:15px;}
        .thumb{width:56px;height:56px;border:1px solid #e5e7eb;border-radius:6px;display:inline-flex;align-items:center;justify-content:center;color:#8a9099;font-size:11px;margin-right:6px;}
        .toggle{display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px dashed #e5e7eb;}
        .warn-box{background:#fffbe6;border:1px solid #ffe58f;border-radius:6px;padding:10px 12px;font-size:12px;color:#ad6800;margin-bottom:12px;}
        .rt-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px;margin-bottom:16px;}
        .rt-cell{border:1px solid #e5e7eb;border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:4px;background:#fff;}
        .rt-cell .ic{width:30px;height:30px;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:16px;color:#fff;}
        .rt-cell .v{font-size:18px;font-weight:700;color:#1f2329;}
        .rt-cell .l{font-size:12px;color:#8a9099;}
      `}</style>

      <div className="toolbar">
        <input className="search" placeholder={t('common:m77')} value={q} onChange={(e) => setQ(e.target.value)} />
        <button className="btn" onClick={() => openEdit(null)}><PlusOutlined />{t('common:m78')}</button>
      </div>

      {loading ? <p className="note">{t('common:m79')}</p> : (
        <div id="prodList">
          {filteredProducts.length ? filteredProducts.map((p) => (
            <div className="prod-item" key={p.id} onClick={() => openDevList(p)}>
              <div>
                <b>{p.name}</b>
                <div className="note">{p.category || '—'} · {p.brand || '—'} · {devicesOf(p.id).length}{t('common:m80')}{apiFields(p).length}{t('common:m81')}</div>
              </div>
              <div className="row-ops" onClick={(e) => e.stopPropagation()}>
                <button className="btn ghost sm" onClick={() => openEdit(p)}><EditOutlined />{t('common:m82')}</button>
                <button className="btn sm danger" onClick={() => delProduct(p)}><DeleteOutlined />{t('common:m83')}</button>
              </div>
            </div>
          )) : <p className="note">{t('common:m84')}</p>}
        </div>
      )}

      {/* 新建/编辑产品 */}
      {editOpen && (
        <div className="mask" onClick={(e) => { if (e.target === e.currentTarget) setEditOpen(false); }}>
          <div className="modal">
            <h3>{editId ? '编辑产品' : '新建产品'}</h3>
            <div style={{ marginBottom: 12 }}><label>{t('common:m85')}</label><input id="pName" style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editName} onChange={(e) => setEditName(e.target.value)} placeholder={t('common:m86')} /></div>
            <div style={{ marginBottom: 12 }}><label>{t('common:m87')}</label><input type="number" style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editInterval} onChange={(e) => setEditInterval(+e.target.value || 30)} /></div>
            <div style={{ marginBottom: 12 }}><label>{t('common:m88')}</label><input style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editCategory} onChange={(e) => setEditCategory(e.target.value)} placeholder={t('common:m89')} /></div>
            <div style={{ marginBottom: 12 }}><label>{t('common:m90')}</label><input style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editBrand} onChange={(e) => setEditBrand(e.target.value)} placeholder={t('common:m91')} /></div>
            <div style={{ marginBottom: 12 }}><label>{t('common:m92')}</label>
              <select style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editAssetType} onChange={(e) => setEditAssetType(e.target.value)}>
                <option value="">{t('common:m93')}</option>
                <option value="VEHICLE">VEHICLE</option><option value="BATTERY">BATTERY</option><option value="DRONE">DRONE</option><option value="CHARGER">CHARGER</option>
              </select></div>
            <div style={{ marginBottom: 12 }}><label>{t('common:m94')}</label>
              <select style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editManufacturerId || ''} onChange={(e) => setEditManufacturerId(e.target.value ? +e.target.value : null)}>
                {manufacturers.map((m) => <option key={m.id} value={m.id}>{m.name || ('厂商#' + m.id)}</option>)}
              </select></div>
            <div>
              <label>{t('common:m95')}</label>
              {renderEditFields()}
            </div>
            <div className="modal-foot"><button className="btn ghost" onClick={() => setEditOpen(false)}>{t('common:m96')}</button><button className="btn" onClick={saveProduct}>{t('common:m97')}</button></div>
          </div>
        </div>
      )}

      {/* 产品 → 设备弹窗 */}
      {devListOpen && (
        <div className="mask" onClick={(e) => { if (e.target === e.currentTarget) setDevListOpen(false); }}>
          <div className="modal" style={{ width: 980 }}>
            <h3>{'产品设备 · ' + (curProduct ? curProduct.name : '')}</h3>
            {curProduct && (
              <div className="note" style={{ marginBottom: 10 }}>{t('common:m98')}{curProduct.name}（{curProduct.category || '—'} · {curProduct.brand || '—'}{t('common:m99')}{(apiFields(curProduct).map((f) => f.apiSource).join('、') || '—')}
              </div>
            )}
            <div className="toolbar" style={{ marginTop: 10 }}>
              <input className="search" placeholder={t('common:m100')} value={devListQ} onChange={(e) => setDevListQ(e.target.value)} />
            </div>
            {renderDevList()}
            <div className="modal-foot"><button className="btn" onClick={closeDevList}>{t('common:m101')}</button></div>
          </div>
        </div>
      )}

      {/* 设备详情：左菜单 + 右（上白底图/实时数据，下菜单内容） */}
      {devOpen && curDev && (
        <div className="dev-mask" onClick={(e) => { if (e.target === e.currentTarget) closeDev(); }}>
          <div className="dev-panel">
            <div className="dev-head">
              <div><b>{t('common:m102')}</b> · <span>{curDev.assetNo}</span></div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="btn ghost sm" onClick={() => setMngOpen(true)}>{t('common:m103')}</button>
                <button className="btn ghost sm" onClick={closeDev}>{t('common:m101')}</button>
              </div>
            </div>
            <div className="dev-wrap">
              <aside className="dev-nav">
                <div className="nt">{t('common:m104')}</div>
                <ul>
                  {MENUS.filter((m) => menuVisible[m.key] !== false).map((m) => (
                    <li key={m.key} className={devRight === m.key ? 'active' : ''} onClick={() => setDevRight(m.key)}>{m.label}</li>
                  ))}
                </ul>
              </aside>
              <div className="dev-main">
                <div className="dev-top">
                  <div className="white-img" onClick={gotoProduct} title={t('common:m105')}>
                    <div className="ph">🖼️</div>
                    <div>{t('common:m106')}</div>
                  </div>
                  <div className="rt-entry" onClick={() => setDevRight('rt')} title={t('common:m107')}>
                    <div className="ic">📊</div>
                    <div>
                      <div className="v">{telemetry && telemetry.SOC != null ? telemetry.SOC + '%' : '—'}</div>
                      <div className="l">{t('common:m108')}</div>
                    </div>
                    <div className="arrow">{t('common:m109')}</div>
                  </div>
                  <button className="btn ghost sm" style={{ alignSelf: 'flex-start' }} onClick={() => setMngOpen(true)}>{t('common:m103')}</button>
                </div>
                <div className="dev-content">{renderDevContent()}</div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 菜单管理 */}
      {mngOpen && (
        <div className="mask" onClick={(e) => { if (e.target === e.currentTarget) setMngOpen(false); }}>
          <div className="modal" style={{ width: 420 }}>
            <h3>{t('common:m110')}</h3>
            <div>
              {MENUS.map((m) => (
                <div key={m.key} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 0', borderBottom: '1px dashed #e5e7eb' }}>
                  <input type="checkbox" checked={menuVisible[m.key] !== false}
                    onChange={(e) => { const v = e.target.checked; setMenuVisible((prev) => ({ ...prev, [m.key]: v })); if (!v && devRight === m.key) setDevRight('overview'); }} />
                  <span>{m.label}</span>
                </div>
              ))}
            </div>
            <div className="modal-foot"><button className="btn" onClick={() => setMngOpen(false)}>{t('common:m111')}</button></div>
          </div>
        </div>
      )}

      {/* 维修记录详情 */}
      {maintOpen && curMaint && (
        <div className="mask" onClick={(e) => { if (e.target === e.currentTarget) setMaintOpen(false); }}>
          <div className="modal" style={{ width: 520 }}>
            <h3>{t('common:m112')}</h3>
            <div className="kv">
              <div className="k">{t('common:m113')}</div><div>{curMaint.id || curMaint.servicedAt}</div>
              <div className="k">{t('common:m35')}</div><div>{curMaint.servicedAt}</div>
              <div className="k">{t('common:m114')}</div><div>{curMaint.person || curMaint.vendor || '—'}</div>
              <div className="k">{t('common:m115')}</div><div><span className="tag orange">{curMaint.cost != null ? '¥' + curMaint.cost : '—'}</span></div>
            </div>
            <div style={{ marginTop: 14 }}><label>{t('common:m116')}</label>
              <div style={{ background: '#fafbfc', border: '1px solid #e5e7eb', borderRadius: 6, padding: 10 }}>{curMaint.process || curMaint.note || '—'}</div></div>
            <div style={{ marginTop: 10 }}><label>{t('common:m117')}{(curMaint.images ? curMaint.images.length : 0)}{t('common:m118')}</label>
              <div>{(curMaint.images && curMaint.images.length) ? curMaint.images.map((_, i) => <span className="thumb" key={i}>{t('common:m119')}{i + 1}</span>) : <span className="note">{t('common:m120')}</span>}</div></div>
            <div className="modal-foot"><button className="btn" onClick={() => setMaintOpen(false)}>{t('common:m101')}</button></div>
          </div>
        </div>
      )}

      {/* 设备电子围栏：新增 / 编辑（真实后端 /v1/iot/geofences） */}
      {fenceModal.open && (
        <Modal
          title={fenceModal.editing ? t('drone:fence.edit') : t('drone:fence.create')}
          open={fenceModal.open}
          onOk={submitFence}
          confirmLoading={fenceSaving}
          onCancel={() => setFenceModal({ open: false, editing: null })}
          okText={t('action.ok')}
          cancelText={t('action.cancel')}
          destroyOnClose
        >
          <Form form={fenceForm} layout="vertical" initialValues={{ fenceType: 'RADIUS', triggerAction: 'ALERT', status: 'ENABLED' }}>
            <Form.Item name="name" label={t('drone:fence.form.name')}
              rules={[{ required: true, message: t('form.required', { label: t('drone:fence.form.name') }) }]}>
              <Input placeholder={t('common:m121')} />
            </Form.Item>
            <Form.Item name="fenceType" label={t('drone:fence.form.fenceType')} rules={[{ required: true }]}>
              <Select options={[
                { label: t('drone:fence.type.RADIUS'), value: 'RADIUS' },
                { label: t('drone:fence.type.POLYGON'), value: 'POLYGON' },
              ]} />
            </Form.Item>
            <Form.Item noStyle shouldUpdate={(p, c) => p.fenceType !== c.fenceType}>
              {({ getFieldValue }) => getFieldValue('fenceType') === 'RADIUS' ? (
                <>
                  <Form.Item name="centerLat" label={t('drone:fence.form.centerLat')}
                    rules={[{ required: true, message: t('form.required', { label: t('drone:fence.form.centerLat') }) }]}>
                    <InputNumber style={{ width: '100%' }} step={0.0001} placeholder="11.55" />
                  </Form.Item>
                  <Form.Item name="centerLng" label={t('drone:fence.form.centerLng')}
                    rules={[{ required: true, message: t('form.required', { label: t('drone:fence.form.centerLng') }) }]}>
                    <InputNumber style={{ width: '100%' }} step={0.0001} placeholder="104.92" />
                  </Form.Item>
                  <Form.Item name="radiusM" label={t('drone:fence.form.radiusM')}
                    rules={[{ required: true, message: t('form.required', { label: t('drone:fence.form.radiusM') }) }]}>
                    <InputNumber style={{ width: '100%' }} min={1} placeholder="500" />
                  </Form.Item>
                </>
              ) : (
                <Form.Item name="polygonWkt" label={t('drone:fence.form.polygonWkt')}
                  rules={[
                    { required: true, message: t('form.required', { label: t('drone:fence.form.polygonWkt') }) },
                    { validator: validateWkt },
                  ]}>
                  <Input.TextArea rows={4} placeholder="POLYGON((104.9 11.5, 104.92 11.5, 104.92 11.52, 104.9 11.52, 104.9 11.5))" />
                </Form.Item>
              )}
            </Form.Item>
            <Form.Item name="triggerAction" label={t('drone:fence.form.triggerAction')} rules={[{ required: true }]}>
              <Select options={[
                { label: t('drone:fence.trigger.ALERT'), value: 'ALERT' },
                { label: t('drone:fence.trigger.LOCK'), value: 'LOCK' },
              ]} />
            </Form.Item>
            {fenceModal.editing && (
              <Form.Item name="status" label={t('drone:fence.form.status')} rules={[{ required: true }]}>
                <Select options={[
                  { label: t('drone:fence.status.ENABLED'), value: 'ENABLED' },
                  { label: t('drone:fence.status.DISABLED'), value: 'DISABLED' },
                ]} />
              </Form.Item>
            )}
          </Form>
        </Modal>
      )}

    </PageCard>
  );
}
