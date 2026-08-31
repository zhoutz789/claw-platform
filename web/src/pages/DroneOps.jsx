import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Card, Col, DatePicker, Form, Input, InputNumber, Modal, Row, Select, Space, Table, Tabs, Tag, Typography,
} from 'antd';
import { ExclamationCircleOutlined, LockOutlined, PlusOutlined, ReloadOutlined, UnlockOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EnumTag, EMPTY, fmtTime } from '../components/supplyShared';
import { useDroneError, useDroneOptions } from '../components/droneShared';
import {
  createMission, getSafetyStatus, listSafetyEvents, resolveLock, simulateLock,
} from '../api/drone';
import {
  DRONE_MISSION_TYPE, DRONE_MISSION_TYPE_COLOR, DRONE_MISSION_TYPE_LABEL,
  DRONE_SAFETY_CAUSE_COLOR, DRONE_SAFETY_CAUSE_LABEL,
  DRONE_SAFETY_EVENT_STATUS_COLOR, DRONE_SAFETY_EVENT_STATUS_LABEL,
} from '../enums';

const { Title } = Typography;
const { Text } = Typography;

/** 需要「作业面积」的作业类型。 */
const AREA_TYPES = ['SPRAY'];
/** 需要「配送趟数」的作业类型。 */
const TRIP_TYPES = ['CARGO'];

/** 三个模拟锁机按钮的预填说明（可改），人工锁机不预填且必填。 */
const SIMULATE_PRESETS = [
  { cause: 'GEOFENCE_VIOLATION', icon: <ExclamationCircleOutlined /> },
  { cause: 'LOST_LINK', icon: <LockOutlined /> },
  { cause: 'LOW_BATTERY', icon: <LockOutlined /> },
];

/** 安全管控轮询间隔（v1.2 拍板：30s）。 */
const SAFETY_POLL_INTERVAL = 30000;

/**
 * 安全管控 30s 轮询 hook（v1.2 拍板 · team-lead 四条限定 + 三条实现约束）。
 *
 * 仅在 enabled 为真时轮询；满足：
 *   - 调用方通过 enabled 控制「只在安全管控 Tab2 激活时」（限 1）；
 *   - enabled 同时要求已选中资产（限 2）；
 *   - 页面不可见（document.hidden）时暂停（限 3）；
 *   - 卸载 / 切换资产 / 停用 clearInterval（限 4）；
 *   - 只刷新 status + events，不触发 useDroneOptions 的下拉重载（约束 5）；
 *   - 在途请求保护：上一轮未返回则跳过本轮 tick（约束 6）；
 *   - 转回可见时立即刷新一次（约束 7）。
 *
 * @param {number|undefined|null} assetId 资产 ID
 * @param {boolean} enabled 是否允许轮询（Tab2 激活且已选中资产）
 * @param {(err: Error, fallbackKey?: string) => void} report 错误上报
 * @returns {{status: string|null, events: Array, loading: boolean, lastUpdated: number|null, reload: () => void}}
 */
function useSafetyPolling(assetId, enabled, report) {
  const [status, setStatus] = useState(null);
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(false);
  const [lastUpdated, setLastUpdated] = useState(null);
  const assetRef = useRef(assetId);
  const inFlight = useRef(false);

  const fetchOnce = useCallback(async () => {
    const id = assetRef.current;
    if (id === undefined || id === null) return;
    if (inFlight.current) return; // 约束 6：在途保护
    inFlight.current = true;
    setLoading(true);
    try {
      const [st, ev] = await Promise.all([getSafetyStatus(id), listSafetyEvents(id)]);
      setStatus(st);
      setEvents(Array.isArray(ev) ? ev : []);
      setLastUpdated(Date.now());
    } catch (e) {
      report(e, 'drone:safety.msg.loadFailed');
      setStatus(null);
      setEvents([]);
    } finally {
      inFlight.current = false;
      setLoading(false);
    }
  }, [report]);

  // 选中资产变化：立即刷新一次；后续轮询 effect 会重建（自动 clearInterval 旧定时器）
  useEffect(() => {
    assetRef.current = assetId;
    if (assetId === undefined || assetId === null) {
      setStatus(null);
      setEvents([]);
      setLastUpdated(null);
      return;
    }
    fetchOnce();
  }, [assetId, fetchOnce]);

  // 轮询：仅当 enabled（Tab2 激活 + 已选资产）且页面可见
  useEffect(() => {
    if (!enabled) return undefined;
    const tick = () => {
      if (typeof document !== 'undefined' && document.hidden) return; // 限 3：不可见暂停
      fetchOnce();
    };
    const timer = setInterval(tick, SAFETY_POLL_INTERVAL);
    const onVisible = () => {
      if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
        fetchOnce(); // 约束 7：转回可见立即刷新
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(timer); // 限 4：卸载 / 停用 clearInterval
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [enabled, fetchOnce]);

  return { status, events, loading, lastUpdated, reload: fetchOnce };
}

/**
 * 作业与安全管控页（增量 D · T04）。
 *
 * 本页是无人机**写操作的唯一入口**：
 *   - 作业登记（POST /v1/drone-missions）
 *   - 触发 / 解除锁机（POST /v1/drone-safety/{id}/simulate | /resolve）
 * ProductIot.jsx 的 DroneAirTab 已收敛为只读展示 + 跳转本页（N5 裁定），
 * 避免同一写操作两个入口带来的双份维护成本、权限闸门配两遍与状态不同步。
 *
 * 安全态**只消费**后端 GET /drone-safety/{id} 的返回值，
 * 前端禁止按事件条数自行推算（§5.2）。
 */
export default function DroneOps() {
  const { t } = useTranslation(['common', 'drone']);
  const { message, modal } = App.useApp();
  const { report } = useDroneError();
  const {
    missions, assetOptions, pilotOptions, assetName, pilotName, loading: optionsLoading, reload,
  } = useDroneOptions();

  const [activeTab, setActiveTab] = useState('mission');

  /* ------------------------------ Tab1 · 作业任务 ------------------------------ */

  const [missionFilter, setMissionFilter] = useState({ assetId: undefined, missionType: undefined });
  const [submitting, setSubmitting] = useState(false);
  const [missionForm] = Form.useForm();
  const [missionType, setMissionType] = useState(undefined);
  // C4：选中资产后立即查一次安全态（第 0 层早反馈，非阻断 —— 作业计量即便锁机也须入账，见 C8）
  const [missionSafety, setMissionSafety] = useState(null);

  const missionRows = useMemo(() => missions.filter((m) => {
    if (missionFilter.assetId && Number(m.assetId) !== Number(missionFilter.assetId)) return false;
    if (missionFilter.missionType && m.missionType !== missionFilter.missionType) return false;
    return true;
  }), [missions, missionFilter]);

  /** 资产变化时查询安全态（早反馈，仅提示不阻断）。 */
  const onMissionAssetChange = useCallback(async (val) => {
    if (val === undefined || val === null) { setMissionSafety(null); return; }
    try {
      const st = await getSafetyStatus(Number(val));
      setMissionSafety(st);
      if (st === 'LOCKED') {
        message.warning(t('drone:mission.msg.assetLockedWarning'));
      }
    } catch (e) {
      report(e);
    }
  }, [message, report, t]);

  const submitMission = async () => {
    let v;
    try {
      v = await missionForm.validateFields();
    } catch (e) {
      return; // 校验失败不发请求
    }
    const body = {
      assetId: Number(v.assetId),
      missionType: v.missionType,
      payloadDesc: v.payloadDesc || null,
      // 条件字段：areaHa 仅 SPRAY，trips 仅 CARGO，其余类型一律不提交
      areaHa: AREA_TYPES.includes(v.missionType) && v.areaHa != null ? Number(v.areaHa) : null,
      trips: TRIP_TYPES.includes(v.missionType) && v.trips != null ? Number(v.trips) : null,
      flightMinutes: v.flightMinutes != null ? Number(v.flightMinutes) : null,
      pilotId: Number(v.pilotId),
      // executedAt 是 Instant：DatePicker showTime → ISO-8601 UTC
      executedAt: v.executedAt ? dayjs(v.executedAt).toISOString() : new Date().toISOString(),
    };
    setSubmitting(true);
    try {
      // C4 + C8：提交时再查一次（权威）。作业计量**不阻断** —— 即便资产已锁机也须入账（审计 / 分账数据源）。
      try {
        const st = await getSafetyStatus(body.assetId);
        setMissionSafety(st);
        if (st === 'LOCKED') {
          message.warning(t('drone:mission.msg.assetLockedWarning'));
        }
      } catch (e) {
        report(e);
      }
      await createMission(body);
      message.success(t('drone:mission.msg.created'));
      missionForm.resetFields();
      setMissionType(undefined);
      setMissionSafety(null);
      reload();
    } catch (e) {
      report(e);
    } finally {
      setSubmitting(false);
    }
  };

  const missionColumns = [
    { title: t('drone:mission.col.id'), dataIndex: 'id', width: 80 },
    {
      title: t('drone:mission.col.asset'),
      dataIndex: 'assetId',
      width: 170,
      render: (v) => assetName(v),
    },
    {
      title: t('drone:mission.col.missionType'),
      dataIndex: 'missionType',
      width: 130,
      render: (v) => <EnumTag value={v} labelMap={DRONE_MISSION_TYPE_LABEL} colorMap={DRONE_MISSION_TYPE_COLOR} />,
    },
    {
      title: t('drone:mission.col.payload'),
      dataIndex: 'payloadDesc',
      render: (v) => v || EMPTY,
    },
    {
      title: t('drone:mission.col.areaHa'),
      dataIndex: 'areaHa',
      width: 110,
      render: (v) => v ?? EMPTY,
    },
    {
      title: t('drone:mission.col.trips'),
      dataIndex: 'trips',
      width: 90,
      render: (v) => v ?? EMPTY,
    },
    {
      title: t('drone:mission.col.flightMinutes'),
      dataIndex: 'flightMinutes',
      width: 110,
      render: (v) => v ?? EMPTY,
    },
    {
      title: t('drone:mission.col.pilot'),
      dataIndex: 'pilotId',
      width: 180,
      render: (v) => pilotName(v),
    },
    {
      title: t('drone:mission.col.executedAt'),
      dataIndex: 'executedAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
  ];

  const missionTab = (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Card size="small" title={t('drone:mission.title')}>
        <Form form={missionForm} layout="vertical" onFinish={submitMission}>
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item
                name="missionType"
                label={t('drone:mission.form.missionType')}
                rules={[{ required: true, message: t('form.required', { label: t('drone:mission.form.missionType') }) }]}
              >
                <Select
                  options={DRONE_MISSION_TYPE.map((o) => ({
                    value: o.value,
                    label: t(`drone:enum.missionType.${o.value}`, { defaultValue: o.value }),
                  }))}
                  onChange={(v) => setMissionType(v)}
                  placeholder={t('form.placeholderSelect')}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="assetId"
                label={t('drone:mission.form.asset')}
                rules={[{ required: true, message: t('form.required', { label: t('drone:mission.form.asset') }) }]}
              >
                {/* C4：选中资产后立即查一次安全态（早反馈，非阻断） */}
                <Select showSearch optionFilterProp="label" options={assetOptions}
                  onChange={onMissionAssetChange}
                  placeholder={t('form.placeholderSelect')} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="pilotId"
                label={t('drone:mission.form.pilot')}
                rules={[{ required: true, message: t('form.required', { label: t('drone:mission.form.pilot') }) }]}
              >
                {/* 飞手改执照下拉；已过期资质 disabled */}
                <Select showSearch optionFilterProp="label" options={pilotOptions}
                  placeholder={t('form.placeholderSelect')} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item name="payloadDesc" label={t('drone:mission.form.payload')}>
                <Input placeholder="如 农药 40L / 货箱 20kg" maxLength={255} />
              </Form.Item>
            </Col>
            {/* 条件字段：areaHa 仅 SPRAY */}
            {AREA_TYPES.includes(missionType) && (
              <Col xs={24} md={8}>
                <Form.Item name="areaHa" label={t('drone:mission.form.areaHa')}>
                  <InputNumber min={0} precision={2} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            )}
            {/* 条件字段：trips 仅 CARGO */}
            {TRIP_TYPES.includes(missionType) && (
              <Col xs={24} md={8}>
                <Form.Item name="trips" label={t('drone:mission.form.trips')}>
                  <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            )}
            <Col xs={24} md={8}>
              <Form.Item name="flightMinutes" label={t('drone:mission.form.flightMinutes')}>
                <InputNumber min={0} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="executedAt" label={t('drone:mission.form.executedAt')}>
                <DatePicker showTime style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Perm code="drone:mission:create">
            <Button type="primary" icon={<PlusOutlined />} htmlType="submit" loading={submitting}>
              {t('drone:mission.submit')}
            </Button>
          </Perm>
        </Form>
        {/* C4 + C8：作业计量即便资产锁机也照常入账，仅给非阻断提示 */}
        {missionSafety === 'LOCKED' && (
          <Alert type="warning" showIcon style={{ marginTop: 12 }}
            message={t('drone:mission.msg.assetLockedWarning')} />
        )}
      </Card>

      <Card size="small">
        <Space wrap style={{ marginBottom: 12 }}>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('drone:mission.filter.asset')}
            style={{ width: 220 }}
            value={missionFilter.assetId}
            onChange={(v) => setMissionFilter((f) => ({ ...f, assetId: v }))}
            options={assetOptions}
          />
          <Select
            allowClear
            placeholder={t('drone:mission.filter.missionType')}
            style={{ width: 180 }}
            value={missionFilter.missionType}
            onChange={(v) => setMissionFilter((f) => ({ ...f, missionType: v }))}
            options={DRONE_MISSION_TYPE.map((o) => ({
              value: o.value,
              label: t(`drone:enum.missionType.${o.value}`, { defaultValue: o.value }),
            }))}
          />
          <Button onClick={() => setMissionFilter({ assetId: undefined, missionType: undefined })}>
            {t('drone:common.reset')}
          </Button>
        </Space>
        <Table
          rowKey="id"
          loading={optionsLoading}
          dataSource={missionRows}
          columns={missionColumns}
          size="middle"
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: t('drone:mission.empty') }}
          pagination={{ pageSize: 10, showSizeChanger: true }}
        />
      </Card>
    </Space>
  );

  /* ------------------------------ Tab2 · 安全管控 ------------------------------ */

  const [assetId, setAssetId] = useState(undefined);
  const [manualOpen, setManualOpen] = useState(false);
  const [manualDetail, setManualDetail] = useState('');
  const [acting, setActing] = useState(false);

  // C3 + v1.2 拍板：30s 轮询，仅在 Tab2 激活且已选中资产时启动
  const safetyEnabled = activeTab === 'safety' && assetId !== undefined && assetId !== null;
  const { status: safetyStatus, events, loading: safetyLoading, lastUpdated, reload: reloadSafety } =
    useSafetyPolling(assetId, safetyEnabled, report);

  const locked = safetyStatus === 'LOCKED';
  const openCount = events.filter((e) => e.status === 'OPEN').length;

  /**
   * 触发锁机。
   * @param {string} cause 触发原因
   * @param {string} [detail] 说明
   * @returns {Promise<void>}
   */
  const doSimulate = async (cause, detail) => {
    if (assetId === undefined || assetId === null) {
      message.warning(t('drone:safety.selectAsset'));
      return;
    }
    setActing(true);
    try {
      await simulateLock(assetId, { cause, detail: detail || '' });
      // 刻意用 error 级（红色）强调风险，与 PRD §5.4 一致
      message.error(t('drone:safety.msg.locked', {
        cause: t(`drone:enum.safetyCause.${cause}`, { defaultValue: cause }),
      }));
      setManualOpen(false);
      setManualDetail('');
      await reloadSafety();
    } catch (e) {
      report(e);
    } finally {
      setActing(false);
    }
  };

  /** 解除锁机：后端按 FIFO 解除最早 1 条，多条 OPEN 时需重复操作。 */
  const doResolve = async () => {
    if (assetId === undefined || assetId === null) return;
    const run = async () => {
      setActing(true);
      try {
        await resolveLock(assetId);
        message.success(t('drone:safety.msg.resolved'));
        await reloadSafety();
      } catch (e) {
        report(e);
        // 无 OPEN 事件（并发竞态）也重拉一次，让卡片自动转 NORMAL
        await reloadSafety();
      } finally {
        setActing(false);
      }
    };
    if (openCount > 1) {
      modal.confirm({
        title: t('drone:safety.confirm.multiTitle'),
        content: t('drone:safety.confirm.multiDesc', { count: openCount }),
        okText: t('action.ok'),
        cancelText: t('action.cancel'),
        onOk: run,
      });
      return;
    }
    await run();
  };

  const safetyColumns = [
    {
      title: t('drone:safety.col.createdAt'),
      dataIndex: 'createdAt',
      width: 170,
      render: (v) => fmtTime(v),
    },
    {
      title: t('drone:safety.col.cause'),
      dataIndex: 'cause',
      width: 130,
      render: (v) => <EnumTag value={v} labelMap={DRONE_SAFETY_CAUSE_LABEL} colorMap={DRONE_SAFETY_CAUSE_COLOR} />,
    },
    {
      title: t('drone:safety.col.status'),
      dataIndex: 'status',
      width: 110,
      render: (v) => (
        <EnumTag value={v} labelMap={DRONE_SAFETY_EVENT_STATUS_LABEL}
          colorMap={DRONE_SAFETY_EVENT_STATUS_COLOR} />
      ),
    },
    { title: t('drone:safety.col.detail'), dataIndex: 'detail', render: (v) => v || EMPTY },
    {
      title: t('drone:safety.col.resolvedAt'),
      dataIndex: 'resolvedAt',
      width: 170,
      render: (v) => fmtTime(v),
    },
  ];

  const safetyTab = (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Card size="small">
        <Space wrap>
          <Select
            showSearch
            optionFilterProp="label"
            placeholder={t('drone:safety.selectAsset')}
            style={{ width: 280 }}
            value={assetId}
            onChange={setAssetId}
            options={assetOptions}
          />
          <Button icon={<ReloadOutlined />} onClick={reloadSafety} loading={safetyLoading}>
            {t('action.refresh')}
          </Button>
          {/* C3：兜底展示「上次刷新时间」，轮询 / 手动刷新都会更新 */}
          {lastUpdated && (
            <Text type="secondary">
              {t('drone:safety.lastUpdated', { time: dayjs(lastUpdated).format('HH:mm:ss') })}
            </Text>
          )}
        </Space>
      </Card>

      <Card size="small">
        {assetId === undefined || assetId === null ? (
          <Alert type="info" showIcon message={t('drone:safety.selectAsset')} />
        ) : safetyLoading && safetyStatus === null ? (
          <Alert type="info" showIcon message={t('msg.loading')} />
        ) : safetyStatus === 'LOCKED' ? (
          <Alert
            type="error"
            showIcon
            message={
              <span>
                <Title level={5} style={{ margin: 0, display: 'inline', color: '#cf1322' }}>
                  {t('drone:safety.locked.title')}
                </Title>
                {openCount > 0 && (
                  <Tag color="red" style={{ marginLeft: 8 }}>{`OPEN × ${openCount}`}</Tag>
                )}
              </span>
            }
            description={t('drone:safety.locked.desc')}
          />
        ) : safetyStatus === 'NORMAL' ? (
          <Alert
            type="success"
            showIcon
            message={
              <Title level={5} style={{ margin: 0, display: 'inline', color: '#52c41a' }}>
                {t('drone:safety.normal.title')}
              </Title>
            }
            description={t('drone:safety.normal.desc')}
          />
        ) : (
          <Alert type="warning" showIcon message={t('drone:safety.msg.loadFailed')} />
        )}
        <Space wrap style={{ marginTop: 12 }}>
          {/* 三个预填模拟锁机 */}
          {SIMULATE_PRESETS.map((preset) => (
            <Perm key={preset.cause} code="drone:safety:simulate">
              <Button
                icon={preset.icon}
                loading={acting}
                disabled={assetId === undefined || assetId === null}
                onClick={() => doSimulate(preset.cause, defaultDetailOf(preset.cause, t))}
              >
                {t(`drone:safety.btn.${btnKeyOf(preset.cause)}`)}
              </Button>
            </Perm>
          ))}
          {/* 人工锁机：detail 必填，弹窗输入 */}
          <Perm code="drone:safety:simulate">
            <Button
              icon={<LockOutlined />}
              loading={acting}
              disabled={assetId === undefined || assetId === null}
              onClick={() => { setManualDetail(''); setManualOpen(true); }}
            >
              {t('drone:safety.btn.manual')}
            </Button>
          </Perm>
          {/* 解除锁机：仅 LOCKED 时可用（第一道防线） */}
          <Perm code="drone:safety:resolve">
            <Button
              type="primary"
              icon={<UnlockOutlined />}
              loading={acting}
              disabled={!locked}
              onClick={doResolve}
            >
              {t('drone:safety.btn.resolve')}
            </Button>
          </Perm>
        </Space>
        {/* U6：自动锁机（越界触发）后端尚未接通，如实标注，避免误导用户以为存围栏即生效 */}
        <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
          {t('drone:safety.autoLockNote')}
        </Text>
      </Card>

      <Card size="small" title={t('drone:safety.tab')}>
        <Table
          rowKey="id"
          loading={safetyLoading}
          dataSource={events}
          columns={safetyColumns}
          size="middle"
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: t('drone:safety.empty') }}
          pagination={{ pageSize: 10, showSizeChanger: true }}
        />
      </Card>

      <Modal
        title={t('drone:safety.detail.title')}
        open={manualOpen}
        onOk={() => doSimulate('MANUAL', manualDetail)}
        confirmLoading={acting}
        onCancel={() => setManualOpen(false)}
        okText={t('action.ok')}
        cancelText={t('action.cancel')}
        destroyOnClose
      >
        <Form layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            label={t('drone:safety.detail.title')}
            required
            validateStatus={manualDetail ? '' : 'error'}
            help={manualDetail ? undefined : t('drone:safety.detail.required')}
          >
            <Input.TextArea
              rows={3}
              maxLength={500}
              value={manualDetail}
              onChange={(e) => setManualDetail(e.target.value)}
              placeholder={t('drone:safety.detail.placeholder')}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );

  return (
    <PageCard title={t('drone:common.opsTitle')} subtitle={t('drone:common.opsSubtitle')}>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          { key: 'mission', label: t('drone:mission.tab'), children: missionTab },
          { key: 'safety', label: t('drone:safety.tab'), children: safetyTab },
        ]}
      />
    </PageCard>
  );
}

/**
 * 模拟锁机的默认说明（可改）。
 * @param {string} cause 触发原因
 * @param {Function} t i18n 翻译函数
 * @returns {string} 默认说明
 */
function defaultDetailOf(cause, t) {
  if (cause === 'GEOFENCE_VIOLATION') return t('drone:enum.safetyCause.GEOFENCE_VIOLATION');
  if (cause === 'LOST_LINK') return t('drone:enum.safetyCause.LOST_LINK');
  if (cause === 'LOW_BATTERY') return t('drone:enum.safetyCause.LOW_BATTERY');
  return '';
}

/**
 * 触发原因 → 按钮 i18n 短键。
 * @param {string} cause 触发原因
 * @returns {string} 按钮键名
 */
function btnKeyOf(cause) {
  if (cause === 'GEOFENCE_VIOLATION') return 'geofence';
  if (cause === 'LOST_LINK') return 'lostLink';
  if (cause === 'LOW_BATTERY') return 'lowBattery';
  return 'manual';
}
