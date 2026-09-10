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
import { listMissions } from '../api/drone';
import { EarningsBlock, fetchAssetTaskEarnings } from '../components/AssetTaskEarnings';

const { Text } = Typography;

// 无人机作业类型（后端 DroneMissionType 枚举合法值；展示文案走 i18n task:drone.missionTypes.*）
const MISSION_TYPES = ['SPRAY', 'CARGO', 'INSPECTION', 'RESCUE'];

const ASSET_TYPE_ICON = { VEHICLE: '🚗', BATTERY: '🔋', CHARGER: '🔌', DRONE: '🚁' };

// 任务大厅 P2：可作为「接单方」承接任务的资产类型（车 / 电车）。
const TASK_CANDIDATE_ASSET_TYPES = ['VEHICLE', 'EV'];
// P3：DRONE_OP 任务只能由 DRONE 资产承接（与车 / 电车互斥，避免串进广告 / 客运候选池）。
const DRONE_ASSET_TYPES = ['DRONE'];
// 状态文案统一走 i18n（task:statusLabels.*），此处只保留颜色映射。
const TASK_STATUS_COLOR = { PENDING: 'default', OPEN: 'blue', ASSIGNED: 'gold', IN_PROGRESS: 'processing', COMPLETED: 'green', SETTLED: 'cyan', CANCELLED: 'red' };
const SCREEN_TYPE_LABEL = { BODY: '车身', SCREEN: '屏显' };
const RIDE_TYPE_LABEL = { HAIL: '招手即停', TAXI: '打的' };
const FARE_MODEL_LABEL = { PER_KM: '按公里', PER_TIME: '按时长', FLAT: '一口价' };

// 任务发布：按子菜单 mode 渲染单个功能（无人机/物流/广告/录像/出租/附近车辆）。
// 原 TaskPublish 的 Tabs 入口已拆为 6 个独立子页，本组件为共享实现，真实接口逻辑全部保留。
export default function TaskPublish({ mode }) {
  const { t } = useTranslation(['common', 'drone', 'task']);
  const { pilotOptions } = useDroneOptions();
  // 真实数据
  const [missions, setMissions] = useState([]);
  const [missionsLoading, setMissionsLoading] = useState(false);
  const [assets, setAssets] = useState([]);
  const [assetsLoading, setAssetsLoading] = useState(false);
  const [missionsError, setMissionsError] = useState(null);
  const [assetsError, setAssetsError] = useState(null);

  // 附近车辆筛选（按真实 assetType）
  const [nearFilter, setNearFilter] = useState('__all');

  /**
   * 重新拉取无人机作业记录（GET /v1/drone-missions）。
   * P3 起 drone_missions 由服务端在 POST /v1/tasks（DRONE_OP）时创建，
   * 因此这里只作为「作业记录」展示，发布成功后由 DronePanel 回调本函数刷新。
   * @returns {Promise<void>}
   */
  const reloadMissions = useCallback(() => {
    setMissionsLoading(true);
    setMissionsError(null);
    return listMissions({})
      .then((d) => { setMissions(d || []); })
      .catch((e) => {
        const m = '加载无人机作业失败：' + e.message;
        message.error(m);
        setMissions([]);
        setMissionsError(m);
      })
      .finally(() => setMissionsLoading(false));
  }, []);

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

  // 附近可服务车辆：客运 / 打的场景仅参考 VEHICLE / EV 资产。
  const nearAssets = useMemo(
    () => assets.filter((a) => TASK_CANDIDATE_ASSET_TYPES.includes(a.assetType)),
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
      children: (
        <DronePanel
          assets={assets}
          assetsLoading={assetsLoading}
          pilotOptions={pilotOptions}
          missions={missions}
          missionsLoading={missionsLoading}
          onReloadMissions={reloadMissions}
        />
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

// 物流配送闭环面板：发布方 / 接单方 双视图，覆盖 发布 → 可接单 → 接单 → 进度 → 完成 → 收益 完整链路。
function LogiPanel({ assets }) {
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
        .catch((e) => { message.error('加载我发布的任务失败：' + e.message); setPubTasks([]); })
        .finally(() => setPubLoading(false));
    },
    []
  );

  const loadAvailable = useCallback(
    () => {
      setProvLoading(true);
      return api.get('/v1/tasks?role=provider')
        .then((d) => setProvTasks(Array.isArray(d) ? d : (d && d.list) || []))
        .catch((e) => { message.error('加载可接单任务失败：' + e.message); setProvTasks([]); })
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
        .catch((e) => { message.error('加载我的接单失败：' + e.message); setMyAccepted([]); })
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
      message.success('物流任务已发布到任务大厅');
      pubForm.resetFields();
      loadPublished();
    } catch (e) {
      message.error('发布失败：' + e.message);
    } finally {
      setPubSubmitting(false);
    }
  };

  // 接单 → POST /v1/tasks/{id}/accept { assetId }
  const onAccept = async (task) => {
    const assetId = acceptSel[task.id];
    if (!assetId) { message.warning('请先选择接单资产'); return; }
    try {
      await api.post(`/v1/tasks/${task.id}/accept`, { assetId: Number(assetId) });
      message.success('接单成功，已绑定资产 #' + assetId);
      setAcceptSel((p) => ({ ...p, [task.id]: undefined }));
      loadAvailable();
      loadMy();
    } catch (e) {
      message.error('接单失败：' + e.message);
    }
  };

  // 更新进度 → POST /v1/tasks/{id}/progress { progressPct, note }
  const onProgress = async (item) => {
    const inp = progressInp[item.key] || {};
    const pct = inp.progressPct;
    if (pct == null || pct < 0 || pct > 100) { message.warning('请输入 0-100 之间的进度'); return; }
    try {
      await api.post(`/v1/tasks/${item.taskId}/progress`, { progressPct: Number(pct), note: inp.note || '' });
      message.success('进度已更新');
      loadMy();
    } catch (e) {
      message.error('更新进度失败：' + e.message);
    }
  };

  // 完成任务 → POST /v1/tasks/{id}/complete，随后拉取收益明细。
  const onComplete = async (item) => {
    try {
      await api.post(`/v1/tasks/${item.taskId}/complete`, {});
      message.success('任务已完成，已触发结算');
      loadMy();
      const aid = item.assetId;
      if (aid != null) {
        try {
          // 后端端点：GET /api/v1/tasks/assets/{assetId}/task-earnings（挂在 TaskController 下）。
          const earns = await api.get(`/v1/tasks/assets/${aid}/task-earnings`);
          setEarnings((p) => ({ ...p, [item.key]: Array.isArray(earns) ? earns : [] }));
        } catch (e) {
          message.warning('收益查询失败：' + e.message);
        }
      }
    } catch (e) {
      message.error('完成任务失败：' + e.message);
    }
  };

  const normStatus = (s) => (s ? String(s).toUpperCase() : s);
  const STATUS_LABEL = { PENDING: '待审核', OPEN: '待接单', ASSIGNED: '已接单', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' };
  const STATUS_COLOR = { PENDING: 'default', OPEN: 'blue', ASSIGNED: 'gold', IN_PROGRESS: 'processing', COMPLETED: 'green', CANCELLED: 'red' };
  const StatusTag = ({ status }) => {
    const s = normStatus(status);
    return <Tag color={STATUS_COLOR[s] || 'default'}>{STATUS_LABEL[s] || status || '—'}</Tag>;
  };
  const canComplete = (status) => ['ASSIGNED', 'IN_PROGRESS'].includes(normStatus(status));

  const publisherView = (
    <>
      <Card style={{ marginBottom: 14 }}>
        <Form layout="vertical" form={pubForm} onFinish={onPublish}>
          <Form.Item label="任务标题" name="title" rules={[{ required: true, message: '请输入任务标题' }]}>
            <Input placeholder="如：柬埔寨跨境生鲜配送" />
          </Form.Item>
          <Space size="large" wrap align="end">
            <Form.Item label="取货地" name="pickupAddr" rules={[{ required: true, message: '请输入取货地' }]} style={{ minWidth: 200 }}>
              <Input placeholder="俄罗斯市场站" />
            </Form.Item>
            <Form.Item label="收货地" name="dropoffAddr" rules={[{ required: true, message: '请输入收货地' }]} style={{ minWidth: 200 }}>
              <Input placeholder="金边机场" />
            </Form.Item>
            <Form.Item label="货物类型" name="cargoType" rules={[{ required: true, message: '请选择货物类型' }]}>
              <Select placeholder="选择货物类型" options={['小件包裹', '生鲜', '大件'].map((v) => ({ label: v, value: v }))} />
            </Form.Item>
            <Form.Item label="重量 (kg)" name="weightKg" rules={[{ required: true, message: '请输入重量' }]}>
              <InputNumber min={0} step={0.1} placeholder="12.5" />
            </Form.Item>
            <Form.Item label="报酬 ($)" name="rewardAmount" rules={[{ required: true, message: '请输入报酬' }]}>
              <InputNumber min={1} step={0.01} placeholder="5.00" />
            </Form.Item>
            <Form.Item label="截止时间"><Input placeholder="30 分钟内（可选）" disabled /></Form.Item>
          </Space>
          <Form.Item label="任务描述" name="description" style={{ marginTop: 4 }}>
            <Input.TextArea rows={2} placeholder="补充说明（可选）" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={pubSubmitting}>发布到任务大厅</Button>
        </Form>
      </Card>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>我发布的物流任务</h4>
      {pubLoading ? <Spin /> : pubTasks.length === 0 ? (
        <Empty description="暂无已发布的任务" />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={pubTasks} size="small"
          columns={[
            { title: '标题', dataIndex: 'title', render: (v) => v || '—' },
            { title: '报酬', dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: '状态', dataIndex: 'status', render: (s) => <StatusTag status={s} /> },
            { title: '路线', key: 'route', render: (_, r) => <span>{r.pickupAddr || '—'} → {r.dropoffAddr || '—'}</span> },
            { title: '货物', dataIndex: 'cargoType', render: (v) => v || '—' },
            { title: '重量', dataIndex: 'weightKg', render: (v) => (v != null ? `${v} kg` : '—') },
          ]} />
      )}
    </>
  );

  const providerView = (
    <>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>可接单任务</h4>
      {provLoading ? <Spin /> : provTasks.length === 0 ? (
        <Empty description="暂无匹配我方资产能力的可接单任务" />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={provTasks} size="small"
          columns={[
            { title: '标题', dataIndex: 'title', render: (v) => v || '—' },
            { title: '报酬', dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: '路线', key: 'route', render: (_, r) => <span>{r.pickupAddr || '—'} → {r.dropoffAddr || '—'}</span> },
            { title: '货物', dataIndex: 'cargoType', render: (v) => v || '—' },
            { title: '重量', dataIndex: 'weightKg', render: (v) => (v != null ? `${v} kg` : '—') },
            {
              title: '接单',
              key: 'act',
              render: (_, task) => (
                <Space>
                  <Select
                    placeholder={candidateAssets.length ? '选择资产' : '无可用车辆/电车'}
                    style={{ width: 200 }}
                    value={acceptSel[task.id]}
                    onChange={(val) => setAcceptSel((p) => ({ ...p, [task.id]: val }))}
                    options={candidateAssets.map((a) => ({ label: `${a.assetNo || a.assetType} · #${a.id}`, value: a.id }))}
                    disabled={candidateAssets.length === 0}
                  />
                  <Button type="primary" size="small" disabled={candidateAssets.length === 0} onClick={() => onAccept(task)}>接单</Button>
                </Space>
              ),
            },
          ]} />
      )}

      <Divider />
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>我的接单</h4>
      {myLoading ? <Spin /> : myAccepted.length === 0 ? (
        <Empty description="暂无接单记录" />
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
                  <StatusTag status={item.status} />
                </div>
                <div style={{ margin: '6px 0' }}>
                  <Progress percent={Number(item.progressPct) || 0} size="small" />
                </div>
                <Space wrap align="end">
                  <InputNumber min={0} max={100} placeholder="进度%" value={inp.progressPct}
                    onChange={(val) => setProgressInp((p) => ({ ...p, [item.key]: { ...inp, progressPct: val } }))} />
                  <Input placeholder="进度备注" style={{ width: 180 }} value={inp.note}
                    onChange={(e) => setProgressInp((p) => ({ ...p, [item.key]: { ...inp, note: e.target.value } }))} />
                  <Button size="small" onClick={() => onProgress(item)}>更新进度</Button>
                  <Button size="small" type="primary" disabled={!canComplete(item.status)} onClick={() => onComplete(item)}>完成</Button>
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

/** 任务状态标签（归一化大小写后按 task:statusLabels.* 取三语文案，缺失时回落原始状态值）。 */
function TaskStatusTag({ status }) {
  const { t } = useTranslation(['task']);
  const s = status ? String(status).toUpperCase() : status;
  const label = t(`task:status.${s}`, { defaultValue: '' });
  return <Tag color={TASK_STATUS_COLOR[s] || 'default'}>{label || status || '—'}</Tag>;
}

/** 任务类型标签（task:typeLabels.*）。 */
function TaskTypeTag({ taskType }) {
  const { t } = useTranslation(['task']);
  const v = taskType ? String(taskType).toUpperCase() : taskType;
  const label = t(`task:type.${v}`, { defaultValue: '' });
  return <Tag color={v === 'DRONE_OP' ? 'purple' : 'geekblue'}>{label || taskType || '—'}</Tag>;
}

/** 发布方 / 接单方 双视图切换器（三语）。 */
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
 * 接单方通用筛选：仅允许指定 assetType 的资产承接；若资产已声明 capabilities 则进一步命中所需能力。
 * @param {Array} assets 资产列表
 * @param {string[]} capabilities 任务所需能力（如 ['AD_DISPLAY'] 或 ['DRONE_OP']）
 * @param {string[]} [assetTypes] 允许的资产类型，默认车 / 电车（DRONE_OP 传 ['DRONE']）
 * @returns {Array} 候选资产
 */
function filterCandidateAssets(assets, capabilities, assetTypes = TASK_CANDIDATE_ASSET_TYPES) {
  return (assets || []).filter((a) => {
    if (!assetTypes.includes(a.assetType)) return false;
    if (Array.isArray(a.capabilities) && a.capabilities.length > 0) {
      return capabilities.some((c) => a.capabilities.includes(c));
    }
    return true;
  });
}

/**
 * 任务闭环通用逻辑 Hook：复用 logi 面板的请求 / 刷新 / 接单 / 进度 / 完成 / 收益 全链路。
 * @param {{assets: Array, capabilities: string[], taskTypes: string[], assetTypes?: string[]}} cfg
 */
function useTaskLoop({ assets, capabilities, taskTypes, assetTypes = TASK_CANDIDATE_ASSET_TYPES }) {
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

  // taskTypes / assetTypes 为字面量数组，按内容做 key 稳定 inScope / candidateAssets 引用。
  const typesKey = taskTypes.join(',');
  const assetTypesKey = assetTypes.join(',');
  const inScope = useCallback((taskType) => typesKey.split(',').includes(taskType), [typesKey]);

  const candidateAssets = useMemo(
    () => filterCandidateAssets(assets, capabilities, assetTypesKey.split(',')),
    [assets, typesKey, assetTypesKey] // eslint-disable-line react-hooks/exhaustive-deps
  );

  const loadPublished = useCallback(
    () => {
      setPubLoading(true);
      return api.get('/v1/tasks?role=publisher')
        .then((d) => {
          const list = Array.isArray(d) ? d : (d && d.list) || [];
          setPubTasks(list.filter((x) => inScope(x && x.taskType)));
        })
        .catch((e) => { message.error(t('task:loadPublishedFailed', { message: e.message })); setPubTasks([]); })
        .finally(() => setPubLoading(false));
    },
    [inScope, t]
  );

  const loadAvailable = useCallback(
    () => {
      setProvLoading(true);
      return api.get('/v1/tasks?role=provider')
        .then((d) => {
          const list = Array.isArray(d) ? d : (d && d.list) || [];
          setProvTasks(list.filter((x) => inScope(x && x.taskType)));
        })
        .catch((e) => { message.error(t('task:loadAvailableFailed', { message: e.message })); setProvTasks([]); })
        .finally(() => setProvLoading(false));
    },
    [inScope, t]
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
        .catch((e) => { message.error(t('task:loadAcceptedFailed', { message: e.message })); setMyAccepted([]); })
        .finally(() => setMyLoading(false));
    },
    [inScope, t]
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
      message.error(t('task:publishFailed', { message: e.message }));
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
      message.success(t('task:hall.acceptSuccess', { assetId }));
      setAcceptSel((p) => ({ ...p, [task.id]: undefined }));
      loadAvailable();
      loadMy();
    } catch (e) {
      message.error(t('task:acceptFailed', { message: e.message }));
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
      message.error(t('task:progressFailed', { message: e.message }));
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
          // 后端端点：GET /api/v1/tasks/assets/{assetId}/task-earnings（挂在 TaskController 下）。
          const earns = await fetchAssetTaskEarnings(aid);
          setEarnings((p) => ({ ...p, [item.key]: Array.isArray(earns) ? earns : [] }));
        } catch (e) {
          message.warning(t('task:earningsFailed', { message: e.message }));
        }
      }
    } catch (e) {
      message.error(t('task:completeFailed', { message: e.message }));
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
    }, '广告任务已发布到任务大厅');
  };

  const publisherView = (
    <>
      <Card style={{ marginBottom: 14 }}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message="车身 / 屏显媒体任务 → 设备接单展示 → 脱敏录像回传核验。" />
        <Form layout="vertical" form={pubForm} onFinish={onPublish}>
          <Form.Item label="任务标题" name="title" rules={[{ required: true, message: '请输入任务标题' }]}>
            <Input placeholder="如：金边市区车身广告投放" />
          </Form.Item>
          <Space size="large" wrap align="end">
            <Form.Item label="广告主" name="advertiser" rules={[{ required: true, message: '请输入广告主' }]} style={{ minWidth: 200 }}>
              <Input placeholder="品牌 / 商家" />
            </Form.Item>
            <Form.Item label="屏显类型" name="screenType" rules={[{ required: true, message: '请选择屏显类型' }]}>
              <Select placeholder="选择屏显类型"
                options={Object.keys(SCREEN_TYPE_LABEL).map((k) => ({ label: SCREEN_TYPE_LABEL[k], value: k }))} />
            </Form.Item>
            <Form.Item label="投放时长" name="displayDuration" style={{ minWidth: 160 }}>
              <Input placeholder="如 1 周 / 30 天" />
            </Form.Item>
            <Form.Item label="报酬 ($)" name="rewardAmount" rules={[{ required: true, message: '请输入报酬' }]}>
              <InputNumber min={1} step={0.01} placeholder="20.00" />
            </Form.Item>
          </Space>
          <Form.Item label="素材 URL" name="mediaUrl" style={{ marginTop: 4 }}>
            <Input placeholder="https://…/ad.mp4（图片 / 视频）" />
          </Form.Item>
          <Form.Item label="任务描述" name="description">
            <Input.TextArea rows={2} placeholder="补充说明（可选）" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={pubSubmitting}>发布到广告任务大厅</Button>
        </Form>
      </Card>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>我发布的广告任务</h4>
      {pubLoading ? <Spin /> : pubTasks.length === 0 ? (
        <Empty description="暂无已发布的广告任务" />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={pubTasks} size="small"
          columns={[
            { title: '标题', dataIndex: 'title', render: (v) => v || '—' },
            { title: '报酬', dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: '状态', dataIndex: 'status', render: (s) => <TaskStatusTag status={s} /> },
            { title: '广告主', dataIndex: ['ad', 'advertiser'], render: (v, r) => v || (r.ad && r.ad.advertiser) || '—' },
            { title: '屏显类型', dataIndex: ['ad', 'screenType'], render: (v, r) => { const s = v || (r.ad && r.ad.screenType); return SCREEN_TYPE_LABEL[s] || s || '—'; } },
            { title: '投放时长', dataIndex: ['ad', 'displayDuration'], render: (v, r) => v || (r.ad && r.ad.displayDuration) || '—' },
          ]} />
      )}
    </>
  );

  const providerView = (
    <>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>可接单广告任务</h4>
      {provLoading ? <Spin /> : provTasks.length === 0 ? (
        <Empty description="暂无匹配我方资产能力的可接单任务" />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={provTasks} size="small"
          columns={[
            { title: '标题', dataIndex: 'title', render: (v) => v || '—' },
            { title: '报酬', dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: '广告主', dataIndex: ['ad', 'advertiser'], render: (v, r) => v || (r.ad && r.ad.advertiser) || '—' },
            { title: '屏显类型', dataIndex: ['ad', 'screenType'], render: (v, r) => { const s = v || (r.ad && r.ad.screenType); return SCREEN_TYPE_LABEL[s] || s || '—'; } },
            { title: '素材 URL', dataIndex: ['ad', 'mediaUrl'], render: (v, r) => v || (r.ad && r.ad.mediaUrl) || '—' },
            { title: '接单', key: 'act', render: (_, task) => <AcceptCell task={task} loop={loop} /> },
          ]} />
      )}

      <Divider />
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>我的接单</h4>
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
    }, rideType === 'TAXI' ? '打的用车任务已发布到任务大厅' : '招手即停任务已发布到任务大厅');
  };

  const publisherView = (
    <>
      <Card style={{ marginBottom: 14 }}>
        <Space style={{ marginBottom: 12 }} align="center">
          <Text strong>用车类型：</Text>
          <Segmented value={rideKind} onChange={setRideKind}
            options={[
              { label: '招手即停 (HAIL_RIDE)', value: 'HAIL_RIDE' },
              { label: '打的 (TAXI)', value: 'TAXI' },
            ]} />
        </Space>
        <Form layout="vertical" form={pubForm} onFinish={onPublish}>
          <Form.Item label="任务标题" name="title" rules={[{ required: true, message: '请输入任务标题' }]}>
            <Input placeholder={rideType === 'TAXI' ? '如：机场接送 · 打的用车' : '如：市区短途 · 招手即停'} />
          </Form.Item>
          <Space size="large" wrap align="end">
            <Form.Item label="起点" name="originAddr" rules={[{ required: true, message: '请输入起点' }]} style={{ minWidth: 200 }}>
              <Input placeholder="俄罗斯市场站" />
            </Form.Item>
            <Form.Item label="终点" name="destAddr" rules={[{ required: true, message: '请输入终点' }]} style={{ minWidth: 200 }}>
              <Input placeholder="金边机场" />
            </Form.Item>
            <Form.Item label="预估距离 (km)" name="estDistanceKm" rules={[{ required: true, message: '请输入预估距离' }]}>
              <InputNumber min={0} step={0.1} placeholder="8.5" />
            </Form.Item>
            <Form.Item label="预估时长 (分)" name="estDurationMin" rules={[{ required: true, message: '请输入预估时长' }]}>
              <InputNumber min={0} step={1} placeholder="25" />
            </Form.Item>
            <Form.Item label="计价方式" name="fareModel" rules={[{ required: true, message: '请选择计价方式' }]}>
              <Select placeholder="选择计价方式"
                options={Object.keys(FARE_MODEL_LABEL).map((k) => ({ label: FARE_MODEL_LABEL[k], value: k }))} />
            </Form.Item>
            <Form.Item label="报酬 ($)" name="rewardAmount" rules={[{ required: true, message: '请输入报酬' }]}>
              <InputNumber min={1} step={0.01} placeholder="5.00" />
            </Form.Item>
          </Space>
          <Form.Item label="任务描述" name="description" style={{ marginTop: 4 }}>
            <Input.TextArea rows={2} placeholder="补充说明（可选）" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={pubSubmitting}>发布到客运任务大厅</Button>
        </Form>
      </Card>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>我发布的用车任务</h4>
      {pubLoading ? <Spin /> : pubTasks.length === 0 ? (
        <Empty description="暂无已发布的用车任务" />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={pubTasks} size="small"
          columns={[
            { title: '标题', dataIndex: 'title', render: (v) => v || '—' },
            { title: '类型', dataIndex: 'taskType', render: (v) => <Tag color={v === 'TAXI' ? 'volcano' : 'geekblue'}>{v === 'TAXI' ? '打的' : '招手即停'}</Tag> },
            { title: '报酬', dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: '状态', dataIndex: 'status', render: (s) => <TaskStatusTag status={s} /> },
            { title: '路线', key: 'route', render: (_, r) => <span>{(r.ride && r.ride.originAddr) || '—'} → {(r.ride && r.ride.destAddr) || '—'}</span> },
            { title: '预估距离', dataIndex: ['ride', 'estDistanceKm'], render: (v, r) => { const d = v ?? (r.ride && r.ride.estDistanceKm); return d != null ? `${d} km` : '—'; } },
            { title: '计价方式', dataIndex: ['ride', 'fareModel'], render: (v, r) => { const f = v || (r.ride && r.ride.fareModel); return FARE_MODEL_LABEL[f] || f || '—'; } },
          ]} />
      )}
    </>
  );

  const providerView = (
    <>
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>可接单用车任务</h4>
      {provLoading ? <Spin /> : provTasks.length === 0 ? (
        <Empty description="暂无匹配我方车辆能力的可接单任务" />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={provTasks} size="small"
          columns={[
            { title: '标题', dataIndex: 'title', render: (v) => v || '—' },
            { title: '类型', dataIndex: ['ride', 'rideType'], render: (v, r) => { const t2 = v || (r.ride && r.ride.rideType); return <Tag color={t2 === 'TAXI' ? 'volcano' : 'geekblue'}>{RIDE_TYPE_LABEL[t2] || t2 || '—'}</Tag>; } },
            { title: '报酬', dataIndex: 'rewardAmount', render: (v, r) => `$${(v ?? 0).toFixed(2)} ${r.currency || 'USD'}` },
            { title: '路线', key: 'route', render: (_, r) => <span>{(r.ride && r.ride.originAddr) || '—'} → {(r.ride && r.ride.destAddr) || '—'}</span> },
            { title: '预估距离', dataIndex: ['ride', 'estDistanceKm'], render: (v, r) => { const d = v ?? (r.ride && r.ride.estDistanceKm); return d != null ? `${d} km` : '—'; } },
            { title: '预估时长', dataIndex: ['ride', 'estDurationMin'], render: (v, r) => { const d = v ?? (r.ride && r.ride.estDurationMin); return d != null ? `${d} 分` : '—'; } },
            { title: '计价方式', dataIndex: ['ride', 'fareModel'], render: (v, r) => { const f = v || (r.ride && r.ride.fareModel); return FARE_MODEL_LABEL[f] || f || '—'; } },
            { title: '接单', key: 'act', render: (_, task) => <AcceptCell task={task} loop={loop} /> },
          ]} />
      )}

      <Divider />
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>我的接单</h4>
      <MyAcceptedList loop={loop} />
    </>
  );

  return (
    <>
      <Divider orientation="left" style={{ marginTop: 4 }}>客运 / 打的 · 任务大厅</Divider>
      <HallViewSwitch value={view} onChange={setView} />
      {view === 'publisher' ? publisherView : providerView}
    </>
  );
}

// ---------- P3：无人机低空作业闭环（taskType=DRONE_OP / capability=DRONE_OP） ----------

/** 取 TaskView.drone 明细（P3 后端回传的作业标量 map），缺省返回空对象。 */
const droneOf = (r) => ((r && r.drone) || {});

/**
 * 无人机面板：发布方 / 接单方 双视图。
 *
 * P3 起「发布」改为 POST /v1/tasks（taskType=DRONE_OP，capabilityRequired=DRONE_OP），
 * 由服务端创建关联的 drone_missions 行；本面板下方的「作业记录」仍读 /v1/drone-missions 展示。
 *
 * @param {{assets: Array, assetsLoading?: boolean, pilotOptions?: Array,
 *          missions?: Array, missionsLoading?: boolean, onReloadMissions?: Function}} props
 */
function DronePanel({ assets, assetsLoading = false, pilotOptions = [], missions = [], missionsLoading = false, onReloadMissions }) {
  const { t } = useTranslation(['common', 'drone', 'task']);
  const [view, setView] = useState('publisher');
  const loop = useTaskLoop({
    assets,
    capabilities: ['DRONE_OP'],
    taskTypes: ['DRONE_OP'],
    assetTypes: DRONE_ASSET_TYPES,
  });
  const { pubForm, pubSubmitting, pubTasks, pubLoading, provTasks, provLoading } = loop;

  const missionOptions = useMemo(
    () => MISSION_TYPES.map((v) => ({ label: t(`task:drone.missionTypes.${v}`), value: v })),
    [t]
  );
  /** 作业类型展示文案（i18n，缺失时回落原始枚举值）。 */
  const missionTypeLabel = useCallback(
    (v) => (v ? t(`task:drone.missionTypes.${v}`, { defaultValue: v }) : '—'),
    [t]
  );

  // 仅 DRONE 资产可承接 / 绑定无人机作业。
  const droneAssets = useMemo(() => (assets || []).filter((a) => a.assetType === 'DRONE'), [assets]);
  const droneAssetOptions = useMemo(
    () => droneAssets.map((x) => ({ label: `${x.assetNo || 'DRONE'} · #${x.id}`, value: x.id })),
    [droneAssets]
  );

  // 发布无人机作业任务 → POST /v1/tasks（DRONE_OP 报文）
  const onPublish = async () => {
    let v;
    try { v = await pubForm.validateFields(); } catch { return; }
    const before = missions.length;
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
    // 服务端已创建关联 drone_missions，刷新作业记录列表（失败静默，作业已在任务大厅落库）。
    if (typeof onReloadMissions === 'function') {
      try { await onReloadMissions(); } catch (e) { /* 作业记录刷新失败不影响发布结果 */ }
    }
    return before;
  };

  /** 任务列表通用列：报酬 / 状态 + drone 作业明细（作业类型 / 作业描述 / 面积 / 趟数 / 飞行时长）。 */
  const droneCols = useMemo(
    () => [
      {
        title: t('task:drone.missionType'),
        key: 'droneMissionType',
        render: (_, r) => <Tag color="purple">{missionTypeLabel(droneOf(r).missionType)}</Tag>,
      },
      { title: t('task:drone.payloadDesc'), key: 'dronePayloadDesc', render: (_, r) => droneOf(r).payloadDesc || '—' },
      {
        title: t('task:drone.areaHa'),
        key: 'droneAreaHa',
        render: (_, r) => { const v = droneOf(r).areaHa; return v != null ? `${v} ha` : '—'; },
      },
      { title: t('task:drone.trips'), key: 'droneTrips', render: (_, r) => { const v = droneOf(r).trips; return v != null ? v : '—'; } },
      {
        title: t('task:drone.flightMinutes'),
        key: 'droneFlightMinutes',
        render: (_, r) => { const v = droneOf(r).flightMinutes; return v != null ? `${v} ${t('task:drone.minutesUnit')}` : '—'; },
      },
    ],
    [t, missionTypeLabel]
  );

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
          <Form.Item label={t('task:hall.title_')} name="title" rules={[{ required: true, message: t('form.required', { label: t('task:hall.title_') }) }]}>
            <Input placeholder={t('task:hall.titlePlaceholder')} />
          </Form.Item>
          <Space size="large" wrap align="end">
            <Form.Item
              label={t('task:drone.missionType')}
              name="missionType"
              rules={[{ required: true, message: t('form.required', { label: t('task:drone.missionType') }) }]}
              style={{ minWidth: 160 }}
            >
              <Select options={missionOptions} placeholder={t('task:drone.missionTypePlaceholder')} />
            </Form.Item>
            <Form.Item
              label={t('task:drone.asset')}
              name="assetId"
              rules={[{ required: true, message: t('form.required', { label: t('task:drone.asset') }) }]}
              style={{ minWidth: 220 }}
            >
              <Select
                placeholder={assetsLoading ? '加载资产中…' : (droneAssetOptions.length ? t('task:drone.asset') : t('task:hall.noAsset'))}
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
            <Form.Item
              label={t('drone:mission.form.pilot')}
              name="pilotId"
              rules={[{ required: true, message: t('form.required', { label: t('drone:mission.form.pilot') }) }]}
            >
              <Select showSearch optionFilterProp="label" placeholder={t('drone:common.selectPlaceholder')} options={pilotOptions} />
            </Form.Item>
            <Form.Item label={t('task:hall.reward') + ' ($)'} name="rewardAmount" rules={[{ required: true, message: t('form.required', { label: t('task:hall.reward') }) }]}>
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
            ...droneCols,
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
            ...droneCols,
            { title: t('task:hall.accept'), key: 'act', render: (_, task) => <AcceptCell task={task} loop={loop} /> },
          ]} />
      )}

      <Divider />
      <h4 style={{ fontSize: 14, fontWeight: 800, margin: '4px 0 8px' }}>{t('task:myAccepted')}</h4>
      <MyAcceptedList loop={loop} />
    </>
  );

  // 作业记录：P3 起由服务端在发布 DRONE_OP 任务时创建，展示 drone_missions 明细。
  const recordsView = (
    <>
      <Divider orientation="left" style={{ marginTop: 18 }}>{t('task:drone.recordsTitle')}</Divider>
      {missionsLoading ? <Spin /> : (missions || []).length === 0 ? (
        <Empty description={t('task:drone.recordsEmpty')} />
      ) : (
        <Table rowKey="id" pagination={false} dataSource={missions} size="small"
          columns={[
            { title: t('task:drone.missionType'), dataIndex: 'missionType', render: (v) => <Tag color="purple">{missionTypeLabel(v)}</Tag> },
            { title: t('task:drone.payloadDesc'), dataIndex: 'payloadDesc', render: (v) => v || '—' },
            { title: t('task:drone.asset'), dataIndex: 'assetId', render: (v) => (v != null ? `#${v}` : '—') },
            { title: t('task:drone.areaHa'), dataIndex: 'areaHa', render: (v) => (v != null ? `${v} ha` : '—') },
            { title: t('task:drone.trips'), dataIndex: 'trips', render: (v) => v ?? '—' },
            { title: t('task:drone.flightMinutes'), dataIndex: 'flightMinutes', render: (v) => (v != null ? `${v} ${t('task:drone.minutesUnit')}` : '—') },
            { title: t('drone:mission.form.pilot'), dataIndex: 'pilotId', render: (v) => (v != null ? `#${v}` : '—') },
          ]} />
      )}
    </>
  );

  return (
    <>
      <HallViewSwitch value={view} onChange={setView} />
      {view === 'publisher' ? publisherView : providerView}
      {recordsView}
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
