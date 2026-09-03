import { useState, useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { message, Spin } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { useTranslation } from 'react-i18next';

/* 严格还原 increment3-d-goods-wizard.html 原型 */
const RATE = 4100; // 1 USD ≈ 4100 KHR（柬埔寨本币，汇率可配置）
const CATEGORIES = [
  { id: 'C1', name: '光伏设备', props: [{ name: '额定功率', type: 'text', req: true }, { name: '电池容量', type: 'text', req: true }, { name: '防护等级', type: 'select', opts: ['IP65', 'IP67'], req: false }] },
  { id: 'C2', name: '电池', props: [{ name: '标称电压', type: 'text', req: true }, { name: '容量', type: 'text', req: true }, { name: '循环次数', type: 'number', req: false }] },
  { id: 'C3', name: '无人机', props: [{ name: '载重', type: 'text', req: true }, { name: '续航', type: 'text', req: true }, { name: '飞行分钟', type: 'number', req: false }] },
];
const BRANDS = ['SunPower', 'CATL', 'DJI', '比亚迪', '宁德时代'];
const STEP_DEFS = [{ n: '选产品/类别' }, { n: '选品牌' }, { n: '标题/主图/属性/SKU/详情' }, { n: '直播/分享' }];

const TINY_SRC = 'https://cdn.jsdelivr.net/npm/tinymce@6.8.3/tinymce.min.js';

export default function ProductWizard() {  const { t } = useTranslation('common');

  const navigate = useNavigate();
  const [params] = useSearchParams();
  const editId = params.get('editId');

  const [loading, setLoading] = useState(false);
  const [products, setProducts] = useState([]);
  const [manufacturers, setManufacturers] = useState([]);
  const [step, setStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({
    productId: '', product: '', category: '', brand: '', manufacturerId: null,
    title: '', skus: [{ name: '', price: '', qty: '', img: false }],
    mainImgs: [], whiteImg: false, live: false, share: false, review: false, desc: '', catVals: {},
  });
  const descRef = useRef(null);
  const tinyReady = useRef(false);

  const curCat = () => CATEGORIES.find((c) => c.name === form.category);
  const setF = (patch) => setForm((f) => ({ ...f, ...patch }));
  const setSku = (i, patch) => setForm((f) => ({ ...f, skus: f.skus.map((s, j) => (j === i ? { ...s, ...patch } : s)) }));

  // 加载产品 + 厂家；编辑回填
  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      api.get('/v1/admin/manufacturer/products'),
      api.get('/v1/admin/manufacturer/manufacturers'),
    ]).then(([p, m]) => {
      if (!alive) return;
      setProducts(Array.isArray(p) ? p : []);
      setManufacturers(Array.isArray(m) ? m : []);
      if (editId && Array.isArray(p)) {
        const g = p.find((x) => String(x.id) === String(editId));
        if (g) setForm((f) => ({
          ...f, productId: g.id, product: g.name || '', category: g.category || '', brand: g.brand || '',
          manufacturerId: g.manufacturerId || null, title: g.name || '',
          desc: g.detail || '', catVals: {},
        }));
      }
    }).catch(() => { if (alive) { message.error(t('common:m736')); setProducts([]); setManufacturers([]); } })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [editId]);

  // TinyMCE：进入 step2 时初始化，离开时移除
  const ensureTiny = (cb) => {
    if (window.tinymce) { cb(); return; }
    const s = document.createElement('script');
    s.src = TINY_SRC; s.referrerPolicy = 'origin';
    s.onload = () => { tinyReady.current = true; cb(); };
    s.onerror = () => message.error(t('common:m737'));
    document.head.appendChild(s);
  };
  const initTiny = () => {
    ensureTiny(() => {
      if (!window.tinymce) return;
      window.tinymce.remove('#fDesc');
      window.tinymce.init({
        selector: '#fDesc', height: 280, menubar: false,
        plugins: 'lists link image table code',
        toolbar: 'undo redo | blocks | bold italic | alignleft aligncenter alignright | bullist numlist | link image table | code',
        setup: (ed) => { ed.on('input', () => { setForm((f) => ({ ...f, desc: ed.getContent() })); }); },
        init_instance_callback: (ed) => { if (form.desc) ed.setContent(form.desc); },
      });
    });
  };
  useEffect(() => {
    if (step === 2) { const t = setTimeout(initTiny, 50); return () => clearTimeout(t); }
    if (window.tinymce) { try { window.tinymce.remove('#fDesc'); } catch (e) { /* noop */ } }
    // eslint-disable-next-line
  }, [step]);
  useEffect(() => () => { if (window.tinymce) { try { window.tinymce.remove('#fDesc'); } catch (e) { /* noop */ } } }, []);

  const updKhr = (i) => { const el = document.getElementById('khr' + i); if (el) { const v = form.skus[i].price; el.textContent = v ? ('≈ ' + (Number(v) * RATE).toLocaleString() + ' KHR') : ''; } };
  const addSku = () => setForm((f) => ({ ...f, skus: [...f.skus, { name: '', price: '', qty: '', img: false }] }));
  const delSku = (i) => setForm((f) => ({ ...f, skus: f.skus.filter((_, j) => j !== i) }));
  const addImg = () => setForm((f) => (f.mainImgs.length >= 10 ? f : { ...f, mainImgs: [...f.mainImgs, true] }));

  const next = () => {
    if (step < 3) { setStep(step + 1); return; }
    // 提交
    const cat = curCat();
    const missing = cat ? cat.props.filter((p) => p.req && !form.catVals[p.name]).map((p) => p.name) : [];
    if (missing.length) { message.warning('类别必填属性未填：' + missing.join('、')); return; }
    if (!form.title.trim()) { message.warning(t('common:m738')); return; }
    if (!form.manufacturerId) { message.warning(t('common:m739')); return; }
    setSubmitting(true);
    const body = {
      manufacturerId: form.manufacturerId,
      name: form.title.trim(),
      assetType: form.category === '无人机' ? 'DRONE' : (form.category === '电池' ? 'BATTERY' : (form.category === '光伏设备' ? 'PV_STATION' : 'VEHICLE')),
      brand: form.brand || null,
      category: form.category || null,
      description: form.desc || '',
      detail: form.desc || '',
      coverImagesJson: JSON.stringify(form.mainImgs.map(() => 'placeholder')),
      liveEnabled: form.live,
      liveUrl: '',
      rewardRate: 0,
    };
    const req = editId
      ? api.put('/v1/admin/manufacturer/products/' + editId, body)
      : api.post('/v1/admin/manufacturer/products', body);
    req.then(async (prod) => {
      const pid = prod.id || editId;
      let skuOk = 0;
      for (let i = 0; i < form.skus.length; i++) {
        const s = form.skus[i];
        if (!s.name) continue;
        await api.post('/v1/admin/manufacturer/skus', {
          productId: pid, skuCode: 'SKU-' + pid + '-' + (i + 1), price: Number(s.price) || 0,
          currency: 'USD', specsJson: JSON.stringify({ name: s.name, qty: s.qty, img: s.img }), status: 'ACTIVE',
        });
        skuOk++;
      }
      try { await api.post('/v1/admin/manufacturer/products/' + pid + '/share'); } catch (e) { /* 分享可选 */ }
      message.success(editId ? '商品已更新' : '商品已发布（' + skuOk + ' 个 SKU）');
      navigate('/goods-list');
    }).catch((e) => { message.error('发布失败：' + (e.message || '未知')); })
      .finally(() => setSubmitting(false));
  };
  const prev = () => { if (step > 0) setStep(step - 1); };

  const css = `
    .wiz{background:#fff;border:1px solid #e5e7eb;border-radius:8px;padding:24px;}
    .steps{display:flex;margin-bottom:20px;}
    .stp{flex:1;text-align:center;position:relative;}
    .stp .num{width:28px;height:28px;border-radius:50%;background:#e5e7eb;color:#8a9099;line-height:28px;margin:0 auto 6px;font-size:13px;}
    .stp.active .num{background:#1677ff;color:#fff;}
    .stp.done .num{background:#18a058;color:#fff;}
    .stp .label{font-size:12px;color:#8a9099;}
    .stp.active .label{color:#1677ff;font-weight:600;}
    .stp:not(:last-child)::after{content:'';position:absolute;top:14px;left:50%;width:100%;height:2px;background:#e5e7eb;z-index:0;}
    .fr{margin-bottom:14px;}
    .fr label{display:block;font-size:13px;margin-bottom:6px;}
    .fr input,.fr select{width:100%;padding:8px 10px;border:1px solid #e5e7eb;border-radius:6px;font-size:13px;}
    .af{background:#f6f8fa;border:1px dashed #e5e7eb;border-radius:6px;padding:12px;font-size:13px;color:#8a9099;}
    .af b{color:#1f2329;}
    .upload{display:flex;gap:8px;flex-wrap:wrap;margin-top:6px;}
    .up-box{width:74px;height:74px;border:1px dashed #e5e7eb;border-radius:6px;display:flex;align-items:center;justify-content:center;color:#8a9099;font-size:11px;text-align:center;cursor:pointer;background:#fff;}
    .up-box.sm{width:48px;height:48px;}
    .up-box.filled{background:#e6f0ff;border-color:#1677ff;color:#1677ff;}
    .counter{font-size:12px;color:#8a9099;float:right;}
    .sku-row{display:flex;gap:8px;margin-bottom:8px;align-items:center;flex-wrap:wrap;}
    .sku-row input{padding:7px 10px;border:1px solid #e5e7eb;border-radius:6px;font-size:13px;}
    .khr{font-size:11px;color:#d46b08;}
    .cat-attr{border:1px dashed #1677ff;border-radius:6px;padding:12px;background:#fafbff;margin-top:6px;}
    .cat-attr h5{margin:0 0 10px;font-size:13px;color:#1677ff;}
    .btn{background:#1677ff;color:#fff;border:none;border-radius:6px;padding:6px 14px;cursor:pointer;font-size:13px;}
    .btn.ghost{background:#fff;color:#1677ff;border:1px solid #1677ff;}
    .btn.sm{padding:3px 10px;font-size:12px;}
    .btn.danger{background:#ff4d4f;color:#fff;}
    .wiz-foot{display:flex;justify-content:space-between;margin-top:20px;border-top:1px solid #e5e7eb;padding-top:16px;}
    .note{font-size:12px;color:#8a9099;}
  `;

  const renderStep = () => {
    if (step === 0) {
      return (
        <>
          <div className="fr"><label>{t('common:m740')}</label>
            <select value={form.productId} onChange={(e) => { const p = products.find((x) => String(x.id) === e.target.value); setF({ productId: e.target.value, product: p ? p.name : '', manufacturerId: p ? p.manufacturerId : form.manufacturerId, category: p ? (p.category || '') : form.category }); }}>
              <option value="">{t('common:m741')}</option>
              {products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select></div>
          <div className="fr"><label>{t('common:m742')}</label>
            <select value={form.category} onChange={(e) => setF({ category: e.target.value, catVals: {} })}>
              <option value="">{t('common:m741')}</option>
              {CATEGORIES.map((c) => <option key={c.id} value={c.name}>{c.name}</option>)}
            </select></div>
          <div className="af">{form.product ? <><b>{t('common:m743')}</b>{t('common:m744')}</> : '选择产品后，基本属性将自动带出。'}{form.category ? <><br /><b>{t('common:m745')}{form.category}{t('common:m746')}</b>{t('common:m747')}{curCat().props.length}{t('common:m748')}</> : null}</div>
        </>
      );
    }
    if (step === 1) {
      return (
        <>
          <div className="fr"><label>{t('common:m90')}</label>
            <select value={form.brand} onChange={(e) => setF({ brand: e.target.value })}>
              <option value="">{t('common:m741')}</option>
              {BRANDS.map((b) => <option key={b} value={b}>{b}</option>)}
            </select></div>
          <div className="fr"><label>{t('common:m749')}</label>
            <select value={form.manufacturerId || ''} onChange={(e) => setF({ manufacturerId: e.target.value ? Number(e.target.value) : null })}>
              <option value="">{t('common:m741')}</option>
              {manufacturers.map((m) => <option key={m.id} value={m.id}>{m.name || ('厂商#' + m.id)}</option>)}
            </select></div>
          <p className="note">{t('common:m750')}</p>
        </>
      );
    }
    if (step === 2) {
      const cat = curCat();
      return (
        <>
          <div className="fr"><label>{t('common:m751')}</label>
            <input maxLength={30} value={form.title} onChange={(e) => setF({ title: e.target.value })} placeholder={t('common:m752')} />
            <span className="counter">{form.title.length}/30</span></div>
          <div className="fr"><label>{t('common:m753')}</label>
            <div className="upload">
              {form.mainImgs.map((_, i) => <div key={i} className="up-box filled">{t('common:m119')}{i + 1}</div>)}
              {form.mainImgs.length < 10 && <div className="up-box" onClick={addImg}>{t('common:m754')}</div>}
            </div></div>
          <div className="fr"><label>{t('common:m755')}</label>
            <div className="upload"><div className={'up-box' + (form.whiteImg ? ' filled' : '')} onClick={() => setF({ whiteImg: !form.whiteImg })}>{form.whiteImg ? '已上传' : '+ 白底图'}</div></div></div>
          {cat ? (
            <div className="fr"><label>{t('common:m756')}{cat.name}{t('common:m757')}</label>
              <div className="cat-attr"><h5>{cat.name}{t('common:m758')}{cat.props.length}{t('common:m759')}</h5>
                {cat.props.map((p, i) => (
                  <div className="fr" key={i} style={{ marginBottom: 10 }}>
                    <label>{p.name}{p.req ? ' *' : ''} {p.type === 'select' ? '(下拉)' : ''}</label>
                    {p.type === 'select' ? (
                      <select value={form.catVals[p.name] || ''} onChange={(e) => setForm((f) => ({ ...f, catVals: { ...f.catVals, [p.name]: e.target.value } }))}>
                        <option value="">{t('common:m760')}</option>
                        {(p.opts || []).map((o) => <option key={o} value={o}>{o}</option>)}
                      </select>
                    ) : (
                      <input value={form.catVals[p.name] || ''} onChange={(e) => setForm((f) => ({ ...f, catVals: { ...f.catVals, [p.name]: e.target.value } }))} placeholder={'请输入' + p.name} />
                    )}
                  </div>
                ))}
              </div>
            </div>
          ) : <div className="af">{t('common:m761')}</div>}
          <div className="fr"><label>{t('common:m762')}</label>
            <div>
              {form.skus.map((s, i) => (
                <div className="sku-row" key={i}>
                  <input placeholder={t('common:m763')} style={{ width: 140 }} value={s.name} onChange={(e) => setSku(i, { name: e.target.value })} />
                  <div style={{ display: 'flex', flexDirection: 'column' }}>
                    <input placeholder={t('common:m764')} style={{ width: 110 }} value={s.price} onChange={(e) => { setSku(i, { price: e.target.value }); setTimeout(updKhr, 0, i); }} />
                    <span className="khr" id={'khr' + i}>{s.price ? ('≈ ' + (Number(s.price) * RATE).toLocaleString() + ' KHR') : ''}</span>
                  </div>
                  <input placeholder={t('common:m765')} style={{ width: 70 }} value={s.qty} onChange={(e) => setSku(i, { qty: e.target.value })} />
                  <div className={'up-box sm' + (s.img ? ' filled' : '')} onClick={() => setSku(i, { img: !s.img })} title={t('common:m766')}>{s.img ? '图' : '+图'}</div>
                  <button className="btn ghost sm" onClick={() => delSku(i)}>{t('common:m767')}</button>
                </div>
              ))}
            </div>
            <button className="btn ghost sm" onClick={addSku}>{t('common:m768')}</button></div>
          <div className="fr"><label>{t('common:m769')}</label>
            <textarea id="fDesc" defaultValue={form.desc} style={{ width: '100%' }} /></div>
        </>
      );
    }
    // step 3
    return (
      <>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}><input type="checkbox" checked={form.live} onChange={(e) => setF({ live: e.target.checked })} />{t('common:m770')}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}><input type="checkbox" checked={form.share} onChange={(e) => setF({ share: e.target.checked })} />{t('common:m771')}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}><input type="checkbox" checked={form.review} onChange={(e) => setF({ review: e.target.checked })} />{t('common:m772')}</div>
        <div style={{ background: '#fffbe6', border: '1px solid #ffe58f', borderRadius: 6, padding: '10px 12px', fontSize: 12, color: '#ad6800', marginTop: 8 }}>{t('common:m773')}<b>{t('common:m774')}</b>{t('common:m775')}</div>
        <div className="af" style={{ marginTop: 12 }}><b>{t('common:m776')}</b><br />{t('common:m777')}{form.product || '—'}{t('common:m778')}{form.category || '—'}{t('common:m779')}{form.brand || '—'}<br />{t('common:m780')}{form.title || '—'}　SKU：{form.skus.filter((s) => s.name).length}{t('common:m781')}{form.desc && form.desc.length > 20 ? '是' : '否'}{t('common:m782')}{Object.keys(form.catVals).filter((k) => form.catVals[k]).length}{t('common:m783')}</div>
      </>
    );
  };

  return (
    <PageCard title={editId ? '编辑商品（向导）' : '发布商品（向导）'} extra={<span className="note">{t('common:m784')}</span>}>
      <style>{css}</style>
      <Spin spinning={loading}>
        <div className="wiz">
          <h3 style={{ margin: '0 0 16px' }}>{editId ? '编辑商品' : '发布商品'} <span className="note">{t('common:m785')}</span></h3>
          <div className="steps">
            {STEP_DEFS.map((s, i) => (
              <div key={i} className={'stp' + (i === step ? ' active' : (i < step ? ' done' : ''))}>
                <div className="num">{i < step ? '✓' : i + 1}</div>
                <div className="label">{s.n}</div>
              </div>
            ))}
          </div>
          {renderStep()}
          <div className="wiz-foot">
            <button className="btn ghost" onClick={() => navigate('/goods-list')}>{t('common:m96')}</button>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn ghost" disabled={step === 0} onClick={prev}>{t('common:m705')}</button>
              <button className="btn" disabled={submitting} onClick={next}>{step === 3 ? (submitting ? '提交中…' : '提交') : '下一步'}</button>
            </div>
          </div>
        </div>
      </Spin>
    </PageCard>
  );
}
