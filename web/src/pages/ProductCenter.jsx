import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { message } from 'antd';
import { SearchOutlined, PlusOutlined, DeleteOutlined, EditOutlined, PictureOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';

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
        message.error('加载失败，已回落演示数据');
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
    if (!editName.trim()) { message.warning('请填写产品名称'); return; }
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
      .then(() => { message.success('已删除'); setProducts(products.filter((x) => x.id !== p.id)); })
      .catch((e) => message.error('删除失败：' + e.message));
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
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>概况 · {d.assetNo}</h3>
            <div className="kv">
              <div className="k">设备编号</div><div>{d.assetNo}<span style={{ color: '#8a9099' }}> #{d.id}</span></div>
              <div className="k">所属商品</div>
              <div><a onClick={gotoProduct} style={{ color: '#1677ff', cursor: 'pointer' }}>{(p && p.name) || '—'}</a></div>
              <div className="k">产品信息</div><div>{(p && p.category) || '—'} · {(p && p.brand) || '—'} · {d.assetType || '—'}</div>
              <div className="k">状态</div><div>{statusTag(d.status)}</div>
              <div className="k">当前所有人</div><div>{d.ownerId != null ? '用户#' + d.ownerId : '平台(资产所有人)'}</div>
              <div className="k">当前使用人</div><div>{d.userId != null ? '用户#' + d.userId : '—'}</div>
              <div className="k">所在项目</div><div>{d.projectName || '—'}</div>
              <div className="k">合格证号</div><div>{d.certNo || (p && p.shareCode) || '—'}</div>
            </div>
          </div>
        );
      case 'rt': {
        const items = [{ k: 'SOC', label: 'SOC', val: telemetry && telemetry.SOC != null ? telemetry.SOC + '%' : '—' }]
          .concat(af.map((f) => ({ k: f.apiSource, label: f.apiSource, val: liveVal(f.apiSource, d) })));
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>实时数据 · 数据与位置 <span style={{ fontSize: 10, background: '#e8f7ee', color: '#18a058', padding: '1px 6px', borderRadius: 4, marginLeft: 6 }}>LIVE</span></h3>
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
            <div style={{ fontSize: 12, color: '#8a9099', margin: '12px 0 6px' }}>实时定位（地图）</div>
            <div className="map-box"><div className="map-dot" style={{ left: '46%', top: '52%' }}></div>实时坐标：<span id="mapLoc">{telemetry && telemetry['定位'] ? telemetry['定位'] : (d.location || '—')}</span></div>
            <p className="note">LIVE：每 2 秒刷新；接入 EMQX 位置上报 + 地图 SDK。</p>
          </div>
        );
      }
      case 'track':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>历史轨迹回放</h3>
            <div className="map-box" style={{ minHeight: 280 }}>
              <div className="map-dot" style={{ left: '30%', top: '40%' }}></div>
              <div className="map-dot" style={{ left: '55%', top: '60%' }}></div>
              <div className="map-dot" style={{ left: '70%', top: '35%' }}></div>
              轨迹点占位（按时间轴回放）
            </div>
            <p className="note">调用 A 期历史位置接口 /api/v1/assets/{d.id}/tracks，支持时间轴拖拽回放。</p>
          </div>
        );
      case 'fence':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>电子围栏设置</h3>
            <div style={{ marginBottom: 10 }}><label>围栏名称</label><input style={{ width: '100%', padding: 8, border: '1px solid var(--border)', borderRadius: 6 }} placeholder="如：站点A作业区" /></div>
            <div style={{ marginBottom: 10 }}><label>形状</label>
              <select style={{ width: '100%', padding: 8, border: '1px solid var(--border)', borderRadius: 6 }}>
                <option>圆形</option><option>多边形</option>
              </select></div>
            <div style={{ marginBottom: 10 }}><label>半径（米）</label><input defaultValue={500} style={{ width: '100%', padding: 8, border: '1px solid var(--border)', borderRadius: 6 }} /></div>
            <button className="btn" onClick={() => alert('保存围栏（airspace 域 V29 地理围栏）')}>保存围栏</button>
            <p className="note">越界触发告警/锁机（类比车辆断缴锁车，V30 飞行安全管控）。</p>
          </div>
        );
      case 'revenue': {
        const ops = [['物流货运', '$30/单'], ['客运', '$25/趟'], ['公交', '$18/趟'], ['顺风车', '$12/单'], ['打的', '$40/趟'], ['广告', '$200/周']];
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>收益中心（与任务接口匹配创造收益）</h3>
            <div className="task-grid">
              {ops.map((t) => (
                <div className="task-card" key={t[0]}>
                  <div><b>{t[0]}</b></div>
                  <div className="earn">{t[1]}</div>
                  <div className="note">按兴趣接单 / 按要求运营</div>
                  <button className="btn sm" style={{ marginTop: 8 }} onClick={() => alert('进入「' + t[0] + '」接单（TaskPublish 任务发布域）')}>去接单</button>
                </div>
              ))}
            </div>
            <p className="note">收益板块对接 TaskPublish 任务发布域，车辆/无人机按订单运营产生收益并进入分账（资产闭环）。</p>
          </div>
        );
      }
      case 'maint': {
        const rows = (curTrace && curTrace.maintenance) || [];
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>维修记录</h3>
            {rows.length ? rows.map((m) => (
              <div key={m.id} style={{ padding: '10px 12px', border: '1px solid var(--border)', borderRadius: 6, marginBottom: 8, cursor: 'pointer' }}
                onClick={() => { setCurMaint(m); setMaintOpen(true); }}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}><b>{m.id || m.servicedAt}</b><span className="tag orange">{m.cost != null ? '¥' + m.cost : '—'}</span></div>
                <div className="note">{m.servicedAt} · {m.vendor || m.person || ''} · {(m.images ? m.images.length : 0)} 张图</div>
              </div>
            )) : <p className="note">暂无维修记录</p>}
            <p className="note">点记录看维修过程详情与图片。</p>
          </div>
        );
      }
      case 'video':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>录像记录</h3>
            <p className="note">录像记录接入录像存储；点击下方进入录像数据管理。</p>
            <button className="btn" onClick={() => navigate('/task-video')}>前往录像数据</button>
          </div>
        );
      case 'transfer':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>转让记录</h3>
            {curTrace && curTrace.transfers && curTrace.transfers.length ? (
              <table>
                <thead><tr><th>时间</th><th>类型</th><th>从</th><th>至</th></tr></thead>
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
            ) : <p className="note">暂无转让记录（C 期 TRANSFER 产权转移将记录于此）</p>}
          </div>
        );
      case 'ops':
        return (
          <div>
            <div className="warn-box">⚠ 远程控制请慎选，误操作可能影响在运资产安全。</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderBottom: '1px dashed var(--border)' }}>
                <span>远程锁机</span>
                <button className="btn sm" onClick={() => api.post(`/v1/iot/devices/${d.assetNo}/command`, { cmd: 'LOCK' }).then(() => message.success('已下发锁机指令')).catch((e) => message.error('下发失败：' + e.message))}>下发锁机</button>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderBottom: '1px dashed var(--border)' }}>
                <span>远程重启</span>
                <button className="btn sm" onClick={() => api.post(`/v1/iot/devices/${d.assetNo}/command`, { cmd: 'RESTART' }).then(() => message.success('已下发重启指令')).catch((e) => message.error('下发失败：' + e.message))}>下发重启</button>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderBottom: '1px dashed var(--border)' }}>
                <span>限速（km/h）</span>
                <input style={{ width: 90, padding: 5, border: '1px solid var(--border)', borderRadius: 5 }} defaultValue={40}
                  onChange={(e) => api.post(`/v1/iot/devices/${d.assetNo}/command`, { cmd: 'SPEED', value: e.target.value }).then(() => message.success('限速已下发')).catch((e) => message.error('下发失败：' + e.message))} />
              </div>
            </div>
          </div>
        );
      case 'cert':
        return (
          <div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>合格证</h3>
            <div className="kv">
              <div className="k">合格证号</div><div>{d.certNo || (p && p.shareCode) || '—'}</div>
              <div className="k">所属产品</div><div>{(p && p.name) || '—'}</div>
              <div className="k">状态</div><div><span className="tag green">已签发</span></div>
            </div>
            <div className="toolbar" style={{ marginTop: 16 }}>
              <button className="btn" onClick={() => message.success('导出合格证 PDF（对接合格证服务）')}>导出合格证(PDF)</button>
              <button className="btn ghost" onClick={() => navigate('/certificate')}>合格证管理</button>
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
        <thead><tr><th>设备名称</th><th>当前位置</th><th>当前所有人</th><th>当前使用人</th><th>状态</th><th>关键字段</th><th>所在项目</th><th>操作</th></tr></thead>
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
                  <button className="btn ghost sm" onClick={() => message.info('转让（TRANSFER/SHARE/AUTHORIZE）')}>转让</button>
                  <button className="btn ghost sm" onClick={() => message.info('共享（SHARE 需 stationId）')}>共享</button>
                  <button className="btn ghost sm" onClick={() => message.info('授权（AUTHORIZE）')}>授权</button>
                </div>
              </td>
            </tr>
          )) : <tr><td colSpan={8} className="note">无匹配设备</td></tr>}
        </tbody>
      </table>
    );
  };

  const renderEditFields = () => (
    <div>
      <div className="field-head"><span>字段名</span><span>类型</span><span>选项(select用,逗号)</span><span>实时API绑定</span><span></span></div>
      {editFields.map((f, i) => (
        <div className="field-row" key={i}>
          <input placeholder="字段名" value={f.name} onChange={(e) => setEditFields((prev) => prev.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
          <select value={f.type} onChange={(e) => setEditFields((prev) => prev.map((x, j) => (j === i ? { ...x, type: e.target.value } : x)))}>
            <option value="text">文本</option><option value="number">数字</option><option value="select">下拉</option>
          </select>
          <input placeholder="选项,逗号" value={f.opts || ''} onChange={(  e) => setEditFields((prev) => prev.map((x, j) => (j === i ? { ...x, opts: e.target.value } : x)))} />
          <select value={f.apiSource || ''} onChange={(e) => onApiChange(i, e.target.value)}>
            <option value="">不绑定</option>
            {ALL_API.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          <button className="btn sm danger" onClick={() => setEditFields((prev) => prev.filter((_, j) => j !== i))}>×</button>
        </div>
      ))}
      <button className="btn ghost sm" onClick={addField}>+ 添加字段</button>
    </div>
  );

  return (
    <PageCard title="产品中心"
      extra={<span style={{ fontSize: 12, color: '#8a9099' }}>列表/查询/新建/编辑/删除 → 点产品看设备 → 点设备进功能菜单（左：菜单页 / 右：白底图+实时数据 / 菜单内容）</span>}>
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
        <input className="search" placeholder="查询产品（名称/类别/品牌）" value={q} onChange={(e) => setQ(e.target.value)} />
        <button className="btn" onClick={() => openEdit(null)}><PlusOutlined /> 新建产品</button>
      </div>

      {loading ? <p className="note">加载中…</p> : (
        <div id="prodList">
          {filteredProducts.length ? filteredProducts.map((p) => (
            <div className="prod-item" key={p.id} onClick={() => openDevList(p)}>
              <div>
                <b>{p.name}</b>
                <div className="note">{p.category || '—'} · {p.brand || '—'} · {devicesOf(p.id).length} 台设备 · {apiFields(p).length} 个实时字段</div>
              </div>
              <div className="row-ops" onClick={(e) => e.stopPropagation()}>
                <button className="btn ghost sm" onClick={() => openEdit(p)}><EditOutlined /> 编辑</button>
                <button className="btn sm danger" onClick={() => delProduct(p)}><DeleteOutlined /> 删除</button>
              </div>
            </div>
          )) : <p className="note">无匹配产品</p>}
        </div>
      )}

      {/* 新建/编辑产品 */}
      {editOpen && (
        <div className="mask" onClick={(e) => { if (e.target === e.currentTarget) setEditOpen(false); }}>
          <div className="modal">
            <h3>{editId ? '编辑产品' : '新建产品'}</h3>
            <div style={{ marginBottom: 12 }}><label>产品名称</label><input id="pName" style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editName} onChange={(e) => setEditName(e.target.value)} placeholder="如：光伏储能一体机 X1" /></div>
            <div style={{ marginBottom: 12 }}><label>位置上报间隔（秒）</label><input type="number" style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editInterval} onChange={(e) => setEditInterval(+e.target.value || 30)} /></div>
            <div style={{ marginBottom: 12 }}><label>类别</label><input style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editCategory} onChange={(e) => setEditCategory(e.target.value)} placeholder="如：光伏 / 电池 / 无人机" /></div>
            <div style={{ marginBottom: 12 }}><label>品牌</label><input style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editBrand} onChange={(e) => setEditBrand(e.target.value)} placeholder="品牌方名称" /></div>
            <div style={{ marginBottom: 12 }}><label>资产类型</label>
              <select style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editAssetType} onChange={(e) => setEditAssetType(e.target.value)}>
                <option value="">请选择</option>
                <option value="VEHICLE">VEHICLE</option><option value="BATTERY">BATTERY</option><option value="DRONE">DRONE</option><option value="CHARGER">CHARGER</option>
              </select></div>
            <div style={{ marginBottom: 12 }}><label>制造商</label>
              <select style={{ width: '100%', padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 6 }} value={editManufacturerId || ''} onChange={(e) => setEditManufacturerId(e.target.value ? +e.target.value : null)}>
                {manufacturers.map((m) => <option key={m.id} value={m.id}>{m.name || ('厂商#' + m.id)}</option>)}
              </select></div>
            <div>
              <label>产品字段定义（类似类别管理 · 增删改查；可勾选「实时API绑定」并选数据源，绑定后该字段在详情实时数据刷新）</label>
              {renderEditFields()}
            </div>
            <div className="modal-foot"><button className="btn ghost" onClick={() => setEditOpen(false)}>取消</button><button className="btn" onClick={saveProduct}>保存</button></div>
          </div>
        </div>
      )}

      {/* 产品 → 设备弹窗 */}
      {devListOpen && (
        <div className="mask" onClick={(e) => { if (e.target === e.currentTarget) setDevListOpen(false); }}>
          <div className="modal" style={{ width: 980 }}>
            <h3>{'产品设备 · ' + (curProduct ? curProduct.name : '')}</h3>
            {curProduct && (
              <div className="note" style={{ marginBottom: 10 }}>
                产品信息：{curProduct.name}（{curProduct.category || '—'} · {curProduct.brand || '—'}）　实时字段：{(apiFields(curProduct).map((f) => f.apiSource).join('、') || '—')}
              </div>
            )}
            <div className="toolbar" style={{ marginTop: 10 }}>
              <input className="search" placeholder="查询设备（名称/位置/所有人/项目）" value={devListQ} onChange={(e) => setDevListQ(e.target.value)} />
            </div>
            {renderDevList()}
            <div className="modal-foot"><button className="btn" onClick={closeDevList}>关闭</button></div>
          </div>
        </div>
      )}

      {/* 设备详情：左菜单 + 右（上白底图/实时数据，下菜单内容） */}
      {devOpen && curDev && (
        <div className="dev-mask" onClick={(e) => { if (e.target === e.currentTarget) closeDev(); }}>
          <div className="dev-panel">
            <div className="dev-head">
              <div><b>设备详情</b> · <span>{curDev.assetNo}</span></div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="btn ghost sm" onClick={() => setMngOpen(true)}>菜单管理</button>
                <button className="btn ghost sm" onClick={closeDev}>关闭</button>
              </div>
            </div>
            <div className="dev-wrap">
              <aside className="dev-nav">
                <div className="nt">功能菜单</div>
                <ul>
                  {MENUS.filter((m) => menuVisible[m.key] !== false).map((m) => (
                    <li key={m.key} className={devRight === m.key ? 'active' : ''} onClick={() => setDevRight(m.key)}>{m.label}</li>
                  ))}
                </ul>
              </aside>
              <div className="dev-main">
                <div className="dev-top">
                  <div className="white-img" onClick={gotoProduct} title="点击查看商品链接">
                    <div className="ph">🖼️</div>
                    <div>白底图 · 点我看商品</div>
                  </div>
                  <div className="rt-entry" onClick={() => setDevRight('rt')} title="进入实时数据与位置">
                    <div className="ic">📊</div>
                    <div>
                      <div className="v">{telemetry && telemetry.SOC != null ? telemetry.SOC + '%' : '—'}</div>
                      <div className="l">实时数据 → 数据与位置</div>
                    </div>
                    <div className="arrow">进入 ›</div>
                  </div>
                  <button className="btn ghost sm" style={{ alignSelf: 'flex-start' }} onClick={() => setMngOpen(true)}>菜单管理</button>
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
            <h3>菜单管理（隐藏不用的功能）</h3>
            <div>
              {MENUS.map((m) => (
                <div key={m.key} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 0', borderBottom: '1px dashed #e5e7eb' }}>
                  <input type="checkbox" checked={menuVisible[m.key] !== false}
                    onChange={(e) => { const v = e.target.checked; setMenuVisible((prev) => ({ ...prev, [m.key]: v })); if (!v && devRight === m.key) setDevRight('overview'); }} />
                  <span>{m.label}</span>
                </div>
              ))}
            </div>
            <div className="modal-foot"><button className="btn" onClick={() => setMngOpen(false)}>完成</button></div>
          </div>
        </div>
      )}

      {/* 维修记录详情 */}
      {maintOpen && curMaint && (
        <div className="mask" onClick={(e) => { if (e.target === e.currentTarget) setMaintOpen(false); }}>
          <div className="modal" style={{ width: 520 }}>
            <h3>维修记录详情</h3>
            <div className="kv">
              <div className="k">记录ID</div><div>{curMaint.id || curMaint.servicedAt}</div>
              <div className="k">时间</div><div>{curMaint.servicedAt}</div>
              <div className="k">维修人</div><div>{curMaint.person || curMaint.vendor || '—'}</div>
              <div className="k">费用</div><div><span className="tag orange">{curMaint.cost != null ? '¥' + curMaint.cost : '—'}</span></div>
            </div>
            <div style={{ marginTop: 14 }}><label>维修过程</label>
              <div style={{ background: '#fafbfc', border: '1px solid #e5e7eb', borderRadius: 6, padding: 10 }}>{curMaint.process || curMaint.note || '—'}</div></div>
            <div style={{ marginTop: 10 }}><label>维修图片（{(curMaint.images ? curMaint.images.length : 0)} 张）</label>
              <div>{(curMaint.images && curMaint.images.length) ? curMaint.images.map((_, i) => <span className="thumb" key={i}>图{i + 1}</span>) : <span className="note">无图片</span>}</div></div>
            <div className="modal-foot"><button className="btn" onClick={() => setMaintOpen(false)}>关闭</button></div>
          </div>
        </div>
      )}
    </PageCard>
  );
}
