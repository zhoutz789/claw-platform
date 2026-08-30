import { useState, useEffect } from 'react';
import { Tree, Card, Select, Button, Space, message, Typography, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import api from '../api';

const { Paragraph, Text } = Typography;

// 把后端权限目录树（PermissionNode，code 为唯一键）转成 antd TreeData
const toTree = (nodes) => (nodes || []).map((n) => ({
  title: n.name,
  key: n.code,
  ptype: n.ptype,
  children: n.children && n.children.length ? toTree(n.children) : undefined,
}));

// 展平所有权限 code（用于保存时构造整张矩阵）
const flattenCodes = (nodes, acc = []) => {
  (nodes || []).forEach((n) => {
    acc.push(n.code);
    if (n.children) flattenCodes(n.children, acc);
  });
  return acc;
};

// 菜单权限：角色下拉接 /v1/admin/roles；权限树接 /v1/admin/permissions/catalog（真实目录，修正旧 MENU_TREE 与导航不一致）；
// 进入角色时拉取真实权限矩阵回填勾选；保存接 PUT /v1/admin/permissions/role/{roleId}。
export default function MenuPermission() {
  const { t } = useTranslation();
  const [roles, setRoles] = useState([]);
  const [roleId, setRoleId] = useState(null);
  const [catalog, setCatalog] = useState([]);
  const [tree, setTree] = useState([]);
  const [checked, setChecked] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // 角色 + 目录树（一次加载）
  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      api.get('/v1/admin/roles'),
      api.get('/v1/admin/permissions/catalog'),
    ]).then(([rs, cat]) => {
      if (!alive) return;
      setRoles(rs || []);
      setCatalog(cat || []);
      setTree(toTree(cat));
      if (rs && rs.length) setRoleId(rs[0].id);
    }).catch((e) => {
      if (alive) message.error(t('system:menuPermission.msg.loadFailed', { msg: e.message }));
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  // 进入某角色 → 拉取真实权限矩阵，回填勾选
  useEffect(() => {
    if (!roleId) return;
    let alive = true;
    setLoading(true);
    api.get(`/v1/admin/permissions/role/${roleId}`).then((rows) => {
      if (!alive) return;
      const keys = (rows || []).filter((r) => r.canRead).map((r) => r.permissionCode);
      setChecked(keys);
    }).catch((e) => {
      if (alive) { message.error(t('system:menuPermission.msg.loadRoleFailed', { msg: e.message })); setChecked([]); }
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [roleId]);

  const save = async () => {
    if (!roleId) return;
    setSaving(true);
    // 用当前勾选项构造整张矩阵：勾中的 canRead=true，其余 false
    const allCodes = flattenCodes(catalog);
    const items = allCodes.map((code) => ({
      permissionCode: code,
      canRead: checked.includes(code),
      canCreate: false, canUpdate: false, canDelete: false, canExport: false,
      buttonsJson: null,
    }));
    try {
      await api.put(`/v1/admin/permissions/role/${roleId}`, { items });
      const roleName = roles.find((r) => r.id === roleId)?.nameI18n || roleId;
      message.success(t('system:menuPermission.msg.saved', { name: roleName }));
    } catch (e) {
      message.error(t('system:menuPermission.msg.saveFailed', { msg: e.message }));
    } finally {
      setSaving(false);
    }
  };

  const roleName = roles.find((r) => r.id === roleId)?.nameI18n || '';

  return (
    <PageCard
      title={t('system:menuPermission.title')}
      extra={
        <Select value={roleId} style={{ width: 260 }} onChange={setRoleId}
          placeholder={t('system:menuPermission.selectRole')}
          options={roles.map((r) => ({ label: `${r.roleCode} · ${r.nameI18n}`, value: r.id }))} />
      }
    >
      <Paragraph type="secondary">
        {t('system:menuPermission.desc')}
      </Paragraph>
      <Card title={t('system:menuPermission.cardTitle')}>
        {loading ? <Spin /> : tree.length === 0 ? (
          <Text type="secondary">{t('system:menuPermission.empty')}</Text>
        ) : (
          <Tree checkable checkedKeys={checked} onCheck={(ks) => setChecked(ks)}
            treeData={tree} defaultExpandAll />
        )}
        <Space style={{ marginTop: 12 }}>
          <Perm code="permission:update">
            <Button type="primary" loading={saving} disabled={!roleId} onClick={save}>{t('system:menuPermission.save')}</Button>
          </Perm>
          {roleId && <Text type="secondary">{t('system:menuPermission.roleLabel')}：{roleName}</Text>}
        </Space>
      </Card>
    </PageCard>
  );
}
