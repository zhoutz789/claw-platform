import { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Card, Form, Input, InputNumber, Select, Button, Table, Tag, Segmented, Space, message, Alert, Typography, Spin, Empty, List, Progress, Divider,
} from 'antd';
import { SwapOutlined, RocketOutlined, CarOutlined, SoundOutlined, VideoCameraOutlined, CloudUploadOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useDroneOptions } from '../components/droneShared';
// 收益明细区块统一由 components/AssetTaskEarnings.jsx 导出（任务大厅「完成」后回填 +
// 资产详情「任务收益」Tab 共用），本文件不再重复声明，避免同名定义导致构建 / 运行冲突。
import { EarningsBlock } from '../components/AssetTaskEarnings';

const { Text } = Typography;

// 无人机作业类型（后端 DroneMissionType 枚举合法值）。
// 保持 [{value,label}] 形态：Select options 与列表标签共用同一份数据，改成字符串数组会同时破坏两处。
const MISSION_TYPES = [
  { value: 'SPRAY', label: '植保喷洒' },
  { value: 'CARGO', label: '物流配送' },
  { value: 'INSPECTION', label: '测绘巡检' },
  { value: 'RESCUE', label: '应急救援' },
];
const missionLabel = (v) => MISSION_TYPES.find((m) => m.value === v)?.label || v;

const ASSET_TYPE_ICON = { VEHICLE: '🚗', BATTERY: '🔋', CHARGER: '🔌', DRONE: '🚁' };

// 默认可承接任务的资产类型（车 / 电车）：能力 → 资产类型无命中时兜底，也用于「附近车辆」筛选。
const DEFAULT_ASSET_TYPES = ['VEHICLE', 'EV'];
// 能力 → 可承接该能力的资产类型。DRONE_OP 只能由 DRONE 资产承接，其余能力走车 / 电车。
const CAPABILITY_ASSET_TYPES = {
  DRONE_OP: ['DRONE'],
  AD_DISPLAY: ['VEHICLE', 'EV'],
  RIDE_HAIL: ['VEHICLE', 'EV'],
  TAXI: ['VEHICLE', 'EV'],
  LOGISTICS: ['VEHICLE', 'EV'],
};
// 任务状态色。状态文案统一走 i18n（task:status.*），此处只保留颜色映射。
const TASK_STATUS_COLOR = { PENDING: 'default', OPEN: 'blue', ASSIGNED: 'gold', IN_PROGRESS: 'processing', COMPLETED: 'green', SETTLED: 'cyan', CANCELLED: 'red' };
// 枚举标签只保留「值 → i18n key 后缀」映射，展示时统一 t('task:' + key)。
// 后端枚举值（BODY / SCREEN / HAIL / TAXI / PER_KM …）不参与翻译，避免改动请求 body。
const SCREEN_TYPE_LABEL = { BODY: 'ad.body', SCREEN: 'ad.screen' };
const RIDE_TYPE_LABEL = { HAIL: 'ride.hail', TAXI: 'ride.taxi' };
const FARE_MODEL_LABEL = { PER_KM: 'ride.perKm', PER_TIME: 'ride.perTime', FLAT: 'ride.flat' };

/**
 * 物流货物类型：value 为后端存储的中文枚举值（请求 body 原样上报，不可翻译），
 * labelKey 指向 task:logi.* 的三语标签，用于下拉选项与列表单元格展示。
 */
const CARGO_OPTIONS = [
  { value: '小件包裹', labelKey: 'logi.cargoParcel' },
  { value: '生鲜', labelKey: 'logi.cargoFresh' },
  { value: '大件', labelKey: 'logi.cargoBulky' },
];

/**
 * 货物类型展示：命中枚举取三语标签，未命中回落原始值，空值显示占位符。
 * @param {Function} t i18next 的 t 函数
 * @param {string} [v] 后端返回的货物类型原值
 * @returns {string} 展示文案
 */
const cargoLabel = (t, v) => {
  if (!v) return '—';
  const hit = CARGO_OPTIONS.find((o) => o.value === v);
  return hit ? t(`task:${hit.labelKey}`) : v;
};

// 任务发布：按子菜单 mode 渲染单个功能（无人机/物流/广告/录像/出租/附近车辆）。
// 原 TaskPublish 的 Tabs 入口已拆为 6 个独立子页，本组件为共享实现，真实接口逻辑全部保留。
export default function TaskPublish({ mode }) {
  const { t } = useTranslation(['common', 'drone']);
  const { pilotOptions } = useDroneOptions();
  // 真实数据
  const [assets, setAssets] = useState([]);
  const [assetsLoading, setAssetsLoading] = useState(false);
  const [assetsError, setAssetsError] = useState(null);

  // 附近车辆筛选（按真实 assetType）
  const [nearFilter, setNearFilter] = useState('__all');

  useEffect(() => {
    let alive = true;
    setAssetsLoading(true);
    setAssetsError(null);
    api.get('/v1/assets').then((a) => { if (alive) setAssets(a || []); })
      .catch((e) => { if (alive) { const m = '加载真实资产失败：' + e.message; message.error(m); setAssets([]); setAssetsError(m); } })
      .finally(() => { if (alive) setAssetsLoading(false); });
    return () => { alive = false; };
  }, []);

  // 附近可服务车辆：客运 / 打的场景仅参考 VEHICLE / EV 资产。
  const nearAssets = useMemo(
    () => assets.filter((a) => DEFAULT_ASSET_TYPES.includes(a.assetType)),
    [assets]
  );
  const nearTypes = useMemo(
    () => Array.from(new Set(nearAssets.map((a) => a.assetType))).filter(Boolean),
    [nearAssets]
  );
  const nearList = useMemo(
    () => (nearFilter === '__all' ? nearAssets : nearAssets.filter((a) => a.assetType === nearFilter)),
    [nearAssets, nearFilter]
  );

  // 各功能模块的实现（保留真实接口逻辑；children 为等效于原 Tab 面板的内容）
  const TAB_MAP = {
    logi: {
      label: '发布物流任务',
      children: <LogiPanel assets={assets} />,
    },
    near: {
      label: '查看附近车辆',
      children: (
        <Card>
          <Space style={{ marginBottom: 12 }}>
            <Text>筛选：</Text>
            <Segmented value={nearFilter} onChange={setNearFilter}
              options={[{ label: '全部', value: '__all' }, ...nearTypes.map((x) => ({ label: x, value: x }))]} />
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
          <div style={{ marginTop: 16 }}>
            <RidePanel assets={assets} />
          </div>
        </Card>
      ),
    },
    ad: {
      label: '发布广告自媒体',
      children: <AdPanel assets={assets} />,
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
      children: <DronePanel assets={assets} pilotOptions={pilotOptions} />,
    },
  };

  const tab = TAB_MAP[mode] || TAB_MAP.drone;

  return (
    <PageCard title={`任务发布 · ${tab.label}`}>
      <Alert type="info" showIcon style={{ marginBottom: 14 }}
        message="各功能模块均设「任务大厅」：需求方发布任务，附近车辆 / 设备按能力标签 + 地理位置匹配并自主接单。涵盖物流、客运（公交 / 打的 / 顺风车，货运归入物流）、广告自媒体、录像数据、资产出租、无人机低空作业。" />
      {assetsError && (
        <Alert type="error" showIcon closable style={{ marginBottom: 12 }}
          message={assetsError} onClose={() => setAssetsError(null)} />
      )}
      {tab.children}
    </PageCard>
  );
}

// 物流配送闭环面板：发布方 / 接单方 双视图，覆盖 发布 → 可接单 → 接单 → 进度 → 完成 → 收益 完整链路。
function LogiPanel({ assets }) {
  const { t } = useTranslation(['task']);
  const [view, setView] = useState('publisher');
  const [pubForm] = Form.useForm();
  const [pubSubmitting, setPubSubmitting] = useState(false);
  const [pubTasks, setPubTasks] = useState([]);
  const [pubLoading, setPubLoading] = useState(false);
  const [provTasks, setProvTasks] = useState([]);
  const [provLoading, setProvLoading] = useState(false);
  const [myAccepted, setMyAccepted] = useState([]);
  const [myLoading, setMyLoading] = useState(false);
  const [acceptSel, setAcceptSel] = useState({});
  const [progressInp, setProgressInp] = useState({});
  const [earnings, setEarnings] = useState({});

  // 候选接单资产：仅 VEHICLE / EV；若资产已声明 capabilities 则进一步过滤含 LOGISTICS 者。
  const candidateAssets = useMemo(
    () => (assets || []).filter((a) => {
      if (!['VEHICLE', 'EV'].includes(a.assetType)) return false;
      if (Array.isArray(a.capabilities)) return a.capabilities.includes('LOGISTICS');
      return true;
    }),
    [assets]
  );

  const loadPublished = useCallback(
    () => {
      setPubLoading(true);
      return api.get('/v1/tasks?role=publisher')
        .then((d) => setPubTasks(Array.isArray(d) ? d : (d && d.list) || []))
        .catch((e) => { message.error(t('task:hall.loadFailed', { message: e.message })); setPubTasks([]); })
        .finally(() => setPubLoading(false));
    },
    []
  );

  const loadAvailable = useCallback(
    () => {
      setProvLoading(true);
      return api.get('/v1/tasks?role=provider')
        .then((d) => setProvTasks(Array.isArray(d) ? d : (d && d.list) || []))
        .catch((e) => { message.error(t('task:hall.loadFailed', { message: e.message })); setProvTasks([]); })
        .finally(() => setProvLoading(false));
    },
    []
  );

  // /v1/me/my-tasks 返回 { published:[...], accepted:[...] }；accepted 为接单（AssignmentView）列表。
  const loadMy = useCallback(
    () => {
      setMyLoading(true);
      return api.get('/v1/me/my-tasks')
        .then((d) => {
          const acc = (d && d.accepted) || [];
          setMyAccepted(acc.map((a, i) => ({
            key: a.id ?? a.assignmentId ?? `acc-${i}`,
            assignmentId: a.id ?? a.assignmentId,
            taskId: a.taskId ?? (a.task && a.task.id),
            taskTitle: a.taskTitle ?? a.title ?? (a.task && a.task.title) ?? `(任务#${a.taskId ?? (a.task && a.task.id)})`,
            status: a.status,
            progressPct: a.progressPct ?? 0,
            assetId: a.assetId,
          })));
        })
        .catch((e) => { message.error(t('task:hall.loadFailed', { message: e.message })); setMyAccepted([]); })
        .finally(() => setMyLoading(false));
    },
    []
  );

  useEffect(() => {
    loadPublished();
    loadAvailable();
    loadMy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 发布物流任务 → POST /v1/tasks
  const onPublish = async () => {
    let v;
    try { v = await pubForm.validateFields(); } catch { return; }
    setPubSubmitting(true);
    try {
      await api.post('/v1/tasks', {
        taskType: 'LOGISTICS',
        title: v.title,
        description: v.description || '',
        rewardAmount: Number(v.rewardAmount),
        currency: 'USD',
        capabilityRequired: 'LOGISTICS',
        geoLat: null,
        geoLng: null,
        serviceRadiusM: null,
        pickupAddr: v.pickupAddr,
        dropoffAddr: v.dropoffAddr,
        cargoType: v.cargoType,
        weightKg: Number(v.weightKg),
      });
      message.success(t('task:hall.publishSuccess'));
      pubForm.resetFields();
      loadPublished();
    } catch (e) {
      message.error(t('task:hall.actionFailed', { message: e.message }));
    } finally {
      setPubSubmitting(false);
    }
  };

  // 接单 → POST /v1/tasks/{id}/accept { assetId }
  const onAccept = async (task) => {
    const assetId = acceptSel[task.id];
    if (!assetId) { message.warning(t('task:hall.needAsset')); return; }
    try {
      await api.post(`/v1/tasks/${task.id}/accept`, { assetId: Number(assetId) });
      message.success(t('task:hall.acceptSuccess') + ' #' + assetId);
      setAcceptSel((p) => ({ ...p, [task.id]: undefined }));
      loadAvailable();
      loadMy();
    } catch (e) {
      message.error(t('task:hall.actionFailed', { message: e.message }));
    }
  };

  // 更新进度 → POST /v1/tasks/{id}/progress { progressPct, note }
  const onProgress = async (item) => {
    const inp = progressInp[item.key] || {};
    const pct = inp.progressPct;
    if (pct == null || pct < 0 || pct > 100) { message.warning(t('task:hall.progressRange')); return; }
    try {
      await api.post(`/v1/tasks/${item.taskId}/progress`, { progressPct: Number(pct), note: inp.note || '' });
      message.success(t('task:hall.progressSuccess'));
      loadMy();
    } catch (e) {
      message.error(t('task:hall.actionFailed', { message: e.message }));
    }
  };

  // 完成任务 → POST /v1/tasks/{id}/complete，随后拉取收益明细。
  const onComplete = async (item) => {
    try {
      await api.post(`/v1/tasks/${item.taskId}/complete`, {});
      message.success(t('task:hall.completeSuccess'));
      loadMy();
      const aid = item.assetId;
      if (aid != null) {
        try {
          // 后端唯一端点：GET /api/v1/tasks/assets/{assetId}/task-earnings（挂在 TaskController 下）。
          const earns = await api.get(`/v1/tasks/assets/${aid}/task-earnings`);
          setEarnings((p) => ({ ...p, [item.key]: Array.isArray(earns) ? earns : [] }));
        } catch (e) {
          message.warning(t('task:earn.loadFailed') + '：' + e.message);
        }
      }
    } catch (e) {
      message.error(t('task:hall.actionFailed', { message: e.message }));
    }
  };

  const publisherView = (
    <>
      <Card style={{ marginBottom: 14 }}>
        <Form layout="vertical" form={pubForm} onFinish={onPublish}>
          <Form.Item label={t('task:hall.title_')} name="title"
            rules={[{ required: true, message: t('task:hall.titlePlaceholder') }]}>
            <Input placeholder={t('task:hall.titlePlaceholder')} />
          </Form.Item>
          <Space size="large" wrap align="end">
            <Form.Item label={t('task:logi.pickup')} name="pickupAddr"
              rules={[{ required: true, message: t('task:logi.pickupPlaceholder') }]} style={{ minWidth: 200 }}>
              <Input placeholder={t('task:logi.pickupPlaceholder')} />
            </Form.Item>
            <Form.Item label={t('task:logi.dropoff')} name="dropoffAddr"
              rules={[{ required: true, message: t('task:logi.dropoffPlaceholder') }]} style={{ minWidth: 200 }}>
              <Input placeholder={t('task:logi.dropoffPlaceholder')} />
            </Form.Item>
            <Form.Item label={t('task:logi.cargoType')} name="cargoType"
              rules={[{ required: true, message: t('task:logi.cargoTypePlaceholder') }]}>
              <Select placeholder={t('task:logi.cargoTypePlaceholder')}
                options={CARGO_OPTIONS.map((o) => ({ label: t(`task:${o.labelKey}`), value: o.value }))} />
            </Form.Item>
            <Form.Item label={`${t('task:logi.weight')} (${t('task:logi.weightUnit')})`} name="weightKg"
              rules={[{ required: true, message: t('task:logi.weight') }]}>
              <InputNumber min={0} step={0.1} placeholder="12.5" />
            </Form.Item>
            <Form.Item label={`${t('task:hall.reward')} ($)`} name="rewardAmount"
              rules={[{ required: true, message: t('task:hall.reward') }]}>
              <InputNumber min={1} step={0.01} placeholder="5.00" />
            </Form.Item>
            <Form.Item label={t('task:logi.deadline')}>
              <Input placeholder={t('task:logi.deadlinePlaceholder')} disabled />
            </Form.Item>
          </Space>
          <Form.Item label={t('task:hall.description')} name="description" style={{ marginTop: 4 }}>
            <Input.TextArea rows={2} placeholder={t('task:hall.descPlaceholder')} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={pubSubmitting}>{t('task:hall.publish')}</Button>
        </Form>
      </Card>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.published')}</h4>
      {pubLoading ? <Spin /> : pubTasks.length === 0 ? (
        <Empty description={t('task:hall.emptyPublished')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={pubTasks} size="small"
          columns={[
            { title: t('task:hall.title_'), dataIndex: 'title', render: (v) => v || '—' },
            { title: t('task:hall.reward'), dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: t('task:hall.status'), dataIndex: 'status', render: (s) => <TaskStatusTag status={s} /> },
            { title: t('task:logi.route'), key: 'route', render: (_, r) => <span>{r.pickupAddr || '—'} → {r.dropoffAddr || '—'}</span> },
            { title: t('task:logi.cargo'), dataIndex: 'cargoType', render: (v) => cargoLabel(t, v) },
            { title: t('task:logi.weight'), dataIndex: 'weightKg', render: (v) => (v != null ? `${v} ${t('task:logi.weightUnit')}` : '—') },
          ]} />
      )}
    </>
  );

  const providerView = (
    <>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.available')}</h4>
      {provLoading ? <Spin /> : provTasks.length === 0 ? (
        <Empty description={t('task:hall.emptyAvailable')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={provTasks} size="small"
          columns={[
            { title: t('task:hall.title_'), dataIndex: 'title', render: (v) => v || '—' },
            { title: t('task:hall.reward'), dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: t('task:logi.route'), key: 'route', render: (_, r) => <span>{r.pickupAddr || '—'} → {r.dropoffAddr || '—'}</span> },
            { title: t('task:logi.cargo'), dataIndex: 'cargoType', render: (v) => cargoLabel(t, v) },
            { title: t('task:logi.weight'), dataIndex: 'weightKg', render: (v) => (v != null ? `${v} ${t('task:logi.weightUnit')}` : '—') },
            {
              title: t('task:hall.accept'),
              key: 'act',
              render: (_, task) => (
                <Space>
                  <Select
                    placeholder={candidateAssets.length ? t('task:hall.selectAsset') : t('task:hall.noAsset')}
                    style={{ width: 200 }}
                    value={acceptSel[task.id]}
                    onChange={(val) => setAcceptSel((p) => ({ ...p, [task.id]: val }))}
                    options={candidateAssets.map((a) => ({ label: `${a.assetNo || a.assetType} · #${a.id}`, value: a.id }))}
                    disabled={candidateAssets.length === 0}
                  />
                  <Button type="primary" size="small" disabled={candidateAssets.length === 0} onClick={() => onAccept(task)}>
                    {t('task:hall.accept')}
                  </Button>
                </Space>
              ),
            },
          ]} />
      )}

      <Divider />
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.myAccepted')}</h4>
      {myLoading ? <Spin /> : myAccepted.length === 0 ? (
        <Empty description={t('task:hall.emptyAccepted')} />
      ) : (
        <List
          itemLayout="vertical"
          dataSource={myAccepted}
          renderItem={(item) => {
            const earns = earnings[item.key];
            const inp = progressInp[item.key] || {};
            return (
              <List.Item key={item.key}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontWeight: 600 }}>{item.taskTitle}</span>
                  <TaskStatusTag status={item.status} />
                </div>
                <div style={{ margin: '6px 0' }}>
                  <Progress percent={Number(item.progressPct) || 0} size="small" />
                </div>
                <Space wrap align="end">
                  <InputNumber min={0} max={100} placeholder={t('task:hall.progressHint')} value={inp.progressPct}
                    onChange={(val) => setProgressInp((p) => ({ ...p, [item.key]: { ...inp, progressPct: val } }))} />
                  <Input placeholder={t('task:hall.note')} style={{ width: 180 }} value={inp.note}
                    onChange={(e) => setProgressInp((p) => ({ ...p, [item.key]: { ...inp, note: e.target.value } }))} />
                  <Button size="small" onClick={() => onProgress(item)}>{t('task:hall.updateProgress')}</Button>
                  <Button size="small" type="primary" disabled={!canCompleteTask(item.status)} onClick={() => onComplete(item)}>
                    {t('task:hall.complete')}
                  </Button>
                </Space>
                <EarningsBlock earnings={earns} assetId={item.assetId} />
              </List.Item>
            );
          }}
        />
      )}
    </>
  );

  return (
    <>
      <HallViewSwitch value={view} onChange={setView} />
      {view === 'publisher' ? publisherView : providerView}
    </>
  );
}

// ---------- P2：任务大厅通用闭环（发布 → 可接单 → 接单 → 进度 → 完成 → 收益） ----------

/** 任务状态标签（归一化大小写后按 task:status.* 取三语文案，缺失时回落原始状态值）。 */
function TaskStatusTag({ status }) {
  const { t } = useTranslation(['task']);
  const s = status ? String(status).toUpperCase() : status;
  const label = t(`task:status.${s}`, { defaultValue: '' });
  return <Tag color={TASK_STATUS_COLOR[s] || 'default'}>{label || status || '—'}</Tag>;
}

/** 任务类型标签（task:type.*）。 */
function TaskTypeTag({ taskType }) {
  const { t } = useTranslation(['task']);
  const v = taskType ? String(taskType).toUpperCase() : taskType;
  const label = t(`task:type.${v}`, { defaultValue: '' });
  return <Tag color={v === 'DRONE_OP' ? 'purple' : 'geekblue'}>{label || taskType || '—'}</Tag>;
}

/** 发布方 / 接单方 双视图切换器（三语，task:hall.*）。 */
function HallViewSwitch({ value, onChange }) {
  const { t } = useTranslation(['task']);
  return (
    <Segmented
      value={value}
      onChange={onChange}
      style={{ marginBottom: 14 }}
      options={[
        { label: t('task:hall.publisher'), value: 'publisher' },
        { label: t('task:hall.provider'), value: 'provider' },
      ]}
    />
  );
}

/** 仅 ASSIGNED / IN_PROGRESS 允许「完成」。 */
const canCompleteTask = (status) => ['ASSIGNED', 'IN_PROGRESS'].includes(status ? String(status).toUpperCase() : status);

// 收益明细区块统一由 components/AssetTaskEarnings.jsx 的 <EarningsBlock /> 提供
// （任务大厅「完成」后回填 + 资产详情「任务收益」Tab 共用同一份渲染逻辑）。

/**
 * 归一化资产的 capabilities 字段。
 *
 * 后端 /v1/assets 目前不返回该字段（undefined）；将来可能返回数组，也可能返回 CSV 字符串，
 * 三种形态统一归一为字符串数组，避免筛选逻辑散落 if-else。
 *
 * @param {Array<string>|string|null|undefined} raw 原始 capabilities
 * @returns {string[]} 归一化后的能力列表（无信息时为空数组）
 */
function normalizeCapabilities(raw) {
  if (Array.isArray(raw)) return raw.map((x) => String(x).trim()).filter(Boolean);
  if (typeof raw === 'string') return raw.split(',').map((x) => x.trim()).filter(Boolean);
  return [];
}

/**
 * 由能力列表推导可承接的资产类型（各能力映射结果取并集）。
 * 无命中时兜底为车 / 电车，保证未登记新能力的老逻辑不退化。
 *
 * @param {string[]} capabilities 任务所需能力
 * @returns {string[]} 允许的 assetType 列表
 */
function assetTypesFor(capabilities) {
  const set = new Set();
  (capabilities || []).forEach((c) => {
    (CAPABILITY_ASSET_TYPES[c] || []).forEach((x) => set.add(x));
  });
  const merged = Array.from(set);
  return merged.length > 0 ? merged : DEFAULT_ASSET_TYPES;
}

/**
 * 接单方通用筛选：按能力推导允许的 assetType；若资产已声明 capabilities 再按能力命中过滤。
 * 资产未携带任何能力信息（后端当前行为）时放行，避免接单下拉恒为空。
 *
 * @param {Array} assets 资产列表
 * @param {string[]} capabilities 任务所需能力（如 ['AD_DISPLAY'] 或 ['DRONE_OP']）
 * @param {string[]} [assetTypes] 显式指定允许的资产类型；省略时由 capabilities 推导
 * @returns {Array} 候选资产
 */
function filterCandidateAssets(assets, capabilities, assetTypes) {
  const types = (Array.isArray(assetTypes) && assetTypes.length > 0)
    ? assetTypes
    : assetTypesFor(capabilities);
  const required = capabilities || [];
  return (assets || []).filter((a) => {
    if (!types.includes(a.assetType)) return false;
    const owned = normalizeCapabilities(a.capabilities);
    if (owned.length === 0) return true;
    return required.some((c) => owned.includes(c));
  });
}

/**
 * 任务闭环通用逻辑 Hook：复用 logi 面板的请求 / 刷新 / 接单 / 进度 / 完成 / 收益 全链路。
 * @param {{assets: Array, capabilities: string[], taskTypes: string[], assetTypes?: string[]}} cfg
 */
function useTaskLoop({ assets, capabilities, taskTypes, assetTypes }) {
  const { t } = useTranslation(['task']);
  const [pubForm] = Form.useForm();
  const [pubSubmitting, setPubSubmitting] = useState(false);
  const [pubTasks, setPubTasks] = useState([]);
  const [pubLoading, setPubLoading] = useState(false);
  const [provTasks, setProvTasks] = useState([]);
  const [provLoading, setProvLoading] = useState(false);
  const [myAccepted, setMyAccepted] = useState([]);
  const [myLoading, setMyLoading] = useState(false);
  const [acceptSel, setAcceptSel] = useState({});
  const [progressInp, setProgressInp] = useState({});
  const [earnings, setEarnings] = useState({});

  // taskTypes 为字面量数组，按内容做 key 稳定 inScope 引用。
  const typesKey = taskTypes.join(',');
  const inScope = useCallback((taskType) => typesKey.split(',').includes(taskType), [typesKey]);

  // capabilities / assetTypes 同为字面量数组（assetTypes 可省略 → 由 capabilities 推导），
  // 一律按内容做 key，避免每次渲染重建候选资产列表导致 Select 抖动。
  const capsKey = (capabilities || []).join(',');
  const assetTypesKey = Array.isArray(assetTypes) ? assetTypes.join(',') : '';
  const candidateAssets = useMemo(
    () => filterCandidateAssets(
      assets,
      capsKey ? capsKey.split(',') : [],
      assetTypesKey ? assetTypesKey.split(',') : undefined
    ),
    [assets, capsKey, assetTypesKey]
  );

  const loadPublished = useCallback(
    () => {
      setPubLoading(true);
      return api.get('/v1/tasks?role=publisher')
        .then((d) => {
          const list = Array.isArray(d) ? d : (d && d.list) || [];
          setPubTasks(list.filter((x) => inScope(x && x.taskType)));
        })
        .catch((e) => { message.error(t('task:hall.loadFailed', { message: e.message })); setPubTasks([]); })
        .finally(() => setPubLoading(false));
    },
    [inScope]
  );

  const loadAvailable = useCallback(
    () => {
      setProvLoading(true);
      return api.get('/v1/tasks?role=provider')
        .then((d) => {
          const list = Array.isArray(d) ? d : (d && d.list) || [];
          setProvTasks(list.filter((x) => inScope(x && x.taskType)));
        })
        .catch((e) => { message.error(t('task:hall.loadFailed', { message: e.message })); setProvTasks([]); })
        .finally(() => setProvLoading(false));
    },
    [inScope]
  );

  // /v1/me/my-tasks 返回 { published:[...], accepted:[...] }；accepted 为接单（AssignmentView）列表。
  const loadMy = useCallback(
    () => {
      setMyLoading(true);
      return api.get('/v1/me/my-tasks')
        .then((d) => {
          const acc = (d && d.accepted) || [];
          setMyAccepted(acc
            // AssignmentView 不含 taskType：缺失时不过滤（与 logi 面板行为一致）。
            .filter((a) => {
              const tt = a.taskType ?? (a.task && a.task.taskType);
              return tt == null || inScope(tt);
            })
            .map((a, i) => ({
              key: a.id ?? a.assignmentId ?? `acc-${i}`,
              assignmentId: a.id ?? a.assignmentId,
              taskId: a.taskId ?? (a.task && a.task.id),
              taskTitle: a.taskTitle ?? a.title ?? (a.task && a.task.title) ?? `(任务#${a.taskId ?? (a.task && a.task.id)})`,
              status: a.status,
              progressPct: a.progressPct ?? 0,
              assetId: a.assetId,
            })));
        })
        .catch((e) => { message.error(t('task:hall.loadFailed', { message: e.message })); setMyAccepted([]); })
        .finally(() => setMyLoading(false));
    },
    [inScope]
  );

  useEffect(() => {
    loadPublished();
    loadAvailable();
    loadMy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 发布任务 → POST /v1/tasks */
  const publish = async (payload, successMsg) => {
    setPubSubmitting(true);
    try {
      await api.post('/v1/tasks', payload);
      message.success(successMsg);
      pubForm.resetFields();
      loadPublished();
    } catch (e) {
      message.error(t('task:hall.actionFailed', { message: e.message }));
    } finally {
      setPubSubmitting(false);
    }
  };

  /** 接单 → POST /v1/tasks/{id}/accept { assetId } */
  const onAccept = async (task) => {
    const assetId = acceptSel[task.id];
    if (!assetId) { message.warning(t('task:hall.needAsset')); return; }
    try {
      await api.post(`/v1/tasks/${task.id}/accept`, { assetId: Number(assetId) });
      message.success(t('task:hall.acceptSuccess') + ' #' + assetId);
      setAcceptSel((p) => ({ ...p, [task.id]: undefined }));
      loadAvailable();
      loadMy();
    } catch (e) {
      message.error(t('task:hall.actionFailed', { message: e.message }));
    }
  };

  /** 更新进度 → POST /v1/tasks/{id}/progress { progressPct, note } */
  const onProgress = async (item) => {
    const inp = progressInp[item.key] || {};
    const pct = inp.progressPct;
    if (pct == null || pct < 0 || pct > 100) { message.warning(t('task:hall.progressRange')); return; }
    try {
      await api.post(`/v1/tasks/${item.taskId}/progress`, { progressPct: Number(pct), note: inp.note || '' });
      message.success(t('task:hall.progressSuccess'));
      loadMy();
    } catch (e) {
      message.error(t('task:hall.actionFailed', { message: e.message }));
    }
  };

  /** 完成任务 → POST /v1/tasks/{id}/complete，随后拉取该接单资产的收益明细。 */
  const onComplete = async (item) => {
    try {
      await api.post(`/v1/tasks/${item.taskId}/complete`, {});
      message.success(t('task:hall.completeSuccess'));
      loadMy();
      const aid = item.assetId;
      if (aid != null) {
        try {
          // 后端唯一端点：GET /api/v1/tasks/assets/{assetId}/task-earnings（挂在 TaskController 下）。
          const earns = await api.get(`/v1/tasks/assets/${aid}/task-earnings`);
          setEarnings((p) => ({ ...p, [item.key]: Array.isArray(earns) ? earns : [] }));
        } catch (e) {
          message.warning(t('task:earn.loadFailed') + '：' + e.message);
        }
      }
    } catch (e) {
      message.error(t('task:hall.actionFailed', { message: e.message }));
    }
  };

  return {
    pubForm, pubSubmitting, pubTasks, pubLoading,
    provTasks, provLoading, myAccepted, myLoading,
    acceptSel, setAcceptSel, progressInp, setProgressInp, earnings,
    candidateAssets, loadPublished, loadAvailable, loadMy,
    publish, onAccept, onProgress, onComplete,
  };
}

/** 接单方「可接单」行的资产选择 + 接单按钮。 */
function AcceptCell({ task, loop }) {
  const { t } = useTranslation(['task']);
  const { acceptSel, setAcceptSel, candidateAssets, onAccept } = loop;
  return (
    <Space>
      <Select
        placeholder={candidateAssets.length ? t('task:hall.selectAsset') : t('task:hall.noAsset')}
        style={{ width: 200 }}
        value={acceptSel[task.id]}
        onChange={(val) => setAcceptSel((p) => ({ ...p, [task.id]: val }))}
        options={candidateAssets.map((a) => ({ label: `${a.assetNo || a.assetType} · #${a.id}`, value: a.id }))}
        disabled={candidateAssets.length === 0}
      />
      <Button type="primary" size="small" disabled={candidateAssets.length === 0} onClick={() => onAccept(task)}>
        {t('task:hall.accept')}
      </Button>
    </Space>
  );
}

/** 接单方「我的接单」列表：进度条 + 进度上报 + 完成 + 收益。 */
function MyAcceptedList({ loop }) {
  const { t } = useTranslation(['task']);
  const { myAccepted, myLoading, progressInp, setProgressInp, earnings, onProgress, onComplete } = loop;
  if (myLoading) return <Spin />;
  if (myAccepted.length === 0) return <Empty description={t('task:hall.emptyAccepted')} />;
  return (
    <List
      itemLayout="vertical"
      dataSource={myAccepted}
      renderItem={(item) => {
        const earns = earnings[item.key];
        const inp = progressInp[item.key] || {};
        return (
          <List.Item key={item.key}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontWeight: 600 }}>{item.taskTitle}</span>
              <TaskStatusTag status={item.status} />
            </div>
            <div style={{ margin: '6px 0' }}>
              <Progress percent={Number(item.progressPct) || 0} size="small" />
            </div>
            <Space wrap align="end">
              <InputNumber min={0} max={100} placeholder={t('task:hall.progressHint')} value={inp.progressPct}
                onChange={(val) => setProgressInp((p) => ({ ...p, [item.key]: { ...inp, progressPct: val } }))} />
              <Input placeholder={t('task:hall.note')} style={{ width: 180 }} value={inp.note}
                onChange={(e) => setProgressInp((p) => ({ ...p, [item.key]: { ...inp, note: e.target.value } }))} />
              <Button size="small" onClick={() => onProgress(item)}>{t('task:hall.updateProgress')}</Button>
              <Button size="small" type="primary" disabled={!canCompleteTask(item.status)} onClick={() => onComplete(item)}>
                {t('task:hall.complete')}
              </Button>
            </Space>
            <EarningsBlock earnings={earns} assetId={item.assetId} />
          </List.Item>
        );
      }}
    />
  );
}

// 广告自媒体闭环面板（taskType=AD / capability=AD_DISPLAY）：发布方 / 接单方 双视图。
function AdPanel({ assets }) {
  const { t } = useTranslation(['task']);
  const [view, setView] = useState('publisher');
  const loop = useTaskLoop({ assets, capabilities: ['AD_DISPLAY'], taskTypes: ['AD'] });
  const { pubForm, pubSubmitting, pubTasks, pubLoading, provTasks, provLoading } = loop;

  // 发布广告任务 → POST /v1/tasks（AD 报文）
  const onPublish = async () => {
    let v;
    try { v = await pubForm.validateFields(); } catch { return; }
    await loop.publish({
      taskType: 'AD',
      title: v.title,
      description: v.description || '',
      rewardAmount: Number(v.rewardAmount),
      currency: 'USD',
      capabilityRequired: 'AD_DISPLAY',
      advertiser: v.advertiser,
      mediaUrl: v.mediaUrl || '',
      displayDuration: v.displayDuration || '',
      screenType: v.screenType || 'BODY',
    }, t('task:hall.publishSuccess'));
  };

  const publisherView = (
    <>
      <Card style={{ marginBottom: 14 }}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('task:ad.hint')} />
        <Form layout="vertical" form={pubForm} onFinish={onPublish}>
          <Form.Item label={t('task:hall.title_')} name="title"
            rules={[{ required: true, message: t('task:hall.titlePlaceholder') }]}>
            <Input placeholder={t('task:hall.titlePlaceholder')} />
          </Form.Item>
          <Space size="large" wrap align="end">
            <Form.Item label={t('task:ad.advertiser')} name="advertiser"
              rules={[{ required: true, message: t('task:ad.advertiser') }]} style={{ minWidth: 200 }}>
              <Input placeholder={t('task:ad.advertiserPlaceholder')} />
            </Form.Item>
            <Form.Item label={t('task:ad.screenType')} name="screenType"
              rules={[{ required: true, message: t('task:ad.screenType') }]}>
              <Select placeholder={t('task:ad.screenTypePlaceholder')}
                options={Object.keys(SCREEN_TYPE_LABEL).map((k) => ({ label: t(`task:${SCREEN_TYPE_LABEL[k]}`), value: k }))} />
            </Form.Item>
            <Form.Item label={t('task:ad.duration')} name="displayDuration" style={{ minWidth: 160 }}>
              <Input placeholder={t('task:ad.durationPlaceholder')} />
            </Form.Item>
            <Form.Item label={`${t('task:hall.reward')} ($)`} name="rewardAmount"
              rules={[{ required: true, message: t('task:hall.reward') }]}>
              <InputNumber min={1} step={0.01} placeholder="20.00" />
            </Form.Item>
          </Space>
          <Form.Item label={t('task:ad.mediaUrl')} name="mediaUrl" style={{ marginTop: 4 }}>
            <Input placeholder={t('task:ad.mediaPlaceholder')} />
          </Form.Item>
          <Form.Item label={t('task:hall.description')} name="description">
            <Input.TextArea rows={2} placeholder={t('task:hall.descPlaceholder')} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={pubSubmitting}>{t('task:hall.publish')}</Button>
        </Form>
      </Card>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.published')}</h4>
      {pubLoading ? <Spin /> : pubTasks.length === 0 ? (
        <Empty description={t('task:hall.emptyPublished')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={pubTasks} size="small"
          columns={[
            { title: t('task:hall.title_'), dataIndex: 'title', render: (v) => v || '—' },
            { title: t('task:hall.reward'), dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: t('task:hall.status'), dataIndex: 'status', render: (s) => <TaskStatusTag status={s} /> },
            { title: t('task:ad.advertiser'), dataIndex: ['ad', 'advertiser'], render: (v, r) => v || (r.ad && r.ad.advertiser) || '—' },
            {
              title: t('task:ad.screenType'),
              dataIndex: ['ad', 'screenType'],
              render: (v, r) => {
                const s = v || (r.ad && r.ad.screenType);
                return SCREEN_TYPE_LABEL[s] ? t(`task:${SCREEN_TYPE_LABEL[s]}`) : (s || '—');
              },
            },
            { title: t('task:ad.duration'), dataIndex: ['ad', 'displayDuration'], render: (v, r) => v || (r.ad && r.ad.displayDuration) || '—' },
          ]} />
      )}
    </>
  );

  const providerView = (
    <>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.available')}</h4>
      {provLoading ? <Spin /> : provTasks.length === 0 ? (
        <Empty description={t('task:hall.emptyAvailable')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={provTasks} size="small"
          columns={[
            { title: t('task:hall.title_'), dataIndex: 'title', render: (v) => v || '—' },
            { title: t('task:hall.reward'), dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: t('task:ad.advertiser'), dataIndex: ['ad', 'advertiser'], render: (v, r) => v || (r.ad && r.ad.advertiser) || '—' },
            {
              title: t('task:ad.screenType'),
              dataIndex: ['ad', 'screenType'],
              render: (v, r) => {
                const s = v || (r.ad && r.ad.screenType);
                return SCREEN_TYPE_LABEL[s] ? t(`task:${SCREEN_TYPE_LABEL[s]}`) : (s || '—');
              },
            },
            { title: t('task:ad.mediaUrl'), dataIndex: ['ad', 'mediaUrl'], render: (v, r) => v || (r.ad && r.ad.mediaUrl) || '—' },
            { title: t('task:hall.accept'), key: 'act', render: (_, task) => <AcceptCell task={task} loop={loop} /> },
          ]} />
      )}

      <Divider />
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.myAccepted')}</h4>
      <MyAcceptedList loop={loop} />
    </>
  );

  return (
    <>
      <HallViewSwitch value={view} onChange={setView} />
      {view === 'publisher' ? publisherView : providerView}
    </>
  );
}

// 客运 / 打的闭环面板（HAIL_RIDE + TAXI）：发布方 / 接单方 双视图。
function RidePanel({ assets }) {
  const { t } = useTranslation(['task']);
  const [view, setView] = useState('publisher');
  const [rideKind, setRideKind] = useState('HAIL_RIDE');
  const loop = useTaskLoop({ assets, capabilities: ['RIDE_HAIL', 'TAXI'], taskTypes: ['HAIL_RIDE', 'TAXI'] });
  const { pubForm, pubSubmitting, pubTasks, pubLoading, provTasks, provLoading } = loop;

  // 招手即停 → HAIL_RIDE / RIDE_HAIL / HAIL；打的 → TAXI / TAXI / TAXI
  const taskType = rideKind;
  const capabilityRequired = rideKind === 'TAXI' ? 'TAXI' : 'RIDE_HAIL';
  const rideType = rideKind === 'TAXI' ? 'TAXI' : 'HAIL';

  const onPublish = async () => {
    let v;
    try { v = await pubForm.validateFields(); } catch { return; }
    await loop.publish({
      taskType,
      title: v.title,
      description: v.description || '',
      rewardAmount: Number(v.rewardAmount),
      currency: 'USD',
      capabilityRequired,
      originAddr: v.originAddr,
      destAddr: v.destAddr,
      rideType,
      estDistanceKm: Number(v.estDistanceKm),
      estDurationMin: Number(v.estDurationMin),
      fareModel: v.fareModel || 'PER_KM',
    }, t('task:hall.publishSuccess'));
  };

  const publisherView = (
    <>
      <Card style={{ marginBottom: 14 }}>
        <Space style={{ marginBottom: 12 }} align="center">
          <Text strong>{t('task:ride.kind')}：</Text>
          <Segmented value={rideKind} onChange={setRideKind}
            options={[
              { label: `${t('task:type.HAIL_RIDE')} (HAIL_RIDE)`, value: 'HAIL_RIDE' },
              { label: `${t('task:type.TAXI')} (TAXI)`, value: 'TAXI' },
            ]} />
        </Space>
        <Form layout="vertical" form={pubForm} onFinish={onPublish}>
          <Form.Item label={t('task:hall.title_')} name="title"
            rules={[{ required: true, message: t('task:hall.titlePlaceholder') }]}>
            <Input placeholder={t('task:hall.titlePlaceholder')} />
          </Form.Item>
          <Space size="large" wrap align="end">
            <Form.Item label={t('task:ride.origin')} name="originAddr"
              rules={[{ required: true, message: t('task:ride.origin') }]} style={{ minWidth: 200 }}>
              <Input placeholder={t('task:ride.originPlaceholder')} />
            </Form.Item>
            <Form.Item label={t('task:ride.dest')} name="destAddr"
              rules={[{ required: true, message: t('task:ride.dest') }]} style={{ minWidth: 200 }}>
              <Input placeholder={t('task:ride.destPlaceholder')} />
            </Form.Item>
            <Form.Item label={`${t('task:ride.estDistance')} (${t('task:ride.kmUnit')})`} name="estDistanceKm"
              rules={[{ required: true, message: t('task:ride.estDistance') }]}>
              <InputNumber min={0} step={0.1} placeholder="8.5" />
            </Form.Item>
            <Form.Item label={`${t('task:ride.estDuration')} (${t('task:ride.minUnit')})`} name="estDurationMin"
              rules={[{ required: true, message: t('task:ride.estDuration') }]}>
              <InputNumber min={0} step={1} placeholder="25" />
            </Form.Item>
            <Form.Item label={t('task:ride.fareModel')} name="fareModel"
              rules={[{ required: true, message: t('task:ride.fareModel') }]}>
              <Select placeholder={t('task:ride.fareModelPlaceholder')}
                options={Object.keys(FARE_MODEL_LABEL).map((k) => ({ label: t(`task:${FARE_MODEL_LABEL[k]}`), value: k }))} />
            </Form.Item>
            <Form.Item label={`${t('task:hall.reward')} ($)`} name="rewardAmount"
              rules={[{ required: true, message: t('task:hall.reward') }]}>
              <InputNumber min={1} step={0.01} placeholder="5.00" />
            </Form.Item>
          </Space>
          <Form.Item label={t('task:hall.description')} name="description" style={{ marginTop: 4 }}>
            <Input.TextArea rows={2} placeholder={t('task:hall.descPlaceholder')} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={pubSubmitting}>{t('task:hall.publish')}</Button>
        </Form>
      </Card>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.published')}</h4>
      {pubLoading ? <Spin /> : pubTasks.length === 0 ? (
        <Empty description={t('task:hall.emptyPublished')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={pubTasks} size="small"
          columns={[
            { title: t('task:hall.title_'), dataIndex: 'title', render: (v) => v || '—' },
            {
              title: t('task:ride.rideType'),
              dataIndex: 'taskType',
              render: (v) => {
                const key = RIDE_TYPE_LABEL[v === 'TAXI' ? 'TAXI' : 'HAIL'];
                return <Tag color={v === 'TAXI' ? 'volcano' : 'geekblue'}>{t(`task:${key}`)}</Tag>;
              },
            },
            { title: t('task:hall.reward'), dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: t('task:hall.status'), dataIndex: 'status', render: (s) => <TaskStatusTag status={s} /> },
            { title: t('task:ride.route'), key: 'route', render: (_, r) => <span>{(r.ride && r.ride.originAddr) || '—'} → {(r.ride && r.ride.destAddr) || '—'}</span> },
            {
              title: t('task:ride.estDistance'),
              dataIndex: ['ride', 'estDistanceKm'],
              render: (v, r) => {
                const d = v ?? (r.ride && r.ride.estDistanceKm);
                return d != null ? `${d} ${t('task:ride.kmUnit')}` : '—';
              },
            },
            {
              title: t('task:ride.fareModel'),
              dataIndex: ['ride', 'fareModel'],
              render: (v, r) => {
                const f = v || (r.ride && r.ride.fareModel);
                return FARE_MODEL_LABEL[f] ? t(`task:${FARE_MODEL_LABEL[f]}`) : (f || '—');
              },
            },
          ]} />
      )}
    </>
  );

  const providerView = (
    <>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.available')}</h4>
      {provLoading ? <Spin /> : provTasks.length === 0 ? (
        <Empty description={t('task:hall.emptyAvailable')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={provTasks} size="small"
          columns={[
            { title: t('task:hall.title_'), dataIndex: 'title', render: (v) => v || '—' },
            {
              title: t('task:ride.rideType'),
              dataIndex: ['ride', 'rideType'],
              render: (v, r) => {
                const rt = v || (r.ride && r.ride.rideType);
                return <Tag color={rt === 'TAXI' ? 'volcano' : 'geekblue'}>{RIDE_TYPE_LABEL[rt] ? t(`task:${RIDE_TYPE_LABEL[rt]}`) : (rt || '—')}</Tag>;
              },
            },
            { title: t('task:hall.reward'), dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: t('task:ride.route'), key: 'route', render: (_, r) => <span>{(r.ride && r.ride.originAddr) || '—'} → {(r.ride && r.ride.destAddr) || '—'}</span> },
            {
              title: t('task:ride.estDistance'),
              dataIndex: ['ride', 'estDistanceKm'],
              render: (v, r) => {
                const d = v ?? (r.ride && r.ride.estDistanceKm);
                return d != null ? `${d} ${t('task:ride.kmUnit')}` : '—';
              },
            },
            {
              title: t('task:ride.estDuration'),
              dataIndex: ['ride', 'estDurationMin'],
              render: (v, r) => {
                const d = v ?? (r.ride && r.ride.estDurationMin);
                return d != null ? `${d} ${t('task:ride.minUnit')}` : '—';
              },
            },
            {
              title: t('task:ride.fareModel'),
              dataIndex: ['ride', 'fareModel'],
              render: (v, r) => {
                const f = v || (r.ride && r.ride.fareModel);
                return FARE_MODEL_LABEL[f] ? t(`task:${FARE_MODEL_LABEL[f]}`) : (f || '—');
              },
            },
            { title: t('task:hall.accept'), key: 'act', render: (_, task) => <AcceptCell task={task} loop={loop} /> },
          ]} />
      )}

      <Divider />
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.myAccepted')}</h4>
      <MyAcceptedList loop={loop} />
    </>
  );

  return (
    <>
      <Divider orientation="left" style={{ marginTop: 4 }}>
        {t('task:type.HAIL_RIDE')} / {t('task:type.TAXI')} · {t('task:hall.title')}
      </Divider>
      <HallViewSwitch value={view} onChange={setView} />
      {view === 'publisher' ? publisherView : providerView}
    </>
  );
}

// ---------- P3：无人机低空作业接入任务大厅（taskType=DRONE_OP / capability=DRONE_OP） ----------

/** 取 TaskView.drone 明细（后端回传的作业标量 map），缺省返回空对象避免层层判空。 */
const droneOf = (r) => ((r && r.drone) || {});

/**
 * 无人机作业闭环面板：与 AdPanel / RidePanel 同构的发布方 / 接单方双视图。
 *
 * P3 起「发布」改为 POST /v1/tasks（taskType=DRONE_OP，capabilityRequired=DRONE_OP），
 * 由服务端创建关联的 drone_missions 行并回写 task.droneMissionId；TaskView 同时返回 drone 明细 map。
 * 因此不再直连 POST /v1/drone-missions，作业才具备接单 / 进度 / 完成 / 结算全链路。
 *
 * @param {{assets: Array, pilotOptions: Array}} props 资产列表与飞手下拉选项
 * @returns {JSX.Element} 面板
 */
function DronePanel({ assets, pilotOptions = [] }) {
  const { t } = useTranslation(['common', 'drone', 'task']);
  const [view, setView] = useState('publisher');
  const loop = useTaskLoop({ assets, capabilities: ['DRONE_OP'], taskTypes: ['DRONE_OP'] });
  const { pubForm, pubSubmitting, pubTasks, pubLoading, provTasks, provLoading } = loop;

  // 无人机作业只能绑定 DRONE 资产（前端先拦一层，后端亦按 capability 校验）。
  const droneAssets = useMemo(
    () => (assets || []).filter((a) => a.assetType === 'DRONE'),
    [assets]
  );
  const droneAssetOptions = useMemo(
    () => droneAssets.map((x) => ({ label: `${x.assetNo || 'DRONE'} · #${x.id}`, value: x.id })),
    [droneAssets]
  );

  // 发布无人机作业任务 → POST /v1/tasks（DRONE_OP 报文）
  const onPublish = async () => {
    let v;
    try { v = await pubForm.validateFields(); } catch { return; }
    await loop.publish({
      taskType: 'DRONE_OP',
      title: v.title,
      description: v.description || '',
      rewardAmount: Number(v.rewardAmount),
      currency: 'USD',
      capabilityRequired: 'DRONE_OP',
      missionType: v.missionType,
      payloadDesc: v.payloadDesc || '',
      areaHa: v.areaHa != null ? Number(v.areaHa) : null,
      trips: v.trips != null ? Number(v.trips) : null,
      flightMinutes: v.flightMinutes != null ? Number(v.flightMinutes) : null,
      pilotId: Number(v.pilotId),
      assetId: Number(v.assetId),
      executedAt: new Date().toISOString(),
    }, t('task:hall.publishSuccess'));
  };

  const publisherView = (
    <>
      <Card style={{ marginBottom: 14 }}>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={<span>{t('task:drone.hint')} <Link to="/drone-ops">{t('drone:common.gotoOps')}</Link></span>}
        />
        <Form layout="vertical" form={pubForm} onFinish={onPublish}>
          <Form.Item label={t('task:hall.title_')} name="title"
            rules={[{ required: true, message: t('task:hall.titlePlaceholder') }]}>
            <Input placeholder={t('task:hall.titlePlaceholder')} />
          </Form.Item>
          <Space size="large" wrap align="end">
            <Form.Item label={t('task:drone.missionType')} name="missionType"
              rules={[{ required: true, message: t('task:drone.missionTypePlaceholder') }]} style={{ minWidth: 160 }}>
              <Select options={MISSION_TYPES} placeholder={t('task:drone.missionTypePlaceholder')} />
            </Form.Item>
            <Form.Item label={t('task:drone.asset')} name="assetId"
              rules={[{ required: true, message: t('task:hall.selectAsset') }]} style={{ minWidth: 220 }}>
              <Select
                placeholder={droneAssetOptions.length ? t('task:hall.selectAsset') : t('task:hall.noAsset')}
                options={droneAssetOptions}
                disabled={droneAssetOptions.length === 0}
              />
            </Form.Item>
            <Form.Item label={t('task:drone.payloadDesc')} name="payloadDesc" style={{ minWidth: 180 }}>
              <Input placeholder={t('task:drone.payloadPlaceholder')} />
            </Form.Item>
            <Form.Item label={t('task:drone.areaHa')} name="areaHa">
              <InputNumber min={0} step={0.1} placeholder="18.5" />
            </Form.Item>
            <Form.Item label={t('task:drone.trips')} name="trips">
              <InputNumber min={0} step={1} placeholder="3" />
            </Form.Item>
            <Form.Item label={t('task:drone.flightMinutes')} name="flightMinutes">
              <InputNumber min={0} step={1} placeholder="96" />
            </Form.Item>
            <Form.Item label={t('task:drone.pilot')} name="pilotId"
              rules={[{ required: true, message: t('drone:mission.form.pilot') }]}>
              <Select showSearch optionFilterProp="label"
                placeholder={t('drone:common.selectPlaceholder')} options={pilotOptions} />
            </Form.Item>
            <Form.Item label={`${t('task:hall.reward')} ($)`} name="rewardAmount"
              rules={[{ required: true, message: t('task:hall.reward') }]}>
              <InputNumber min={1} step={0.01} placeholder="20.00" />
            </Form.Item>
          </Space>
          <Form.Item label={t('task:hall.description')} name="description" style={{ marginTop: 4 }}>
            <Input.TextArea rows={2} placeholder={t('task:hall.descPlaceholder')} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={pubSubmitting}>{t('task:hall.publish')}</Button>
        </Form>
      </Card>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:drone.publishedTitle')}</h4>
      {pubLoading ? <Spin /> : pubTasks.length === 0 ? (
        <Empty description={t('task:hall.emptyPublished')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={pubTasks} size="small"
          columns={[
            { title: t('task:hall.title_'), dataIndex: 'title', render: (v) => v || '—' },
            { title: t('task:hall.reward'), dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: t('task:hall.status'), dataIndex: 'status', render: (s) => <TaskStatusTag status={s} /> },
            {
              title: t('task:drone.missionType'),
              key: 'droneMissionType',
              render: (_, r) => <Tag color="purple">{missionLabel(droneOf(r).missionType) || '—'}</Tag>,
            },
            {
              title: t('task:drone.droneMissionId'),
              key: 'droneMissionId',
              render: (_, r) => {
                const id = r.droneMissionId != null ? r.droneMissionId : droneOf(r).id;
                return id != null ? `#${id}` : '—';
              },
            },
            { title: t('task:drone.areaHa'), key: 'droneAreaHa', render: (_, r) => droneOf(r).areaHa ?? '—' },
            { title: t('task:drone.flightMinutes'), key: 'droneFlightMinutes', render: (_, r) => droneOf(r).flightMinutes ?? '—' },
          ]} />
      )}
    </>
  );

  const providerView = (
    <>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:drone.availableTitle')}</h4>
      {provLoading ? <Spin /> : provTasks.length === 0 ? (
        <Empty description={t('task:hall.emptyAvailable')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={provTasks} size="small"
          columns={[
            { title: t('task:hall.title_'), dataIndex: 'title', render: (v) => v || '—' },
            { title: t('task:hall.reward'), dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            {
              title: t('task:drone.missionType'),
              key: 'droneMissionType',
              render: (_, r) => <Tag color="purple">{missionLabel(droneOf(r).missionType) || '—'}</Tag>,
            },
            { title: t('task:drone.payloadDesc'), key: 'dronePayloadDesc', render: (_, r) => droneOf(r).payloadDesc || '—' },
            { title: t('task:hall.accept'), key: 'act', render: (_, task) => <AcceptCell task={task} loop={loop} /> },
          ]} />
      )}

      <Divider />
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:hall.myAccepted')}</h4>
      <MyAcceptedList loop={loop} />
    </>
  );

  return (
    <>
      <HallViewSwitch value={view} onChange={setView} />
      {view === 'publisher' ? publisherView : providerView}
    </>
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
