import { useState, useEffect, useMemo } from 'react';
import { Card, Select, Switch, Button, Space, Tag, message, Tabs, Tree, Modal, Form, Input, InputNumber } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useFetch } from '../hooks';
import { Perm } from '../components/Perm';
import api from '../api';
import { DATA_SCOPE, DATA_SCOPE_LABEL, enumLabel } from '../enums';

// 目录项转为 antd Tree 节点（保留原始字段供编辑回填）。
const toTree = (nodes) => (nodes || []).map((n) => ({
  title: n.name,
  key: n.code,
  ptype: n.ptype,
  raw: n,
  children: n.children && n.children.length ? toTree(n.children) : undefined,
}));

// 拍平目录，供父级下拉与递归收集使用。
const flatten = (nodes, acc = []) => {
  (nodes || []).forEach((n) => {
    acc.push(n);
    if (n.children) flatten(n.children, acc);
  });
  return acc;
};

// 收集某 code 的全部后代 code（含其自身），用于删除/改父级时防环。
const descendantCodes = (catalog, code, out = []) => {
  const hit = (catalog || []).find((n) => n.code === code);
  if (!hit) return out;
  out.push(code);
  (hit.children || []).forEach((c) => descendantCodes(catalog, c.code, out));
  return out;
};

// flattenMatrix 已移除：权限矩阵现渲染为带内联开关的目录树（见 matrixTitleRender）。

export default function Permission() {
  const { t } = useTranslation();

  // ---------- 权限目录（增删改） ----------
  const [catalog, setCatalog] = useState([]);
  const [catLoading, setCatLoading] = useState(false);
  const [catModal, setCatModal] = useState({ open: false, editing: null });
  const [catSaving, setCatSaving] = useState(false);
  const [form] = Form.useForm();

  const loadCatalog = async () => {
    setCatLoading(true);
    try {
      const data = await api.get('/v1/admin/permissions/catalog');
      setCatalog(Array.isArray(data) ? data : []);
    } catch (e) {
      message.error(t('system:permission.catalog.msg.loadFailed', { msg: e.message }));
    } finally {
      setCatLoading(false);
    }
  };
  useEffect(() => { loadCatalog(); }, []);

  const flatCatalog = useMemo(() => flatten(catalog), [catalog]);
  const parentOptions = useMemo(() => {
    const banned = catModal.editing ? new Set(descendantCodes(catalog, catModal.editing.code)) : new Set();
    return flatCatalog
      .filter((n) => n.ptype === 'MENU' && !banned.has(n.code))
      .map((n) => ({ label: `${n.name}（${n.code}）`, value: n.code }));
  }, [flatCatalog, catalog, catModal.editing]);

  const openAddCat = () => {
    form.resetFields();
    setCatModal({ open: true, editing: null });
  };
  const openEditCat = (node) => {
    form.resetFields();
    form.setFieldsValue({
      name: node.name, ptype: node.ptype, parentCode: node.parentCode || null,
      path: node.path || undefined, sortNo: node.sortNo ?? 0, icon: node.icon || undefined,
    });
    setCatModal({ open: true, editing: node });
  };
  const submitCat = async () => {
    const v = await form.validateFields();
    setCatSaving(true);
    try {
      const body = {
        name: v.name.trim(),
        ptype: v.ptype || 'MENU',
        parentCode: v.parentCode || null,
        path: v.path ? v.path.trim() : null,
        sortNo: Number(v.sortNo ?? 0),
        icon: v.icon ? v.icon.trim() : null,
      };
      if (catModal.editing) {
        await api.put(`/v1/admin/permissions/catalog/${catModal.editing.code}`, body);
        message.success(t('system:permission.catalog.msg.updated'));
      } else {
        await api.post('/v1/admin/permissions/catalog', { code: v.code.trim(), ...body });
        message.success(t('system:permission.catalog.msg.created'));
      }
      setCatModal({ open: false, editing: null });
      await loadCatalog();
    } catch (e) {
      message.error(e.message);
    } finally {
      setCatSaving(false);
    }
  };
  const deleteCat = async (code) => {
    try {
      await api.delete(`/v1/admin/permissions/catalog/${code}`);
      message.success(t('system:permission.catalog.msg.deleted'));
      await loadCatalog();
    } catch (e) {
      message.error(e.message);
    }
  };

  const treeData = useMemo(() => toTree(catalog), [catalog]);
  const catTitleRender = (node) => (
    <Space>
      <span>{node.title}</span>
      <Tag color={node.ptype === 'BUTTON' ? 'purple' : 'blue'}>
        {enumLabel({ MENU: 'system:permission.tag.menu', BUTTON: 'system:permission.tag.button' }, node.ptype)}
      </Tag>
      <small style={{ color: 'var(--muted)' }}>{node.key}</small>
      <Perm code="permission:update">
        <Button size="small" type="link" icon={<EditOutlined />} onClick={(e) => { e.stopPropagation(); openEditCat(node.raw); }}>{t('action.edit')}</Button>
      </Perm>
      <Perm code="permission:delete">
        <Button size="small" type="link" danger icon={<DeleteOutlined />} onClick={(e) => { e.stopPropagation(); deleteCat(node.key); }}>{t('action.delete')}</Button>
      </Perm>
    </Space>
  );

  const catalogTab = (
    <Card
      title={t('system:permission.catalog.title')}
      extra={
        <Perm code="permission:create">
          <Button type="primary" icon={<PlusOutlined />} onClick={openAddCat}>{t('system:permission.catalog.add')}</Button>
        </Perm>
      }
    >
      <p style={{ color: 'var(--muted)', fontSize: 12 }}>{t('system:permission.catalog.hint')}</p>
      <Tree treeData={treeData} titleRender={catTitleRender} defaultExpandAll loading={catLoading} />
      <Modal
        title={catModal.editing ? t('system:permission.catalog.modal.titleEdit') : t('system:permission.catalog.modal.titleAdd')}
        open={catModal.open}
        onOk={submitCat}
        confirmLoading={catSaving}
        onCancel={() => setCatModal({ open: false, editing: null })}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          {!catModal.editing && (
            <Form.Item name="code" label={t('system:permission.catalog.col.code')} rules={[{ required: true, message: t('form.required', { label: t('system:permission.catalog.col.code') }) }]}>
              <Input placeholder={t('system:permission.catalog.modal.code')} />
            </Form.Item>
          )}
          <Form.Item name="name" label={t('system:permission.catalog.col.name')} rules={[{ required: true, message: t('form.required', { label: t('system:permission.catalog.col.name') }) }]}>
            <Input />
          </Form.Item>
          <Form.Item name="ptype" label={t('system:permission.catalog.col.ptype')} initialValue="MENU">
            <Select options={[{ label: t('system:permission.tag.menu'), value: 'MENU' }, { label: t('system:permission.tag.button'), value: 'BUTTON' }]} />
          </Form.Item>
          <Form.Item name="parentCode" label={t('system:permission.catalog.col.parent')}>
            <Select options={parentOptions} placeholder={t('system:permission.catalog.modal.parentPlaceholder')} allowClear />
          </Form.Item>
          <Form.Item name="path" label={t('system:permission.catalog.col.path')}>
            <Input placeholder="/orders" />
          </Form.Item>
          <Form.Item name="sortNo" label={t('system:permission.catalog.col.sort')} initialValue={0}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="icon" label={t('system:permission.catalog.col.icon')}>
            <Input placeholder="dashboard" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );

  // ---------- 角色权限矩阵（树形内联开关） ----------
  const { data: roles } = useFetch(() => api.get('/v1/admin/roles'));
  const [roleId, setRoleId] = useState(null);
  const [permMap, setPermMap] = useState({}); // code -> { canRead, canCreate, canUpdate, canDelete, canExport, btnEnabled }
  const [scope, setScope] = useState('SELF');
  const [saving, setSaving] = useState(false);

  const roleOpts = (roles || []).map((r) => ({ label: `${r.roleCode}（${r.nameI18n}）`, value: r.id }));

  useEffect(() => {
    if (!roleId) return;
    (async () => {
      const rp = await api.get(`/v1/admin/permissions/role/${roleId}`);
      const map = {};
      (rp || []).forEach((r) => {
        map[r.permissionCode] = {
          canRead: !!r.canRead, canCreate: !!r.canCreate, canUpdate: !!r.canUpdate,
          canDelete: !!r.canDelete, canExport: !!r.canExport,
          btnEnabled: !!(r.buttonsJson && r.buttonsJson !== '{}'),
        };
      });
      setPermMap(map);
      const roleObj = (roles || []).find((r) => r.id === roleId);
      setScope(roleObj?.dataScope || 'SELF');
    })();
  }, [roleId, roles]);

  const toggle = (code, field) =>
    setPermMap((m) => ({ ...m, [code]: { ...(m[code] || {}), [field]: !m[code]?.[field] } }));

  const save = async () => {
    if (!roleId) return;
    setSaving(true);
    try {
      const items = flatten(catalog).map((n) => {
        const p = permMap[n.code] || {};
        return {
          permissionCode: n.code,
          canRead: !!p.canRead, canCreate: !!p.canCreate, canUpdate: !!p.canUpdate,
          canDelete: !!p.canDelete, canExport: !!p.canExport,
          buttonsJson: p.btnEnabled ? '{"enabled":true}' : '{}',
        };
      });
      await api.put(`/v1/admin/permissions/role/${roleId}`, { items });
      await api.put(`/v1/admin/roles/${roleId}`, { dataScope: scope });
      message.success(t('system:permission.msg.saved'));
    } catch (e) { message.error(e.message); }
    finally { setSaving(false); }
  };

  const matrixSw = (code, field, label) => (
    <span style={{ marginLeft: 10, whiteSpace: 'nowrap' }}>
      <span style={{ color: 'var(--muted)', fontSize: 12 }}>{label}</span>
      <Switch size="small" checked={!!permMap[code]?.[field]} onChange={() => toggle(code, field)} />
    </span>
  );

  const matrixTitleRender = (node) => (
    <span style={{ display: 'inline-flex', alignItems: 'center', flexWrap: 'wrap' }}>
      <span>{node.title}</span>
      {node.ptype === 'BUTTON' && <Tag color="purple" style={{ marginLeft: 6 }}>{t('system:permission.tag.button')}</Tag>}
      {matrixSw(node.key, 'canRead', t('system:permission.col.read'))}
      {matrixSw(node.key, 'canCreate', t('system:permission.col.create'))}
      {matrixSw(node.key, 'canUpdate', t('system:permission.col.update'))}
      {matrixSw(node.key, 'canDelete', t('system:permission.col.delete'))}
      {matrixSw(node.key, 'canExport', t('system:permission.col.export'))}
      {node.ptype === 'BUTTON' && matrixSw(node.key, 'btnEnabled', t('system:permission.col.buttonEnabled'))}
    </span>
  );

  const matrixTab = (
    <Card title={t('system:permission.title')} extra={
      <Space>
        <Select placeholder={t('system:permission.selectRole')} style={{ width: 280 }} value={roleId} onChange={setRoleId} options={roleOpts} showSearch optionFilterProp="label" />
        <Select value={scope} style={{ width: 160 }} onChange={setScope} options={DATA_SCOPE.map((s) => ({ label: enumLabel(DATA_SCOPE_LABEL, s.value), value: s.value }))} />
        <Perm code="permission:update">
          <Button type="primary" loading={saving} onClick={save} disabled={!roleId}>{t('system:permission.save')}</Button>
        </Perm>
      </Space>
    }>
      <p style={{ color: 'var(--muted)', fontSize: 12 }}>
        {t('system:permission.hint')}
      </p>
      <Tree treeData={treeData} titleRender={matrixTitleRender} defaultExpandAll />
    </Card>
  );

  return (
    <Tabs
      defaultActiveKey="catalog"
      items={[
        { key: 'catalog', label: t('system:permission.tab.catalog'), children: catalogTab },
        { key: 'matrix', label: t('system:permission.tab.matrix'), children: matrixTab },
      ]}
    />
  );
}
