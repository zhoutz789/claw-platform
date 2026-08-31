import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Col, Divider, Form, Input, InputNumber, Modal, Row, Select, Space, Table, Tag, Typography,
} from 'antd';
import { PlusOutlined, CopyOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { AirspaceLevelTag, AirspaceOverview, useDroneError, useDroneOptions } from '../components/droneShared';
import { EMPTY, fmtTime } from '../components/supplyShared';
import { createZone } from '../api/drone';
import { AIRSPACE_LEVEL } from '../enums';

const { Text } = Typography;

/** 空域国家代码（后端 airspace_zones.country 无约束，这里给出首站覆盖的法域）。 */
const COUNTRY_OPTIONS = ['KH', 'CN', 'VN', 'TH', 'LA'].map((v) => ({ label: v, value: v }));

/** 筛选条件初值。 */
const EMPTY_FILTER = { keyword: '', level: undefined, country: undefined };

/**
 * 空域管理页（增量 D · T03）。
 *
 * 空域是**平台级**地理概念，与资产无关联列，因此本页不做资产过滤（N4）。
 * 后端 AirspaceController 无 PUT / DELETE，空域一经创建不可修改、不可停用（B1 / Q1），
 * 因此本页强制执行两项补偿措施：页顶常驻警示 + 提交前二次确认展示完整参数。
 */
export default function AirspaceZones() {
  const { t } = useTranslation(['common', 'drone']);
  const { message, modal } = App.useApp();
  const { report } = useDroneError();
  const { zones, loading, reload } = useDroneOptions();

  const [filters, setFilters] = useState(EMPTY_FILTER);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  /** 纯内存过滤：切条件不重新请求（后端无过滤参数，重新请求是无意义的网络往返）。 */
  const rows = useMemo(() => {
    const kw = String(filters.keyword || '').trim().toLowerCase();
    return zones.filter((z) => {
      if (filters.level && z.level !== filters.level) return false;
      if (filters.country && z.country !== filters.country) return false;
      if (kw) {
        const hay = `${z.name || ''} ${z.note || ''}`.toLowerCase();
        if (!hay.includes(kw)) return false;
      }
      return true;
    });
  }, [zones, filters]);

  const countOf = (level) => zones.filter((z) => z.level === level).length;
  const summary = {
    total: zones.length,
    operational: countOf('OPERATIONAL'),
    restricted: countOf('RESTRICTED'),
    nfz: countOf('NFZ'),
  };

  /** 复制中心坐标到剪贴板（无 Clipboard API 时回退 execCommand）。 */
  const copyCoords = async (zone) => {
    const text = `${zone.centerLat}, ${zone.centerLng}`;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      message.success(t('drone:common.copied'));
    } catch (e) {
      message.error(t('drone:common.copyFailed'));
    }
  };

  /**
   * 提交新增空域：先展示完整参数二次确认，再真正落库（Q1 的强制补偿措施）。
   * @returns {Promise<void>}
   */
  const submitCreate = async () => {
    let v;
    try {
      v = await form.validateFields();
    } catch (e) {
      return; // 校验失败不发请求
    }

    const body = {
      name: v.name,
      level: v.level,
      lat: Number(v.lat),
      lng: Number(v.lng),
      radiusM: Number(v.radiusM),
      country: v.country || 'KH',
      note: v.note || null,
    };

    modal.confirm({
      title: t('drone:airspace.immutableConfirmTitle'),
      width: 480,
      okText: t('action.ok'),
      cancelText: t('action.cancel'),
      content: (
        <div style={{ fontSize: 13 }}>
          <div style={{ marginBottom: 8 }}>{t('drone:airspace.immutableConfirmDesc')}</div>
          <div><Text type="secondary">{t('drone:airspace.form.name')}：</Text>{body.name}</div>
          <div><Text type="secondary">{t('drone:airspace.form.level')}：</Text>
            {t(`drone:enum.airspaceLevel.${body.level}`, { defaultValue: body.level })}
          </div>
          <div><Text type="secondary">{t('drone:airspace.form.lat')}：</Text>{body.lat}</div>
          <div><Text type="secondary">{t('drone:airspace.form.lng')}：</Text>{body.lng}</div>
          <div><Text type="secondary">{t('drone:airspace.form.radius')}：</Text>{body.radiusM} m</div>
          <div><Text type="secondary">{t('drone:airspace.form.country')}：</Text>{body.country}</div>
          <div><Text type="secondary">{t('drone:airspace.form.note')}：</Text>{body.note || EMPTY}</div>
        </div>
      ),
      onOk: async () => {
        setSubmitting(true);
        try {
          await createZone(body);
          message.success(t('drone:airspace.msg.created'));
          setCreateOpen(false);
          form.resetFields();
          reload();
        } catch (e) {
          report(e);
        } finally {
          setSubmitting(false);
        }
      },
    });
  };

  const columns = [
    { title: t('drone:airspace.col.id'), dataIndex: 'id', width: 80 },
    { title: t('drone:airspace.col.name'), dataIndex: 'name', width: 180, render: (v) => v || EMPTY },
    {
      title: t('drone:airspace.col.level'),
      dataIndex: 'level',
      width: 120,
      render: (v) => <AirspaceLevelTag value={v} />,
    },
    {
      title: t('drone:airspace.col.center'),
      key: 'center',
      width: 180,
      render: (_, r) => `${r.centerLat ?? EMPTY}, ${r.centerLng ?? EMPTY}`,
    },
    { title: t('drone:airspace.col.radius'), dataIndex: 'radiusM', width: 100, render: (v) => v ?? EMPTY },
    {
      title: t('drone:airspace.col.country'),
      dataIndex: 'country',
      width: 90,
      render: (v) => (v ? <Tag>{v}</Tag> : EMPTY),
    },
    { title: t('drone:airspace.col.note'), dataIndex: 'note', render: (v) => v || EMPTY },
    {
      title: t('drone:airspace.col.createdAt'),
      dataIndex: 'createdAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 130,
      fixed: 'right',
      render: (_, r) => (
        <Button size="small" type="link" icon={<CopyOutlined />} onClick={() => copyCoords(r)}>
          {t('drone:airspace.copyCoords')}
        </Button>
      ),
    },
  ];

  return (
    <PageCard
      title={`${t('drone:airspace.title')}（${t('drone:airspace.platformWide')}）`}
      subtitle={t('drone:airspace.subtitle')}
      reload={reload}
      loading={loading}
      extra={
        <Perm code="drone:zone:create">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => {
            setCreateOpen(true);
            form.resetFields();
            form.setFieldsValue({ country: 'KH', level: 'OPERATIONAL' });
          }}>
            {t('drone:airspace.create')}
          </Button>
        </Perm>
      }
    >
      {/* Q1 强制补偿措施 ①：页顶常驻警示，空域一经创建不可修改、不可停用 */}
      <Alert type="warning" showIcon style={{ marginBottom: 12 }}
        message={t('drone:airspace.immutableTip')} />

      <Space wrap style={{ marginBottom: 12 }}>
        <Text strong>
          {t('drone:airspace.summary', summary)}
        </Text>
      </Space>

      <Space wrap style={{ marginBottom: 12 }}>
        <Input
          allowClear
          placeholder={t('drone:airspace.filter.keyword')}
          style={{ width: 220 }}
          value={filters.keyword}
          onChange={(e) => setFilters((f) => ({ ...f, keyword: e.target.value }))}
        />
        <Select
          allowClear
          placeholder={t('drone:airspace.filter.level')}
          style={{ width: 180 }}
          value={filters.level}
          onChange={(v) => setFilters((f) => ({ ...f, level: v }))}
          options={AIRSPACE_LEVEL.map((o) => ({
            value: o.value,
            label: t(`drone:enum.airspaceLevel.${o.value}`, { defaultValue: o.value }),
          }))}
        />
        <Select
          allowClear
          placeholder={t('drone:airspace.filter.country')}
          style={{ width: 150 }}
          value={filters.country}
          onChange={(v) => setFilters((f) => ({ ...f, country: v }))}
          options={COUNTRY_OPTIONS}
        />
        <Button onClick={() => setFilters(EMPTY_FILTER)}>{t('drone:common.reset')}</Button>
      </Space>

      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <Table
            rowKey="id"
            loading={loading}
            dataSource={rows}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            pagination={{ pageSize: 10, showSizeChanger: true }}
          />
        </Col>
        <Col xs={24} lg={10}>
          <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: 12 }}>
            <Text strong>{t('drone:airspace.map.title')}</Text>
            <Divider style={{ margin: '8px 0' }} />
            <AirspaceOverview zones={rows} />
          </div>
        </Col>
      </Row>

      <Modal
        title={t('drone:airspace.create')}
        open={createOpen}
        onOk={submitCreate}
        confirmLoading={submitting}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
        width={560}
        okText={t('action.ok')}
        cancelText={t('action.cancel')}
      >
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message={t('drone:airspace.immutableTip')}
          description={t('drone:airspace.createdDesc')} />
        <Form form={form} layout="vertical"
          initialValues={{ country: 'KH', level: 'OPERATIONAL', radiusM: 1000 }}>
          <Form.Item
            name="name"
            label={t('drone:airspace.form.name')}
            rules={[
              { required: true, message: t('form.required', { label: t('drone:airspace.form.name') }) },
              { min: 1, max: 120, message: '1–120' },
            ]}
          >
            <Input placeholder="如 金边郊农作业区" maxLength={120} showCount />
          </Form.Item>
          <Form.Item
            name="level"
            label={t('drone:airspace.form.level')}
            rules={[{ required: true, message: t('form.required', { label: t('drone:airspace.form.level') }) }]}
          >
            <Select
              options={AIRSPACE_LEVEL.map((o) => ({
                value: o.value,
                label: t(`drone:enum.airspaceLevel.${o.value}`, { defaultValue: o.value }),
              }))}
            />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item
              name="lat"
              label={t('drone:airspace.form.lat')}
              rules={[
                { required: true, message: t('form.required', { label: t('drone:airspace.form.lat') }) },
                { type: 'number', min: -90, max: 90, message: '-90 ~ 90' },
              ]}
            >
              <InputNumber precision={7} style={{ width: 200 }} placeholder="11.5489000" />
            </Form.Item>
            <Form.Item
              name="lng"
              label={t('drone:airspace.form.lng')}
              rules={[
                { required: true, message: t('form.required', { label: t('drone:airspace.form.lng') }) },
                { type: 'number', min: -180, max: 180, message: '-180 ~ 180' },
              ]}
            >
              <InputNumber precision={7} style={{ width: 200 }} placeholder="104.9210000" />
            </Form.Item>
          </Space>
          <Space size="large" wrap>
            <Form.Item
              name="radiusM"
              label={t('drone:airspace.form.radius')}
              rules={[
                { required: true, message: t('form.required', { label: t('drone:airspace.form.radius') }) },
                { type: 'number', min: 1, max: 50000, message: '1 ~ 50000' },
              ]}
            >
              <InputNumber precision={0} min={1} max={50000} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="country" label={t('drone:airspace.form.country')}>
              <Select options={COUNTRY_OPTIONS} style={{ width: 200 }} />
            </Form.Item>
          </Space>
          <Form.Item name="note" label={t('drone:airspace.form.note')}>
            <Input.TextArea rows={2} maxLength={500} showCount placeholder="如 植保 / 物流可飞" />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
