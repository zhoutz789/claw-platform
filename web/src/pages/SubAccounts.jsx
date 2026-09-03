import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App, Alert, Button, Drawer, Popconfirm, Space, Table, Tag, Tree,
} from 'antd';
import { KeyOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EMPTY, GRANT_MODES, fmtTime } from '../components/onboardingShared';
import {
  createSubAccount, disableSubAccount, enableSubAccount, getSubAccountGrant, getSubAccountMe,
  grantSubAccount, listPermissionCatalog, listSubAccounts, revokeSubAccount,
} from '../api/onboarding';
import { useTranslation } from 'react-i18next';

/**
 * 子账号管理页（增量 C · 页面 10 · O28–O30 / Q11）。
 *
 * 主体端（服务站管理者 / 厂家管理员 / 商家管理员）为协作成员开子账号，授予：
 * <dl>
 *   <dt>全部功能（ALL）</dt><dd>跟随主账号角色模板，<b>平台新增功能时零改动自动获得</b>
 *       —— 满足周老板「以后功能也会不断的增加扩展」的诉求。</dd>
 *   <dt>部分功能（PARTIAL）</dt><dd>按权限菜单树勾选；实际生效集合 = 勾选 ∩ 主账号模板
 *       （越权项自动剔除并提示）。</dd>
 * </dl>
 *
 * Q11：子账号不可再开子账号（后端强校验，前端同步置灰）。
 *
 * 对接后端 AdminSubAccountController（/api/v1/org/sub-accounts）。权限码 org:subaccount:manage。
 */
export default function SubAccounts() {  const { t } = useTranslation('common');

  const { message, modal } = App.useApp();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [me, setMe] = useState(null);
  const [catalog, setCatalog] = useState([]);

  const [grantOpen, setGrantOpen] = useState(false);
  const [current, setCurrent] = useState(null);
  const [mode, setMode] = useState('ALL');
  const [checked, setChecked] = useState([]);
  const [grantInfo, setGrantInfo] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [list, meInfo, cat] = await Promise.all([
        listSubAccounts().catch(() => []),
        getSubAccountMe().catch(() => null),
        listPermissionCatalog().catch(() => []),
      ]);
      setRows(Array.isArray(list) ? list : []);
      setMe(meInfo);
      setCatalog(Array.isArray(cat) ? cat : []);
    } catch (e) {
      message.error(`子账号列表加载失败：${e.message}`);
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => { load(); }, [load]);

  /** 权限码 → antd Tree 数据（按 parent_code 建树）。 */
  const treeData = useMemo(() => {
    const byParent = new Map();
    catalog.forEach((p) => {
      const key = p.parentCode || '__root__';
      if (!byParent.has(key)) byParent.set(key, []);
      byParent.get(key).push(p);
    });
    const build = (parent) => (byParent.get(parent) || []).map((p) => ({
      key: p.code,
      title: `${p.name || p.code}（${p.code}）`,
      children: build(p.code),
    }));
    return build('__root__');
  }, [catalog]);

  const openCreate = () => {
    let payload = { userId: null, displayName: '' };
    modal.confirm({
      title: t('common:m902'),
      width: 480,
      content: (
        <div style={{ marginTop: 12 }}>
          <input
            className="ant-input"
            style={{ marginBottom: 8 }}
            placeholder={t('common:m903')}
            onChange={(e) => { payload.userId = e.target.value ? Number(e.target.value) : null; }}
          />
          <input
            className="ant-input"
            placeholder={t('common:m904')}
            onChange={(e) => { payload.displayName = e.target.value; }}
          />
          <div style={{ marginTop: 8, color: '#8c8c8c', fontSize: 12 }}>{t('common:m905')}</div>
        </div>
      ),
      okText: t('common:m906'),
      cancelText: t('common:m96'),
      onOk: async () => {
        if (!payload.userId) {
          message.error(t('common:m907'));
          return Promise.reject(new Error('no-user'));
        }
        await createSubAccount({
          userId: payload.userId,
          displayName: payload.displayName || undefined,
        });
        message.success(t('common:m908'));
        load();
      },
    });
  };

  const openGrant = async (row) => {
    setCurrent(row);
    setGrantOpen(true);
    try {
      const info = await getSubAccountGrant(row.id);
      setGrantInfo(info);
      setMode(info?.grant?.grantMode || 'ALL');
      setChecked(Array.isArray(info?.items) ? info.items : []);
    } catch (e) {
      message.error(`授权信息加载失败：${e.message}`);
      setGrantInfo(null);
      setMode('ALL');
      setChecked([]);
    }
  };

  const doGrant = async () => {
    setSubmitting(true);
    try {
      const r = await grantSubAccount(current.id, {
        grantMode: mode,
        permissionCodes: mode === 'PARTIAL' ? checked : undefined,
      });
      if (r?.removedCount > 0) {
        message.warning(`已自动剔除 ${r.removedCount} 项越权权限（主账号模板未包含）`);
      } else {
        message.success(mode === 'ALL'
          ? '已授予全部功能（跟随主账号模板，未来新功能自动继承）'
          : '已保存部分功能授权');
      }
      setGrantOpen(false);
      load();
    } catch (e) {
      message.error(`授权失败：${e.message}`);
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: t('common:m909'), dataIndex: 'userId', width: 110 },
    { title: t('common:m910'), dataIndex: 'displayName', width: 160, render: (v) => v || EMPTY },
    { title: t('common:m911'), dataIndex: 'phone', width: 130, render: (v) => v || EMPTY },
    {
      title: t('common:m8'), dataIndex: 'status', width: 100,
      render: (v) => <Tag color={v === 'ACTIVE' ? 'green' : 'red'}>{v === 'ACTIVE' ? '启用' : '已停用'}</Tag>,
    },
    { title: t('common:m279'), dataIndex: 'createdAt', width: 160, render: (v) => fmtTime(v) },
    {
      title: t('common:m58'), key: '_actions', width: 260, fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Perm code="org:subaccount:manage">
            <Button size="small" type="link" icon={<KeyOutlined />} onClick={() => openGrant(r)}>{t('common:m64')}</Button>
          </Perm>
          <Perm code="org:subaccount:manage">
            {r.status === 'ACTIVE' ? (
              <Popconfirm title={t('common:m912')} onConfirm={async () => {
                await disableSubAccount(r.id);
                message.success(t('common:m913'));
                load();
              }}>
                <Button size="small" type="link" danger icon={<StopOutlined />}>{t('common:m914')}</Button>
              </Popconfirm>
            ) : (
              <Button size="small" type="link" onClick={async () => {
                await enableSubAccount(r.id);
                message.success(t('common:m915'));
                load();
              }}>{t('common:m350')}</Button>
            )}
          </Perm>
          <Perm code="org:subaccount:manage">
            <Popconfirm title={t('common:m916')} onConfirm={async () => {
              await revokeSubAccount(r.id);
              message.success(t('common:m917'));
              load();
            }}>
              <Button size="small" type="link" danger>{t('common:m918')}</Button>
            </Popconfirm>
          </Perm>
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('common:m919')}
      subtitle="为协作成员开子账号，授予全部或部分功能（企业协作）"
      reload={load}
      loading={loading}
      extra={(
        <Perm code="org:subaccount:manage">
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={openCreate}
            disabled={Boolean(me?.viaSubAccount)}
            title={me?.viaSubAccount ? '子账号不可再开子账号（Q11）' : ''}
          >{t('common:m902')}</Button>
        </Perm>
      )}
    >
      {me?.viaSubAccount && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="当前登录身份是子账号，不可再开设子账号（Q11）。"
        />
      )}
      {me?.principalType && (
        <div style={{ marginBottom: 12 }}>
          <Tag color="blue">{t('common:m920')}{me.principalType}#{me.principalId}</Tag>
          <Tag>{me.viaSubAccount ? '子账号身份' : '主账号身份'}</Tag>
        </div>
      )}

      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      {/* 授权抽屉 */}
      <Drawer
        title={current ? `授权 · ${current.displayName || `子账号#${current.id}`}` : '授权'}
        open={grantOpen}
        onClose={() => setGrantOpen(false)}
        width={720}
        destroyOnClose
        extra={(
          <Perm code="org:subaccount:manage">
            <Button type="primary" loading={submitting} onClick={doGrant}>{t('common:m921')}</Button>
          </Perm>
        )}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space wrap>
            <span>{t('common:m922')}</span>
            <select
              style={{ height: 32, minWidth: 140 }}
              value={mode}
              onChange={(e) => setMode(e.target.value)}
            >
              {GRANT_MODES.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
            </select>
          </Space>

          {mode === 'ALL' ? (
            <Alert
              type="success"
              showIcon
              message="全部功能：跟随主账号角色模板，不落明细。平台新增功能并挂到模板后，该子账号无需重新授权即自动获得。"
            />
          ) : (
            <>
              <Alert
                type="info"
                showIcon
                message="部分功能：勾选权限码；实际生效集合 = 勾选 ∩ 主账号模板，越权项会自动剔除。"
              />
              <div style={{ maxHeight: 460, overflow: 'auto', border: '1px solid #f0f0f0', padding: 8 }}>
                <Tree
                  checkable
                  selectable={false}
                  treeData={treeData}
                  checkedKeys={checked}
                  onCheck={(keys) => setChecked(Array.isArray(keys) ? keys : keys.checked)}
                />
              </div>
            </>
          )}

          {grantInfo?.effectivePermissions?.length > 0 && (
            <div>
              <b>{t('common:m923')}{grantInfo.effectivePermissions.length}{t('common:m924')}</b>
              <div style={{ maxHeight: 140, overflow: 'auto', marginTop: 6 }}>
                <Space wrap size={[4, 4]}>
                  {grantInfo.effectivePermissions.map((c) => <Tag key={c}>{c}</Tag>)}
                </Space>
              </div>
            </div>
          )}
        </Space>
      </Drawer>
    </PageCard>
  );
}
