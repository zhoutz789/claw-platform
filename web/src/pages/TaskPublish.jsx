import { useState, useEffect, useMemo } from 'react';
import {
  Card, Form, Input, InputNumber, Select, Button, Table, Tag, Segmented, Space, message, Alert, Typography, Spin, Empty,
} from 'antd';
import { SwapOutlined, RocketOutlined, CarOutlined, SoundOutlined, VideoCameraOutlined, CloudUploadOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useDroneOptions } from '../components/droneShared';
import { listMissions, createMission } from '../api/drone';

const { Text } = Typography;

// 无人机作业类型（后端 DroneMissionType 枚举合法值）
const MISSION_TYPES = [
  { value: 'SPRAY', label: '植保喷洒' },
  { value: 'CARGO', label: '物流配送' },
  { value: 'INSPECTION', label: '测绘巡检' },
  { value: 'RESCUE', label: '应急救援' },
];
const missionLabel = (t) => MISSION_TYPES.find((m) => m.value === t)?.label || t;

const ASSET_TYPE_ICON = { VEHICLE: '🚗', BATTERY: '🔋', CHARGER: '🔌', DRONE: '🚁' };

// 任务发布：按子菜单 mode 渲染单个功能（无人机/物流/广告/录像/出租/附近车辆）。
// 原 TaskPublish 的 Tabs 入口已拆为 6 个独立子页，本组件为共享实现，真实接口逻辑全部保留。
export default function TaskPublish({ mode }) {
  const { t } = useTranslation(['common', 'drone']);
  const { pilotOptions } = useDroneOptions();
  // 真实数据
  const [missions, setMissions] = useState([]);
  const [missionsLoading, setMissionsLoading] = useState(false);
  const [assets, setAssets] = useState([]);
  const [assetsLoading, setAssetsLoading] = useState(false);
  const [missionsError, setMissionsError] = useState(null);
  const [assetsError, setAssetsError] = useState(null);

  // 无人机发布
  const [droneForm] = Form.useForm();
  const [droneSubmitting, setDroneSubmitting] = useState(false);

  // 附近车辆筛选（按真实 assetType）
  const [nearFilter, setNearFilter] = useState('__all');

  useEffect(() => {
    let alive = true;
    setMissionsLoading(true);
    setMissionsError(null);
    listMissions({}).then((d) => { if (alive) setMissions(d || []); })
      .catch((e) => { if (alive) { const m = '加载无人机作业失败：' + e.message; message.error(m); setMissions([]); setMissionsError(m); } })
      .finally(() => { if (alive) setMissionsLoading(false); });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    let alive = true;
    setAssetsLoading(true);
    setAssetsError(null);
    api.get('/v1/assets').then((a) => { if (alive) setAssets(a || []); })
      .catch((e) => { if (alive) { const m = '加载真实资产失败：' + e.message; message.error(m); setAssets([]); setAssetsError(m); } })
      .finally(() => { if (alive) setAssetsLoading(false); });
    return () => { alive = false; };
  }, []);

  const nearTypes = useMemo(
    () => Array.from(new Set(assets.map((a) => a.assetType))).filter(Boolean),
    [assets]
  );
  const nearList = useMemo(
    () => (nearFilter === '__all' ? assets : assets.filter((a) => a.assetType === nearFilter)),
    [assets, nearFilter]
  );

  // 无人机任务发布 → POST /v1/drone-missions（真实写入）
  const submitDrone = async () => {
    let v;
    try { v = await droneForm.validateFields(); } catch { return; }
    setDroneSubmitting(true);
    try {
      const payload = {
        assetId: Number(v.assetId),
        missionType: v.missionType,
        payloadDesc: v.payloadDesc || '',
        areaHa: v.areaHa != null ? Number(v.areaHa) : null,
        trips: v.trips != null ? Number(v.trips) : null,
        flightMinutes: v.flightMinutes != null ? Number(v.flightMinutes) : null,
        pilotId: Number(v.pilotId),
        executedAt: new Date().toISOString(),
      };
      await createMission(payload);
      message.success('无人机作业已发布（真实写入后端）');
      droneForm.resetFields();
      const d = await listMissions({});
      setMissions(d || []);
    } catch (e) {
      message.error('发布失败：' + e.message);
    } finally {
      setDroneSubmitting(false);
    }
  };

  // 各功能模块的实现（保留真实接口逻辑；children 为等效于原 Tab 面板的内容）
  const TAB_MAP = {
    logi: {
      label: '发布物流任务',
      children: (
        <>
          <Card style={{ marginBottom: 14 }}>
            <Form layout="vertical" onFinish={() => message.info('任务大厅发布后端待接入（物流/广告/录像）')}>
              <Space size="large" wrap>
                <Form.Item label="取货地" required style={{ minWidth: 200 }}><Input placeholder="俄罗斯市场站" /></Form.Item>
                <Form.Item label="收货地" required style={{ minWidth: 200 }}><Input placeholder="金边机场" /></Form.Item>
                <Form.Item label="货物类型"><Select defaultValue="小件包裹" options={['小件包裹', '生鲜', '大件'].map((v) => ({ label: v, value: v }))} /></Form.Item>
                <Form.Item label="报酬 ($)" required><InputNumber min={1} placeholder="5.00" /></Form.Item>
                <Form.Item label="截止时间"><Input placeholder="30 分钟内" /></Form.Item>
              </Space>
              <Button type="primary" htmlType="submit">发布到任务大厅</Button>
            </Form>
          </Card>
          <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>任务大厅 · 待接单</h4>
          <Empty description="暂无真实任务数据（后端待接入）" />
        </>
      ),
    },
    near: {
      label: '查看附近车辆',
      children: (
        <Card>
          <Space style={{ marginBottom: 12 }}>
            <Text>筛选：</Text>
            <Segmented value={nearFilter} onChange={setNearFilter}
              options={[{ label: '全部', value: '__all' }, ...nearTypes.map((t) => ({ label: t, value: t }))]} />
          </Space>
          {assetsLoading ? <Spin /> : nearList.length === 0 ? (
            <Empty description="暂无真实资产（后端待接入）" />
          ) : (
            <Space direction="vertical" style={{ width: '100%' }}>
              {nearList.map((a) => (
                <div key={a.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0', borderBottom: '1px solid #f0f0f0' }}>
                  <div style={{ fontSize: 22 }}>{ASSET_TYPE_ICON[a.assetType] || '📦'}</div>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600 }}>{a.assetNo}</div>
                    <div style={{ fontSize: 12, color: '#888' }}>资产类型 {a.assetType} · 状态 {a.status}</div>
                  </div>
                  <Tag>{'—'}</Tag>
                  <Button size="small" onClick={() => message.info('附近设备调度 / 呼叫后端待接入')}>查看</Button>
                </div>
              ))}
            </Space>
          )}
        </Card>
      ),
    },
    ad: {
      label: '发布广告自媒体',
      children: (
        <Card>
          <Form layout="vertical" onFinish={() => message.info('任务大厅发布后端待接入（物流/广告/录像）')}>
            <Space size="large" wrap>
              <Form.Item label="广告主" required style={{ minWidth: 200 }}><Input placeholder="品牌 / 商家" /></Form.Item>
              <Form.Item label="投放时长"><Select defaultValue="1 周" options={['1 天', '1 周', '1 月'].map((v) => ({ label: v, value: v }))} /></Form.Item>
              <Form.Item label="报酬 ($)"><InputNumber min={1} placeholder="20.0" /></Form.Item>
            </Space>
            <Form.Item label="素材"><Input placeholder="上传图片 / 视频" /></Form.Item>
            <Alert type="info" showIcon style={{ marginBottom: 12 }} message="车身 / 屏显媒体任务 → 设备接单展示 → 脱敏录像回传核验。" />
            <Button type="primary" htmlType="submit">发布到广告任务大厅</Button>
          </Form>
        </Card>
      ),
    },
    video: {
      label: '查看录像数据',
      children: (
        <Card>
          <Space size="large" wrap style={{ marginBottom: 12 }}>
            <StatBox v="—（待接入）" l="脱敏片段（今日）" />
            <StatBox v="—（待接入）" l="已脱敏率" color="#1677ff" />
            <StatBox v="—（待接入）" l="可调阅设备" />
          </Space>
          <Alert type="info" showIcon message="行车记录脱敏回传；人脸 / 车牌自动打码，仅授权设备 / 任务可见，符合隐私合规。录像播放器与脱敏引擎由专项开发，UI 预留检索与预览位。" />
        </Card>
      ),
    },
    rent: {
      label: '我要出租',
      children: (
        <Card style={{ maxWidth: 560 }}>
          <Form layout="vertical" onFinish={() => message.info('资产出租发布后端待接入')}>
            <Form.Item label="出租资产">
              <Select placeholder={assetsLoading ? '加载资产中…' : '选择真实资产'}
                options={assets.map((x) => ({ label: `${x.assetNo} · #${x.id} (${x.assetType})`, value: x.id }))} />
            </Form.Item>
            <Space size="large" wrap>
              <Form.Item label="租金"><Input placeholder="¥26 / 月" /></Form.Item>
              <Form.Item label="租期"><Select defaultValue="月" options={['日', '月', '季'].map((v) => ({ label: v, value: v }))} /></Form.Item>
            </Space>
            <Text strong style={{ display: 'block', marginBottom: 6 }}>权限模板（最小权限 · 模板化）</Text>
            <Segmented block options={[
              { label: '仅使用', value: 'use' },
              { label: '使用+定位', value: 'use_loc' },
              { label: '全权', value: 'full' },
            ]} />
            <div style={{ marginTop: 8 }}>
              <Tag color="green">开关使用</Tag><Tag color="blue">实时定位</Tag><Tag color="red">收益数据(隐藏)</Tag><Tag color="red">产权(禁止)</Tag>
            </div>
            <Alert type="info" showIcon style={{ margin: '8px 0 12px' }} message="权限模板沉淀为「仅使用 / 使用+定位 / 全权」，减少每次勾选；实时定位承租人可见、历史轨迹仅本人使用期。" />
            <Button type="primary" htmlType="submit">发布出租任务</Button>
          </Form>
        </Card>
      ),
    },
    drone: {
      label: '无人机任务',
      children: (
        <Card>
          <Alert type="info" showIcon style={{ marginBottom: 12 }} message={<span>无人机任务须绑定 OPERATIONAL 空域与有效飞手资质；NFZ 禁飞、RESTRICTED 限飞。作业将真实写入后端 drone_missions。 <Link to="/drone-ops">{t('drone:common.gotoOps')}</Link></span>} />
          <Form layout="vertical" style={{ marginBottom: 14 }} form={droneForm} onFinish={submitDrone}>
            <Space size="large" wrap align="end">
              <Form.Item label="作业类型" name="missionType" rules={[{ required: true, message: '请选择作业类型' }]} style={{ minWidth: 160 }}>
                <Select options={MISSION_TYPES} placeholder="植保/物流/巡检/救援" />
              </Form.Item>
              <Form.Item label="绑定资产" name="assetId" rules={[{ required: true, message: '请选择资产' }]} style={{ minWidth: 220 }}>
                <Select placeholder={assetsLoading ? '加载资产中…' : '选择真实资产'}
                  options={assets.map((x) => ({ label: `${x.assetNo} · #${x.id}`, value: x.id }))} />
              </Form.Item>
              <Form.Item label="作业描述" name="payloadDesc" style={{ minWidth: 180 }}><Input placeholder="如 农药 40L / 货箱 20kg" /></Form.Item>
              <Form.Item label="面积(公顷)" name="areaHa"><InputNumber min={0} placeholder="18.5" /></Form.Item>
              <Form.Item label="趟数" name="trips"><InputNumber min={0} placeholder="3" /></Form.Item>
              <Form.Item label="飞行时长(分)" name="flightMinutes"><InputNumber min={0} placeholder="96" /></Form.Item>
              <Form.Item label={t('drone:mission.form.pilot')} name="pilotId"
                rules={[{ required: true, message: t('form.required', { label: t('drone:mission.form.pilot') }) }]}>
                <Select showSearch optionFilterProp="label" placeholder={t('drone:common.selectPlaceholder')} options={pilotOptions} />
              </Form.Item>
              <Form.Item><Button type="primary" loading={droneSubmitting} htmlType="submit">发布任务</Button></Form.Item>
            </Space>
          </Form>
          {missionsLoading ? <Spin /> : missions.length === 0 ? (
            <Empty description="暂无真实无人机作业（后端待接入）" />
          ) : (
            <Table rowKey="id" pagination={false} dataSource={missions}
              columns={[
                { title: '任务', key: 'task', render: (_, r) => <span>{missionLabel(r.missionType)}{r.payloadDesc ? ` · ${r.payloadDesc}` : ''}</span> },
                { title: '类型', dataIndex: 'missionType', render: (v) => <Tag color="purple">{missionLabel(v)}</Tag> },
                { title: '资产', dataIndex: 'assetId', render: (v) => `#${v}` },
                { title: '面积(ha)', dataIndex: 'areaHa', render: (v) => v ?? '—' },
                { title: '趟数', dataIndex: 'trips', render: (v) => v ?? '—' },
                { title: '飞行(分)', dataIndex: 'flightMinutes', render: (v) => v ?? '—' },
                { title: '飞手', dataIndex: 'pilotId', render: (v) => `#${v}` },
              ]} />
          )}
        </Card>
      ),
    },
  };

  const tab = TAB_MAP[mode] || TAB_MAP.drone;

  return (
    <PageCard title={`任务发布 · ${tab.label}`}>
      <Alert type="info" showIcon style={{ marginBottom: 14 }}
        message="各功能模块均设「任务大厅」：需求方发布任务，附近车辆 / 设备按能力标签 + 地理位置匹配并自主接单。涵盖物流、客运（公交 / 打的 / 顺风车，货运归入物流）、广告自媒体、录像数据、资产出租、无人机低空作业。" />
      {missionsError && (
        <Alert type="error" showIcon closable style={{ marginBottom: 12 }}
          message={missionsError} onClose={() => setMissionsError(null)} />
      )}
      {assetsError && (
        <Alert type="error" showIcon closable style={{ marginBottom: 12 }}
          message={assetsError} onClose={() => setAssetsError(null)} />
      )}
      {tab.children}
    </PageCard>
  );
}

function StatBox({ v, l, color }) {
  return (
    <div style={{ padding: '12px 16px', border: '1px solid #f0f0f0', borderRadius: 8, minWidth: 140 }}>
      <div style={{ fontSize: 24, fontWeight: 800, color: color || 'inherit' }}>{v}</div>
      <div style={{ fontSize: 12, color: '#888' }}>{l}</div>
    </div>
  );
}
