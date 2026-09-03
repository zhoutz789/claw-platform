import { useState, useEffect } from 'react';
import { Card, Segmented, Tag, Modal, Descriptions, Button, Alert, Space, message, Divider, Input, Spin, Empty, List } from 'antd';
import { ScanOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
import { useTranslation } from 'react-i18next';

const ROLE_ENTRY = [
  { ic: '🏪', t: '入驻商家' }, { ic: '🛠️', t: '服务站管理' }, { ic: '🤝', t: '资产租赁' },
  { ic: '🔋', t: '换电/共享' }, { ic: '🚁', t: '无人机作业' },
];

const STATUS_LABEL = {
  IN_STOCK: '在库', IN_USE: '使用中', SHARED: '共享中', REPAIR: '维修中',
  DISABLED: '停用', RETIRED: '退役', RECYCLED: '回收中', SCRAPPED: '已报废',
};

// 统一 App 门户：「我的资产」接真实 /v1/assets；详情接 /v1/assets/{id} + 收益接 /v1/admin/manufacturer/assets/{id}/trace；
// 「绑定新设备」接 POST /v1/assets/{id}/bind。定位/能力后端无 → 显示待接入。
export default function AppPortal() {  const { t } = useTranslation('common');

  const [role, setRole] = useState('owner');
  const [bind, setBind] = useState(false);
  const [sel, setSel] = useState(null); // 选中的资产 id
  const [assets, setAssets] = useState([]);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState(null); // { asset, trace }
  const [detailLoading, setDetailLoading] = useState(false);
  const [bindInput, setBindInput] = useState('');
  const [bindStation, setBindStation] = useState('');
  const [bindLoading, setBindLoading] = useState(false);
  const isOwner = role === 'owner';

  useEffect(() => {
    let alive = true;
    setLoading(true);
    api.get('/v1/assets').then((a) => { if (alive) setAssets(a || []); })
      .catch((e) => { if (alive) { message.error('加载真实资产失败：' + e.message); setAssets([]); } })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  // 打开详情：并行取真实资产字段 + 收益（trace 接口可能 404，容错为 null）
  const openDetail = (id) => {
    setSel(id);
    setDetail(null);
    setDetailLoading(true);
    Promise.all([
      api.get(`/v1/assets/${id}`),
      api.get(`/v1/admin/manufacturer/assets/${id}/trace`).catch(() => null),
    ]).then(([asset, trace]) => {
      setDetail({ asset, trace: trace || null });
    }).catch((e) => {
      message.error('加载设备详情失败：' + e.message);
    }).finally(() => setDetailLoading(false));
  };

  // 绑定新设备：POST /v1/assets/{id}/bind
  const doBind = async () => {
    const q = bindInput.trim();
    if (!q) { message.warning(t('common:m940')); return; }
    const target = assets.find((a) => String(a.id) === q || a.assetNo === q);
    const id = target ? target.id : (Number(q) ? Number(q) : null);
    if (!id) { message.error(t('common:m941')); return; }
    setBindLoading(true);
    try {
      await api.post(`/v1/assets/${id}/bind`, {
        assetId: id,
        stationId: bindStation ? Number(bindStation) : null,
        location: null,
      });
      message.success(`已绑定设备 #${id}`);
      setBind(false); setBindInput(''); setBindStation('');
    } catch (e) {
      message.error('绑定失败：' + e.message);
    } finally {
      setBindLoading(false);
    }
  };

  const phone = {
    width: 300, margin: '0 auto', background: 'var(--surface)', border: '1px solid var(--line)',
    borderRadius: 20, padding: 14, boxShadow: '0 8px 30px rgba(0,0,0,.12)',
  };

  return (
    <PageCard title={t('common:m942')} extra={
      <Segmented value={role} onChange={setRole}
        options={[{ label: t('common:m943'), value: 'owner' }, { label: t('common:m944'), value: 'tenant' }]} />
    }>
      <Alert type="info" showIcon style={{ marginBottom: 14 }}
        message="工作站 + 用户端整合在一个 App；我的页按角色展示入口，资产菜单展示设备全部信息。扫码绑定仅一次，再绑需产权转移；租赁仅给产权人勾选权限。" />

      <div style={phone}>
        <div style={{ fontWeight: 800, fontSize: 16, marginBottom: 4 }}>{t('common:m582')}<small style={{ fontSize: 10, color: 'var(--muted)' }}>{t('common:m945')}</small></div>
        <Divider style={{ margin: '8px 0' }} />
        <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>{t('common:m946')}</div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 8 }}>
          {ROLE_ENTRY.map((e) => (
            <div key={e.t} style={{ textAlign: 'center', padding: 8, borderRadius: 10, background: 'var(--brand-soft)' }}>
              <div style={{ fontSize: 20 }}>{e.ic}</div>
              <div style={{ fontSize: 11 }}>{e.t}</div>
            </div>
          ))}
        </div>
        <div style={{ fontSize: 12, color: 'var(--muted)', margin: '12px 0 6px' }}>{t('common:m947')}</div>
        {loading ? <div style={{ textAlign: 'center', padding: 16 }}><Spin /></div> : assets.length === 0 ? (
          <Empty description={t('common:m948')} />
        ) : assets.map((d) => (
          <div key={d.id} onClick={() => openDetail(d.id)} style={{ border: '1px solid var(--line)', borderRadius: 10, padding: 10, marginBottom: 8, cursor: 'pointer' }}>
            <div style={{ fontWeight: 700 }}>{d.assetNo}</div>
            <div style={{ fontSize: 12, color: 'var(--muted)' }}>{STATUS_LABEL[d.status] || d.status} · #{d.id}</div>
            <Space wrap size={4} style={{ marginTop: 4 }}><Tag color="blue">{t('common:m949')}{d.assetType}</Tag></Space>
          </div>
        ))}
        <Button type="primary" block icon={<ScanOutlined />} style={{ marginTop: 6 }} onClick={() => setBind(true)}>{t('common:m950')}</Button>
      </div>

      <Modal open={!!sel} title={`设备详情 · ${detail?.asset?.assetNo || sel}`} onCancel={() => setSel(null)} footer={null}>
        {detailLoading ? <div style={{ textAlign: 'center', padding: 16 }}><Spin /></div> : detail ? (
          <>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label={t('common:m8')}><Tag color="blue">{STATUS_LABEL[detail.asset.status] || detail.asset.status}</Tag></Descriptions.Item>
              <Descriptions.Item label={t('common:m168')}>{detail.asset.assetNo}</Descriptions.Item>
              <Descriptions.Item label={t('common:m951')}>{t('common:m188')}</Descriptions.Item>
              <Descriptions.Item label={t('common:m184')}>{t('common:m188')}</Descriptions.Item>
            </Descriptions>
            <h4 style={{ margin: '12px 0 8px' }}>{t('common:m952')}{isOwner ? '完整' : '承租受限'}）</h4>
            {!isOwner ? (
              <Alert type="warning" showIcon message="承租人视角：收益/产权隐藏，仅可见实时定位与自身使用轨迹。" />
            ) : detail.trace ? (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0' }}>
                  <span>{t('common:m953')}</span>
                  <span style={{ color: 'var(--brand)' }}>¥{detail.trace.totalRevenue ?? '—'}</span>
                </div>
                <List size="small" dataSource={detail.trace.vehicleOps || []}
                  locale={{ emptyText: '暂无运营记录（后端待接入）' }}
                  renderItem={(op) => (
                    <List.Item>
                      <span>{op.opType}{op.startedAt ? ` · ${new Date(op.startedAt).toLocaleString()}` : ''}</span>
                      <span style={{ color: 'var(--brand)' }}>¥{op.revenue ?? '—'}</span>
                    </List.Item>
                  )} />
              </>
            ) : (
              <Empty description={t('common:m954')} />
            )}
          </>
        ) : <Empty description={t('common:m955')} />}
      </Modal>

      <Modal open={bind} title={t('common:m956')} onCancel={() => setBind(false)} footer={null}>
        <div style={{ textAlign: 'center', padding: 16, border: '2px dashed var(--line)', borderRadius: 12, marginBottom: 12 }}>
          <ScanOutlined style={{ fontSize: 40, color: 'var(--brand)' }} />
          <div style={{ color: 'var(--muted)', marginTop: 6 }}>{t('common:m957')}</div>
        </div>
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message="绑定只能一次；再绑定需产权转移。新用户付款设备当前价值即获所有权，原用户丧失一切权利。" />
        <Space style={{ width: '100%' }} wrap>
          <Input style={{ maxWidth: 200 }} placeholder={t('common:m958')} value={bindInput} onChange={(e) => setBindInput(e.target.value)} />
          <Input style={{ maxWidth: 160 }} placeholder={t('common:m959')} addonBefore={t('common:m512')} value={bindStation} onChange={(e) => setBindStation(e.target.value)} />
          <Button type="primary" loading={bindLoading} onClick={doBind}>{t('common:m960')}</Button>
        </Space>
      </Modal>
    </PageCard>
  );
}
