import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, DatePicker, Form, Input, Modal, Select, Space, Table, Tag, Typography,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useNavigate } from 'react-router-dom';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EnumTag, EMPTY, fmtTime } from '../components/supplyShared';
import { fmtDate, useDroneError, useDroneOptions } from '../components/droneShared';
import { createLicense } from '../api/drone';
import {
  LICENSE_EXPIRY_WARN_DAYS, LICENSE_VALIDITY, LICENSE_VALIDITY_COLOR, LICENSE_VALIDITY_LABEL,
  PILOT_LICENSE_TYPE, licenseDaysLeft, licenseValidity,
} from '../enums';

const { Text } = Typography;

/** 筛选条件初值。 */
const EMPTY_FILTER = { ltype: undefined, issuer: undefined, validity: undefined };

/**
 * 计算资质派生状态与剩余天数。
 * @param {Object} row PilotLicense 行
 * @returns {{validity: string|null, days: number|null}} 派生状态与剩余天数
 */
function derive(row) {
  return { validity: licenseValidity(row.expiryDate), days: licenseDaysLeft(row.expiryDate) };
}

/**
 * 飞手资质页（增量 D · T03）。
 *
 * ⚠️ 后端 pilot_licenses 无 PUT、无 user_id 列（B3 / Q2）：
 *    - 不支持续期 → 页顶给出可执行绕行方案（新建一条 + 新执照编号，如原号后加 -R2）；
 *    - pilotId 语义即本表的 id，飞手下拉与列表一律按「姓名 · 执照号」展示。
 * 列表默认按 expiryDate 升序，最紧急的换证排在最前。
 */
export default function PilotLicenses() {
  const { t } = useTranslation(['common', 'drone']);
  const { message } = App.useApp();
  const { report } = useDroneError();
  const { licenses, loading, reload } = useDroneOptions();
  const navigate = useNavigate();

  const [filters, setFilters] = useState(EMPTY_FILTER);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  /** 默认按有效期升序（最紧急的排最前），再做内存过滤。 */
  const sorted = useMemo(
    () => [...licenses].sort((a, b) => String(a.expiryDate || '').localeCompare(String(b.expiryDate || ''))),
    [licenses]
  );

  const rows = useMemo(() => sorted.filter((r) => {
    if (filters.ltype && r.ltype !== filters.ltype) return false;
    if (filters.issuer && r.issuer !== filters.issuer) return false;
    if (filters.validity && derive(r).validity !== filters.validity) return false;
    return true;
  }), [sorted, filters]);

  /** 到期预警汇总。 */
  const stats = useMemo(() => {
    let expiring = 0;
    let expired = 0;
    licenses.forEach((r) => {
      const v = licenseValidity(r.expiryDate);
      if (v === 'EXPIRING') expiring += 1;
      if (v === 'EXPIRED') expired += 1;
    });
    return { total: licenses.length, expiring, expired };
  }, [licenses]);

  /** 签发机构选项：从既有数据去重，避免硬编码。 */
  const issuerOptions = useMemo(() => {
    const set = Array.from(new Set(licenses.map((r) => r.issuer).filter(Boolean)));
    return set.map((v) => ({ label: v, value: v }));
  }, [licenses]);

  const submitCreate = async () => {
    let v;
    try {
      v = await form.validateFields();
    } catch (e) {
      return; // 校验失败不发请求
    }
    const body = {
      licenseNo: v.licenseNo,
      holderName: v.holderName,
      ltype: v.ltype,
      // 表单默认值已填 SSCA，避免「我没填但响应里有」的歧义（后端同样会补 SSCA）
      issuer: v.issuer || 'SSCA',
      // expiryDate 是 LocalDate：YYYY-MM-DD，不要 toISOString()（§7.7 最容易写错的一处）
      expiryDate: v.expiryDate ? dayjs(v.expiryDate).format('YYYY-MM-DD') : null,
    };
    setSubmitting(true);
    try {
      await createLicense(body);
      message.success(t('msg.created'));
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
    { title: t('drone:license.col.id'), dataIndex: 'id', width: 80 },
    {
      title: t('drone:license.col.licenseNo'),
      dataIndex: 'licenseNo',
      width: 200,
      render: (v) => v || EMPTY,
    },
    {
      title: t('drone:license.col.holder'),
      dataIndex: 'holderName',
      width: 140,
      render: (v) => v || EMPTY,
    },
    {
      title: t('drone:license.col.type'),
      dataIndex: 'ltype',
      width: 100,
      render: (v) => t(`drone:enum.licenseType.${v}`, { defaultValue: v || EMPTY }),
    },
    {
      title: t('drone:license.col.issuer'),
      dataIndex: 'issuer',
      width: 110,
      render: (v) => (v ? <Tag>{v}</Tag> : EMPTY),
    },
    {
      title: t('drone:license.col.expiry'),
      dataIndex: 'expiryDate',
      width: 130,
      render: (v) => fmtDate(v),
    },
    {
      title: t('drone:license.col.validity'),
      key: 'validity',
      width: 180,
      render: (_, r) => {
        const { validity, days } = derive(r);
        if (!validity) return EMPTY;
        const suffix = days === null
          ? ''
          : (days < 0
            ? ` ${t('drone:license.expiredDays', { days: Math.abs(days) })}`
            : ` ${t('drone:license.daysLeft', { days })}`);
        return (
          <Space size={4}>
            <EnumTag value={validity} labelMap={LICENSE_VALIDITY_LABEL} colorMap={LICENSE_VALIDITY_COLOR} />
            <Text type="secondary" style={{ fontSize: 12 }}>{suffix}</Text>
          </Space>
        );
      },
    },
    {
      title: t('drone:license.col.createdAt'),
      dataIndex: 'createdAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
  ];

  return (
    <PageCard
      title={t('drone:license.title')}
      subtitle={t('drone:license.subtitle')}
      reload={reload}
      loading={loading}
      extra={
        <Perm code="drone:license:create">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => {
            setCreateOpen(true);
            form.resetFields();
            form.setFieldsValue({ issuer: 'SSCA' });
          }}>
            {t('drone:license.create')}
          </Button>
        </Perm>
      }
    >
      {/* Q5：提示必须给出可执行的绕行方案（-R2 后缀），否则用户撞唯一约束后完全不知道怎么办 */}
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message={t('drone:license.renewTip')} />

      {stats.expiring > 0 || stats.expired > 0 ? (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message={t('drone:license.summary.warning', {
            total: stats.total,
            expiring: stats.expiring,
            expired: stats.expired,
            days: LICENSE_EXPIRY_WARN_DAYS,
          })} />
      ) : (
        <Alert type="success" showIcon style={{ marginBottom: 12 }}
          message={t('drone:license.summary.ok', { total: stats.total })} />
      )}

      <Space wrap style={{ marginBottom: 12 }}>
        <Select
          allowClear
          placeholder={t('drone:license.filter.type')}
          style={{ width: 160 }}
          value={filters.ltype}
          onChange={(v) => setFilters((f) => ({ ...f, ltype: v }))}
          options={PILOT_LICENSE_TYPE.map((o) => ({
            value: o.value,
            label: t(`drone:enum.licenseType.${o.value}`, { defaultValue: o.value }),
          }))}
        />
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          placeholder={t('drone:license.filter.issuer')}
          style={{ width: 180 }}
          value={filters.issuer}
          onChange={(v) => setFilters((f) => ({ ...f, issuer: v }))}
          options={issuerOptions}
        />
        <Select
          allowClear
          placeholder={t('drone:license.filter.validity')}
          style={{ width: 160 }}
          value={filters.validity}
          onChange={(v) => setFilters((f) => ({ ...f, validity: v }))}
          options={LICENSE_VALIDITY.map((o) => ({
            value: o.value,
            label: t(`drone:enum.licenseValidity.${o.value}`, { defaultValue: o.value }),
          }))}
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

      <Button type="link" style={{ paddingLeft: 0 }} onClick={() => navigate('/drone-ops')}>
        {t('drone:common.gotoOps')}
      </Button>

      <Modal
        title={t('drone:license.create')}
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
          message={t('drone:license.renewTip')} />
        <Form form={form} layout="vertical" initialValues={{ issuer: 'SSCA' }}>
          <Form.Item
            name="licenseNo"
            label={t('drone:license.form.licenseNo')}
            rules={[{ required: true, message: t('form.required', { label: t('drone:license.form.licenseNo') }) }]}
          >
            <Input placeholder="如 SSCA-AG-2026-0031" maxLength={120} />
          </Form.Item>
          <Form.Item
            name="holderName"
            label={t('drone:license.form.holder')}
            rules={[{ required: true, message: t('form.required', { label: t('drone:license.form.holder') }) }]}
          >
            <Input placeholder="如 李工" maxLength={120} />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item
              name="ltype"
              label={t('drone:license.form.type')}
              rules={[{ required: true, message: t('form.required', { label: t('drone:license.form.type') }) }]}
            >
              <Select
                style={{ width: 200 }}
                options={PILOT_LICENSE_TYPE.map((o) => ({
                  value: o.value,
                  label: t(`drone:enum.licenseType.${o.value}`, { defaultValue: o.value }),
                }))}
              />
            </Form.Item>
            <Form.Item name="issuer" label={t('drone:license.form.issuer')}>
              <Input style={{ width: 200 }} placeholder="SSCA" maxLength={120} />
            </Form.Item>
          </Space>
          <Form.Item
            name="expiryDate"
            label={t('drone:license.form.expiry')}
            rules={[{ required: true, message: t('form.required', { label: t('drone:license.form.expiry') }) }]}
          >
            {/* LocalDate：无 showTime，format YYYY-MM-DD */}
            <DatePicker style={{ width: '100%' }} format="YYYY-MM-DD" />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
