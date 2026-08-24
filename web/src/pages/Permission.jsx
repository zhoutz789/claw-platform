import React, { useState, useEffect, useMemo } from 'react';
import { Card, Select, Table, Switch, Button, Space, Tag, message, Input } from 'antd';
import { useFetch } from '../hooks';
import api from '../api';
import { DATA_SCOPE, DATA_SCOPE_LABEL } from '../enums';

function flatten(nodes, depth = 0, acc = []) {
  (nodes || []).forEach((n) => {
    acc.push({ code: n.code, name: n.name, ptype: n.ptype, depth });
    if (n.children && n.children.length) flatten(n.children, depth + 1, acc);
  });
  return acc;
}

export default function Permission() {
  const { data: roles } = useFetch(() => api.get('/v1/admin/roles'));
  const { data: catalog } = useFetch(() => api.get('/v1/admin/permissions/catalog'));
  const [roleId, setRoleId] = useState(null);
  const [rows, setRows] = useState([]);
  const [scope, setScope] = useState('SELF');
  const [saving, setSaving] = useState(false);

  const roleOpts = (roles || []).map((r) => ({ label: `${r.roleCode}（${r.nameI18n}）`, value: r.id }));

  useEffect(() => {
    if (!roleId) return;
    (async () => {
      const rp = await api.get(`/v1/admin/permissions/role/${roleId}`);
      const map = {}; (rp || []).forEach((r) => { map[r.permissionCode] = r; });
      const flat = flatten(catalog || []);
      setRows(flat.map((n) => {
        const cur = map[n.code];
        return {
          ...n,
          canRead: !!cur?.canRead, canCreate: !!cur?.canCreate, canUpdate: !!cur?.canUpdate,
          canDelete: !!cur?.canDelete, canExport: !!cur?.canExport,
          btn: cur?.buttonsJson || '{}',
        };
      }));
      const roleObj = (roles || []).find((r) => r.id === roleId);
      setScope(roleObj?.dataScope || 'SELF');
    })();
  }, [roleId, catalog, roles]);

  const toggle = (code, field) => setRows((rs) => rs.map((r) => (r.code === code ? { ...r, [field]: !r[field] } : r)));

  const save = async () => {
    if (!roleId) return;
    setSaving(true);
    try {
      await api.put(`/v1/admin/permissions/role/${roleId}`, {
        items: rows.map((r) => ({
          permissionCode: r.code, canRead: r.canRead, canCreate: r.canCreate,
          canUpdate: r.canUpdate, canDelete: r.canDelete, canExport: r.canExport, buttonsJson: r.btn,
        })),
      });
      await api.put(`/v1/admin/roles/${roleId}`, { dataScope: scope });
      message.success('权限矩阵与数据范围已保存');
    } catch (e) { message.error(e.message); }
    finally { setSaving(false); }
  };

  const cols = [
    { title: '权限点', dataIndex: 'name', render: (v, r) => <span style={{ paddingLeft: r.depth * 16 }}>
      {r.ptype === 'BUTTON' ? <Tag color="purple">按钮</Tag> : null}{v}
      <small style={{ color: 'var(--muted)' }}> · {r.code}</small></span> },
    { title: '读', dataIndex: 'canRead', render: (_, r) => <Switch size="small" checked={r.canRead} onChange={() => toggle(r.code, 'canRead')} /> },
    { title: '增', dataIndex: 'canCreate', render: (_, r) => <Switch size="small" checked={r.canCreate} onChange={() => toggle(r.code, 'canCreate')} /> },
    { title: '改', dataIndex: 'canUpdate', render: (_, r) => <Switch size="small" checked={r.canUpdate} onChange={() => toggle(r.code, 'canUpdate')} /> },
    { title: '删', dataIndex: 'canDelete', render: (_, r) => <Switch size="small" checked={r.canDelete} onChange={() => toggle(r.code, 'canDelete')} /> },
    { title: '导出', dataIndex: 'canExport', render: (_, r) => <Switch size="small" checked={r.canExport} onChange={() => toggle(r.code, 'canExport')} /> },
    { title: '按钮有效', dataIndex: 'btn', render: (v, r) => <Switch size="small" checked={v && v !== '{}'} onChange={(c) => setRows((rs) => rs.map((x) => (x.code === r.code ? { ...x, btn: c ? '{"enabled":true}' : '{}' } : x)))} /> },
  ];

  return (
    <Card title="角色权限矩阵（菜单 + 增删改查 + 按钮）" extra={
      <Space>
        <Select placeholder="选择角色" style={{ width: 280 }} value={roleId} onChange={setRoleId} options={roleOpts} showSearch optionFilterProp="label" />
        <Select value={scope} style={{ width: 160 }} onChange={setScope} options={DATA_SCOPE.map((s) => ({ label: DATA_SCOPE_LABEL[s.value] || s.value, value: s.value }))} />
        <Button type="primary" loading={saving} onClick={save} disabled={!roleId}>保存</Button>
      </Space>
    }>
      <p style={{ color: 'var(--muted)', fontSize: 12 }}>
        勾选即授予该角色对应菜单的「读/增/改/删/导出」权限；数据范围控制其可见数据（本人 / 本部门 / 全部 / 特殊授权类型）。
      </p>
      <Table rowKey="code" size="small" dataSource={rows} columns={cols} pagination={false} />
    </Card>
  );
}
