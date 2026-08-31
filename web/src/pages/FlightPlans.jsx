import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, DatePicker, Descriptions, Drawer, Form, Input, Modal, Select, Space, Table, Typography,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EnumTag, EMPTY, fmtTime } from '../components/supplyShared';
import { useDroneError, useDroneOptions } from '../components/droneShared';
import { createFlightPlan, getSafetyStatus } from '../api/drone';
import {
  FLIGHT_PLAN_STATUS_ACTIVE, FLIGHT_PLAN_STATUS_COLOR, FLIGHT_PLAN_STATUS_LABEL,
} from '../enums';

const { Text } = Typography;

/** 筛选条件初值。 */
const EMPTY_FILTER = { zoneId: undefined, status: undefined, assetId: undefined, pilotId: undefined };

/**
 * 飞行计划页（增量 D · T03）。
 *
 * 三层合规拦截：
 *   ① 空域下拉只列 OPERATIONAL（数据源层面，非可飞区根本不进选项）；
 *   ② 表单自定义 validator validateZoneOperational，校验不通过不发请求；
 *   ③ 后端 BizException → useDroneError().report() 按 bizCode 给出可读提示。
 *
 * ⚠️ 状态流转（Q4）：后端创建即 APPROVED 且无流转端点，前端只渲染 APPROVED，
 *    不做任何假按钮 —— 宁可功能少，不能让用户点了没反应。
 */
export default function FlightPlans() {
  const { t } = useTranslation(['common', 'drone']);
  const { message } = App.useApp();
  const { report } = useDroneError();
  const {
    flightPlans, zones, zoneOptions, assetOptions, pilotOptions, operationalZoneOptions,
    assetName, zoneName, pilotName, loading, reload,
  } = useDroneOptions();

  const [filters, setFilters] = useState(EMPTY_FILTER);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [detailId, setDetailId] = useState(null);
  const [form] = Form.useForm();

  /** 第 ② 层拦截：下拉里若被强行塞入非可飞区，此处拦下并不发请求。 */
  const validateZoneOperational = (rule, value) => {
    if (value === null || value === undefined || value === '') return Promise.resolve();
    const zone = zones.find((z) => Number(z.id) === Number(value));
    if (!zone) {
      return Promise.reject(new Error(t('drone:flightPlan.err.zoneNotFound')));
    }
    if (zone.level !== 'OPERATIONAL') {
      return Promise.reject(new Error(t('drone:flightPlan.msg.notOperationalRule')));
    }
    return Promise.resolve();
  };

  /** C4（第 0 层早反馈）：选中资产后立即查一次安全态；资产已锁机则给出表单级报错。 */
  const onFlightAssetChange = useCallback(async (val) => {
    if (val === undefined || val === null) {
      form.setFields([{ name: 'assetId', errors: [] }]);
      return;
    }
    try {
      const st = await getSafetyStatus(Number(val));
      if (st === 'LOCKED') {
        const msg = t('drone:flightPlan.err.assetLocked');
        form.setFields([{ name: 'assetId', errors: [msg] }]);
        message.error(msg);
      } else {
        form.setFields([{ name: 'assetId', errors: [] }]);
      }
    } catch (e) {
      report(e);
    }
  }, [form, message, report, t]);

  /** 纯内存过滤：切条件不重新请求。 */
  const rows = useMemo(() => flightPlans.filter((r) => {
    if (filters.zoneId && Number(r.zoneId) !== Number(filters.zoneId)) return false;
    if (filters.status && r.status !== filters.status) return false;
    if (filters.assetId && Number(r.assetId) !== Number(filters.assetId)) return false;
    if (filters.pilotId && Number(r.pilotId) !== Number(filters.pilotId)) return false;
    return true;
  }), [flightPlans, filters]);

  const detail = useMemo(
    () => (detailId === null ? null : flightPlans.find((r) => Number(r.id) === Number(detailId)) || null),
    [flightPlans, detailId]
  );

  /** 提交新增飞行计划。 */
  const submitCreate = async () => {
    let v;
    try {
      v = await form.validateFields();
    } catch (e) {
      return; // 校验失败不发请求
    }
    const body = {
      assetId: Number(v.assetId),
      zoneId: Number(v.zoneId),
      pilotId: Number(v.pilotId),
      // plannedAt 是 Instant：DatePicker showTime → ISO-8601 UTC（与 expiryDate 的 LocalDate 处理不同）
      plannedAt: v.plannedAt ? dayjs(v.plannedAt).toISOString() : null,
      routeNote: v.routeNote || null,
    };

    // C4（权威拦截）：提交时再查一次安全态，资产已锁机则拒绝提交并提示（后端 C9 兜底同源）
    try {
      const st = await getSafetyStatus(Number(v.assetId));
      if (st === 'LOCKED') {
        const msg = t('drone:flightPlan.err.assetLocked');
        form.setFields([{ name: 'assetId', errors: [msg] }]);
        message.error(msg);
        return;
      }
    } catch (e) {
      report(e);
      return; // 查不到安全态也保守拦截，避免给可能已锁机的资产排期
    }

    setSubmitting(true);
    try {
      await createFlightPlan(body);
      message.success(t('drone:flightPlan.msg.created'));
      setCreateOpen(false);
      form.resetFields();
      reload();
    } catch (e) {
      report(e);
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    { title: t('drone:flightPlan.col.id'), dataIndex: 'id', width: 80 },
    {
      title: t('drone:flightPlan.col.asset'),
      dataIndex: 'assetId',
      width: 170,
      render: (v) => assetName(v),
    },
    {
      title: t('drone:flightPlan.col.zone'),
      dataIndex: 'zoneId',
      width: 180,
      render: (v) => zoneName(v),
    },
    {
      title: t('drone:flightPlan.col.pilot'),
      dataIndex: 'pilotId',
      width: 180,
      render: (v) => pilotName(v),
    },
    {
      title: t('drone:flightPlan.col.plannedAt'),
      dataIndex: 'plannedAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('drone:flightPlan.col.status'),
      dataIndex: 'status',
      width: 110,
      render: (v) => <EnumTag value={v} labelMap={FLIGHT_PLAN_STATUS_LABEL} colorMap={FLIGHT_PLAN_STATUS_COLOR} />,
    },
    {
      title: t('drone:flightPlan.col.routeNote'),
      dataIndex: 'routeNote',
      render: (v) => v || EMPTY,
    },
    {
      title: t('drone:flightPlan.col.createdAt'),
      dataIndex: 'createdAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 90,
      fixed: 'right',
      render: (_, r) => (
        <Button size="small" type="link" onClick={() => setDetailId(r.id)}>
          {t('action.detail')}
        </Button>
      ),
    },
  ];

  return (
    <PageCard
      title={t('drone:flightPlan.title')}
      subtitle={t('drone:flightPlan.subtitle')}
      reload={reload}
      loading={loading}
      extra={
        <Perm code="drone:flightplan:create">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => {
            setCreateOpen(true);
            form.resetFields();
          }}>
            {t('drone:flightPlan.create')}
          </Button>
        </Perm>
      }
    >
      <Alert type="info" showIcon style={{ marginBottom: 8 }}
        message={t('drone:flightPlan.complianceTip')} />
      <Alert type="warning" showIcon style={{ marginBottom: 12 }}
        message={t('drone:flightPlan.flowTip')} />

      <Space wrap style={{ marginBottom: 12 }}>
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          placeholder={t('drone:flightPlan.filter.zone')}
          style={{ width: 200 }}
          value={filters.zoneId}
          onChange={(v) => setFilters((f) => ({ ...f, zoneId: v }))}
          options={zoneOptions}
        />
        <Select
          allowClear
          placeholder={t('drone:flightPlan.filter.status')}
          style={{ width: 160 }}
          value={filters.status}
          onChange={(v) => setFilters((f) => ({ ...f, status: v }))}
          options={FLIGHT_PLAN_STATUS_ACTIVE.map((o) => ({
            value: o.value,
            label: t(`drone:enum.flightPlanStatus.${o.value}`, { defaultValue: o.value }),
          }))}
        />
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          placeholder={t('drone:flightPlan.filter.asset')}
          style={{ width: 200 }}
          value={filters.assetId}
          onChange={(v) => setFilters((f) => ({ ...f, assetId: v }))}
          options={assetOptions}
        />
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          placeholder={t('drone:flightPlan.filter.pilot')}
          style={{ width: 200 }}
          value={filters.pilotId}
          onChange={(v) => setFilters((f) => ({ ...f, pilotId: v }))}
          options={pilotOptions}
        />
        <Button onClick={() => setFilters(EMPTY_FILTER)}>{t('drone:common.reset')}</Button>
      </Space>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      <Modal
        title={t('drone:flightPlan.create')}
        open={createOpen}
        onOk={submitCreate}
        confirmLoading={submitting}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
        width={560}
        okText={t('action.ok')}
        cancelText={t('action.cancel')}
      >
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message={t('drone:flightPlan.complianceTip')} />
        <Form form={form} layout="vertical">
          <Form.Item
            name="assetId"
            label={t('drone:flightPlan.form.asset')}
            rules={[{ required: true, message: t('form.required', { label: t('drone:flightPlan.form.asset') }) }]}
          >
            {/* C4：选中资产后立即查一次安全态（第 0 层早反馈） */}
            <Select showSearch optionFilterProp="label" options={assetOptions}
              onChange={onFlightAssetChange}
              placeholder={t('form.placeholderSelect')} />
          </Form.Item>
          <Form.Item
            name="zoneId"
            label={t('drone:flightPlan.form.zone')}
            rules={[
              { required: true, message: t('form.required', { label: t('drone:flightPlan.form.zone') }) },
              { validator: validateZoneOperational },
            ]}
          >
            {/* 第 ① 层拦截：非可飞作业区根本不进选项 */}
            <Select showSearch optionFilterProp="label" options={operationalZoneOptions}
              placeholder={t('form.placeholderSelect')} />
          </Form.Item>
          <Form.Item
            name="pilotId"
            label={t('drone:flightPlan.form.pilot')}
            rules={[{ required: true, message: t('form.required', { label: t('drone:flightPlan.form.pilot') }) }]}
          >
            {/* 已过期资质 disabled，不可选 */}
            <Select showSearch optionFilterProp="label" options={pilotOptions}
              placeholder={t('form.placeholderSelect')} />
          </Form.Item>
          <Form.Item name="plannedAt" label={t('drone:flightPlan.form.plannedAt')}>
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="routeNote" label={t('drone:flightPlan.form.routeNote')}>
            <Input.TextArea rows={2} maxLength={500} showCount placeholder="如 自西向东逐行喷洒" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={t('drone:flightPlan.detail.title')}
        open={detailId !== null}
        onClose={() => setDetailId(null)}
        width={520}
      >
        {detail ? (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('drone:flightPlan.col.id')}>{detail.id ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('drone:flightPlan.col.asset')}>{assetName(detail.assetId)}</Descriptions.Item>
            <Descriptions.Item label={t('drone:flightPlan.col.zone')}>{zoneName(detail.zoneId)}</Descriptions.Item>
            <Descriptions.Item label={t('drone:flightPlan.col.pilot')}>{pilotName(detail.pilotId)}</Descriptions.Item>
            <Descriptions.Item label={t('drone:flightPlan.col.plannedAt')}>{fmtTime(detail.plannedAt)}</Descriptions.Item>
            <Descriptions.Item label={t('drone:flightPlan.col.status')}>
              <EnumTag value={detail.status} labelMap={FLIGHT_PLAN_STATUS_LABEL}
                colorMap={FLIGHT_PLAN_STATUS_COLOR} />
            </Descriptions.Item>
            <Descriptions.Item label={t('drone:flightPlan.col.routeNote')}>{detail.routeNote || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('drone:flightPlan.col.createdAt')}>{fmtTime(detail.createdAt)}</Descriptions.Item>
          </Descriptions>
        ) : (
          <Text type="secondary">{t('drone:flightPlan.detail.empty')}</Text>
        )}
      </Drawer>
    </PageCard>
  );
}
