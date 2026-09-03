import { useCallback, useEffect, useState } from 'react';
import {
  App, Button, Descriptions, Drawer, Progress, Space, Table, Tag,
} from 'antd';
import { CheckCircleOutlined, StopOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import {
  APPLICANT_TYPES, EMPTY, ORG_STATUS, OrgStatusTag, fmtMoney, fmtRatio, fmtTime,
} from '../components/onboardingShared';
import {
  changeOrgTier, disableOrg, enableOrg, getCreditUsage, listOrgDeposits, listOrgStatusLogs,
  listOrgs, listTiersAdmin,
} from '../api/onboarding';
import { useTranslation } from 'react-i18next';

/**
 * 组织管理页（增量 C · 页面 9 · O36 / Q7）。
 *
 * 服务站 / 厂家 / 商家三类主体统一治理：查看入驻状态、保证金档位与授信额度占用，
 * 执行<b>禁用 / 启用</b>（原因必填 + 二次确认 + 留痕）与改档（重算额度）。
 *
 * <b>禁用语义（Q7 拍板）</b>：只切断新增（新建履约单 / 调拨 / 铺货入站 / 新建子账号 /
 * 再申请入驻），<b>在途订单继续履约到底</b>。被禁用组织显示红标「已禁用 · 仅可履约不可下单」。
 *
 * 对接后端 AdminOrgController（/api/v1/admin/orgs）。权限码 org:status:manage / org:credit:view。
 */
export default function OrgManage() {  const { t } = useTranslation('common');

  const { message, modal } = App.useApp();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [principalType, setPrincipalType] = useState(undefined);
  const [onboardingStatus, setOnboardingStatus] = useState(undefined);

  const [detailOpen, setDetailOpen] = useState(false);
  const [current, setCurrent] = useState(null);
  const [credit, setCredit] = useState(null);
  const [deposits, setDeposits] = useState([]);
  const [logs, setLogs] = useState([]);
  const [tiers, setTiers] = useState([]);
  const [detailLoading, setDetailLoading] = useState(false);
  /** 主体 → 额度占用（列表页「超额」标记用；只对设了额度的主体取数）。 */
  const [usageMap, setUsageMap] = useState({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listOrgs({ principalType, onboardingStatus });
      const list = Array.isArray(d) ? d : [];
      setRows(list);
      // 只对设了授信额度的并发取占用（历史主体未设额度时不校验，无需请求）
      const targets = list.filter((r) => r.creditLimit != null);
      const pairs = await Promise.all(targets.map(async (r) => {
        try {
          return [`${r.principalType}-${r.principalId}`, await getCreditUsage(r.principalType, r.principalId)];
        } catch {
          return [`${r.principalType}-${r.principalId}`, null];
        }
      }));
      setUsageMap(Object.fromEntries(pairs));
    } catch (e) {
      message.error(`组织列表加载失败：${e.message}`);
      setRows([]);
      setUsageMap({});
    } finally {
      setLoading(false);
    }
  }, [principalType, onboardingStatus, message]);

  useEffect(() => { load(); }, [load]);

  const openDetail = async (row) => {
    setCurrent(row);
    setDetailOpen(true);
    setDetailLoading(true);
    setCredit(null);
    setDeposits([]);
    setLogs([]);
    try {
      const [c, d, l, t] = await Promise.all([
        getCreditUsage(row.principalType, row.principalId).catch(() => null),
        listOrgDeposits(row.principalType, row.principalId).catch(() => []),
        listOrgStatusLogs(row.principalType, row.principalId).catch(() => []),
        listTiersAdmin(row.principalType).catch(() => []),
      ]);
      setCredit(c);
      setDeposits(Array.isArray(d) ? d : []);
      setLogs(Array.isArray(l) ? l : []);
      setTiers(Array.isArray(t) ? t : []);
    } finally {
      setDetailLoading(false);
    }
  };

  const doDisable = (row) => {
    let reason = '';
    modal.confirm({
      title: `禁用「${row.name}」`,
      width: 520,
      okText: t('common:m320'),
      okButtonProps: { danger: true },
      cancelText: t('common:m96'),
      content: (
        <div style={{ marginTop: 12 }}>
          <div style={{ marginBottom: 8, color: '#cf1322' }}>{t('common:m321')}<b>{t('common:m322')}</b>{t('common:m323')}<b>{t('common:m324')}</b>{t('common:m325')}</div>
          <textarea
            className="ant-input"
            rows={3}
            placeholder={t('common:m326')}
            onChange={(e) => { reason = e.target.value; }}
          />
        </div>
      ),
      onOk: async () => {
        if (!reason || !reason.trim()) {
          message.error(t('common:m327'));
          return Promise.reject(new Error('no-reason'));
        }
        await disableOrg(row.principalType, row.principalId, reason.trim());
        message.success(t('common:m328'));
        load();
        if (current) setCurrent({ ...current, onboardingStatus: 'DISABLED', disabledReason: reason.trim() });
      },
    });
  };

  const doEnable = (row) => {
    modal.confirm({
      title: `启用「${row.name}」`,
      content: t('common:m329'),
      okText: t('common:m330'),
      cancelText: t('common:m96'),
      onOk: async () => {
        await enableOrg(row.principalType, row.principalId, '平台启用');
        message.success(t('common:m331'));
        load();
        if (current) setCurrent({ ...current, onboardingStatus: 'ACTIVATED' });
      },
    });
  };

  const doChangeTier = (row) => {
    let tierId = null;
    modal.confirm({
      title: `调整「${row.name}」的保证金档位`,
      width: 480,
      content: (
        <div style={{ marginTop: 12 }}>
          <select
            className="ant-input"
            style={{ height: 32, width: '100%' }}
            defaultValue=""
            onChange={(e) => { tierId = e.target.value ? Number(e.target.value) : null; }}
          >
            <option value="">{t('common:m332')}</option>
            {tiers.filter((t) => Boolean(t.enabled)).map((t) => (
              <option key={t.id} value={t.id}>
                {t.tierName}{t('common:m333')}{fmtMoney(t.depositAmount)}{t('common:m334')}{' '}
                {fmtMoney(t.creditLimitOverride ?? Number(t.depositAmount) * Number(t.creditMultiplier))}）
              </option>
            ))}
          </select>
          <div style={{ marginTop: 8, color: '#8c8c8c', fontSize: 12 }}>{t('common:m335')}<b>{t('common:m336')}</b>{t('common:m337')}</div>
        </div>
      ),
      okText: t('common:m338'),
      cancelText: t('common:m96'),
      onOk: async () => {
        if (!tierId) {
          message.error(t('common:m332'));
          return Promise.reject(new Error('no-tier'));
        }
        await changeOrgTier(row.principalType, row.principalId, tierId, '平台改档');
        message.success(t('common:m339'));
        load();
        openDetail(row);
      },
    });
  };

  const columns = [
    {
      title: t('common:m340'), dataIndex: 'principalType', width: 100,
      render: (v) => <Tag color="blue">{APPLICANT_TYPES.find((x) => x.value === v)?.label || v}</Tag>,
    },
    { title: t('common:m341'), dataIndex: 'code', width: 150, render: (v) => v || EMPTY },
    { title: t('common:m277'), dataIndex: 'name', width: 200, render: (v) => v || EMPTY },
    {
      title: t('common:m342'), dataIndex: 'onboardingStatus', width: 160,
      render: (v, r) => (
        <Space size={4} direction="vertical">
          <OrgStatusTag value={v} />
          {v === 'DISABLED' && (
            <Tag color="red" style={{ marginInlineStart: 0 }}>{t('common:m343')}</Tag>
          )}
          {usageMap[`${r.principalType}-${r.principalId}`]?.overLimit && (
            <Tag color="volcano" style={{ marginInlineStart: 0 }}>{t('common:m344')}</Tag>
          )}
        </Space>
      ),
    },
    {
      title: t('common:m345'), key: '_usage', width: 160,
      render: (_, r) => {
        const u = usageMap[`${r.principalType}-${r.principalId}`];
        if (r.creditLimit == null) return <Tag>{t('common:m346')}</Tag>;
        if (!u) return EMPTY;
        return (
          <Space direction="vertical" size={2}>
            <span>{fmtMoney(u.usedValue)} / {fmtMoney(u.creditLimit)}</span>
            <Progress
              percent={Math.min(100, Number(u.usageRatio || 0) * 100)}
              size="small"
              status={u.overLimit ? 'exception' : u.warn ? 'active' : 'normal'}
            />
          </Space>
        );
      },
    },
    {
      title: t('common:m347'), dataIndex: 'creditLimit', width: 140,
      render: (v) => (v == null ? <Tag>{t('common:m346')}</Tag> : fmtMoney(v)),
    },
    { title: t('common:m348'), dataIndex: 'disabledReason', render: (v) => v || EMPTY },
    { title: t('common:m349'), dataIndex: 'disabledAt', width: 160, render: (v) => fmtTime(v) },
    {
      title: t('common:m58'), key: '_actions', width: 220, fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Button size="small" type="link" onClick={() => openDetail(r)}>{t('common:m141')}</Button>
          <Perm code="org:status:manage">
            {r.onboardingStatus === 'DISABLED' ? (
              <Button size="small" type="link" icon={<CheckCircleOutlined />} onClick={() => doEnable(r)}>{t('common:m350')}</Button>
            ) : (
              <Button size="small" type="link" danger icon={<StopOutlined />} onClick={() => doDisable(r)}>{t('common:m351')}</Button>
            )}
          </Perm>
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('common:m352')}
      subtitle="服务站 / 厂家 / 商家统一治理：状态、保证金档位、授信额度、禁用启用"
      reload={load}
      loading={loading}
    >
      <Space wrap style={{ marginBottom: 12 }}>
        <span>{t('common:m340')}</span>
        <select
          style={{ height: 32, minWidth: 120 }}
          value={principalType || ''}
          onChange={(e) => setPrincipalType(e.target.value || undefined)}
        >
          <option value="">{t('common:m353')}</option>
          {APPLICANT_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
        <span>{t('common:m342')}</span>
        <select
          style={{ height: 32, minWidth: 120 }}
          value={onboardingStatus || ''}
          onChange={(e) => setOnboardingStatus(e.target.value || undefined)}
        >
          <option value="">{t('common:m353')}</option>
          {ORG_STATUS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
        <Button onClick={load}>{t('common:m354')}</Button>
      </Space>

      <Table
        rowKey={(r) => `${r.principalType}-${r.principalId}`}
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      <Drawer
        title={current ? `组织详情 · ${current.name}` : '组织详情'}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        width={820}
        destroyOnClose
        extra={current && (
          <Space>
            <Perm code="org:status:manage">
              <Button size="small" onClick={() => doChangeTier(current)}>{t('common:m355')}</Button>
              {current.onboardingStatus === 'DISABLED' ? (
                <Button size="small" type="primary" onClick={() => doEnable(current)}>{t('common:m350')}</Button>
              ) : (
                <Button size="small" danger onClick={() => doDisable(current)}>{t('common:m351')}</Button>
              )}
            </Perm>
          </Space>
        )}
      >
        {detailLoading ? <div>{t('common:m79')}</div> : current ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('common:m340')}>
                <Tag color="blue">{APPLICANT_TYPES.find((x) => x.value === current.principalType)?.label}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('common:m342')}><OrgStatusTag value={current.onboardingStatus} /></Descriptions.Item>
              <Descriptions.Item label={t('common:m341')}>{current.code || EMPTY}</Descriptions.Item>
              <Descriptions.Item label={t('common:m277')}>{current.name || EMPTY}</Descriptions.Item>
              <Descriptions.Item label={t('common:m356')}>{current.onboardingApplicationId || EMPTY}</Descriptions.Item>
              <Descriptions.Item label={t('common:m347')}>
                {current.creditLimit == null ? <Tag>{t('common:m357')}</Tag> : fmtMoney(current.creditLimit)}
              </Descriptions.Item>
              <Descriptions.Item label={t('common:m348')} span={2}>{current.disabledReason || EMPTY}</Descriptions.Item>
              <Descriptions.Item label={t('common:m349')}>{fmtTime(current.disabledAt)}</Descriptions.Item>
            </Descriptions>

            <b>{t('common:m358')}</b>
            {credit ? (
              credit.creditLimit == null ? (
                <div>{t('common:m359')}</div>
              ) : (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Progress
                    percent={Math.min(100, Number(credit.usageRatio || 0) * 100)}
                    status={credit.overLimit ? 'exception' : credit.warn ? 'active' : 'normal'}
                  />
                  <Space wrap>
                    <Tag>{t('common:m360')}{fmtMoney(credit.usedValue)}</Tag>
                    <Tag color="blue">{t('common:m361')}{fmtMoney(credit.creditLimit)}</Tag>
                    <Tag color={credit.warn ? 'orange' : 'default'}>{t('common:m362')}{fmtRatio(credit.usageRatio)}</Tag>
                    <Tag>{t('common:m363')}{credit.deviceCount ?? 0}</Tag>
                    <Tag>{t('common:m364')}{credit.tierName || EMPTY}</Tag>
                    {credit.overLimit && <Tag color="volcano">{t('common:m365')}</Tag>}
                  </Space>
                </Space>
              )
            ) : <div>{EMPTY}</div>}

            <b>{t('common:m366')}</b>
            <Table
              rowKey="id"
              size="small"
              dataSource={deposits}
              pagination={false}
              columns={[
                { title: t('common:m367'), dataIndex: 'depositNo', width: 150 },
                { title: t('common:m316'), dataIndex: 'amount', width: 120, render: (v, r) => fmtMoney(v, r.currency) },
                { title: t('common:m8'), dataIndex: 'status', width: 110, render: (v) => <Tag>{v}</Tag> },
                { title: t('common:m368'), dataIndex: 'confirmedAt', width: 160, render: (v) => fmtTime(v) },
              ]}
            />

            <b>{t('common:m369')}</b>
            <Table
              rowKey="id"
              size="small"
              dataSource={logs}
              pagination={false}
              columns={[
                { title: t('common:m370'), dataIndex: 'action', width: 100, render: (v) => <Tag>{v}</Tag> },
                { title: t('common:m371'), key: '_st', width: 160, render: (_, r) => `${r.fromStatus || '—'} → ${r.toStatus}` },
                { title: t('common:m372'), dataIndex: 'reason', render: (v) => v || EMPTY },
                { title: t('common:m373'), dataIndex: 'operatorId', width: 90, render: (v) => v ?? EMPTY },
                { title: t('common:m35'), dataIndex: 'createdAt', width: 160, render: (v) => fmtTime(v) },
              ]}
            />
          </Space>
        ) : <div>{t('common:m374')}</div>}
      </Drawer>
    </PageCard>
  );
}
