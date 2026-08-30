import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Tree, Button, Modal, Form, Input, Select, Space, message } from 'antd';
import { PlusOutlined, EditOutlined } from '@ant-design/icons';
import api from '../api';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';

/**
 * 部门管理（C3 数据范围维度锚点 · 部门层级）。
 * - 树形展示：消费 GET /v1/admin/departments/tree 返回的嵌套结构；
 * - 新增子部门：name + parent（可留空为根）；
 * - 简单编辑：更名 / 改父部门（PUT /v1/admin/departments/{id}）。
 */

// 后端 DepartmentTree -> antd Tree 节点（保留原始字段供编辑回填）。
function toTreeData(nodes) {
  return (nodes || []).map((n) => ({
    key: String(n.id),
    title: n.name,
    raw: n,
    children: toTreeData(n.children),
  }));
}

// 扁平化树，供「父部门」下拉与编辑时剔除自身后代防环。
function flatten(nodes, acc = []) {
  (nodes || []).forEach((n) => {
    acc.push(n);
    flatten(n.children, acc);
  });
  return acc;
}

function descendantIds(roles, rootId) {
  const childrenOf = new Map();
  roles.forEach((r) => {
    if (r.parentId != null) {
      if (!childrenOf.has(r.parentId)) childrenOf.set(r.parentId, []);
      childrenOf.get(r.parentId).push(r.id);
    }
  });
  const result = new Set();
  const stack = [rootId];
  while (stack.length) {
    const cur = stack.pop();
    const kids = childrenOf.get(cur) || [];
    for (const k of kids) {
      if (!result.has(k)) { result.add(k); stack.push(k); }
    }
  }
  return result;
}

export default function Departments() {
  const { t } = useTranslation();
  const [raw, setRaw] = useState([]);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const [modal, setModal] = useState({ open: false, editing: null });

  const load = async () => {
    setLoading(true);
    try {
      const data = await api.get('/v1/admin/departments/tree');
      setRaw(Array.isArray(data) ? data : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const treeData = useMemo(() => toTreeData(raw), [raw]);
  const flat = useMemo(() => flatten(raw), [raw]);

  const openAdd = (parentId = null) => {
    form.resetFields();
    form.setFieldValue('parentId', parentId);
    setModal({ open: true, editing: null });
  };

  const openEdit = (node) => {
    form.resetFields();
    form.setFieldValue('name', node.name);
    form.setFieldValue('parentId', node.parentId ?? null);
    setModal({ open: true, editing: node });
  };

  const submit = async () => {
    const values = await form.validateFields();
    const body = { name: values.name.trim(), parentId: values.parentId ?? null };
    try {
      if (modal.editing) {
        await api.put(`/v1/admin/departments/${modal.editing.id}`, body);
        message.success(t('msg.saved'));
      } else {
        await api.post('/v1/admin/departments', body);
        message.success(t('msg.created'));
      }
      setModal({ open: false, editing: null });
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    }
  };

  const parentOptions = flat
    .filter((d) => (modal.editing ? !descendantIds(flat, modal.editing.id).has(d.id) && d.id !== modal.editing.id : true))
    .map((d) => ({ label: `${d.orgCode ? d.orgCode + ' · ' : ''}${d.name}`, value: d.id }));

  const titleRender = (node) => (
    <Space>
      <span>{node.title}{node.raw?.orgCode ? ` (${node.raw.orgCode})` : ''}</span>
      <Perm code="department:create">
        <Button size="small" type="link" onClick={(e) => { e.stopPropagation(); openAdd(node.raw.id); }}>{t('action.add')}</Button>
      </Perm>
      <Perm code="department:update">
        <Button size="small" type="link" icon={<EditOutlined />} onClick={(e) => { e.stopPropagation(); openEdit(node.raw); }}>{t('action.edit')}</Button>
      </Perm>
    </Space>
  );

  return (
    <PageCard title={t('system:departments.title')} subtitle={t('system:departments.subtitle')}>
      <div style={{ marginBottom: 12 }}>
        <Perm code="department:create">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openAdd(null)}>{t('system:departments.addRoot')}</Button>
        </Perm>
      </div>
      <Tree
        treeData={treeData}
        titleRender={titleRender}
        defaultExpandAll
        loading={loading}
      />
      <Modal
        title={modal.editing ? t('action.edit') : t('system:departments.add')}
        open={modal.open}
        onOk={submit}
        onCancel={() => setModal({ open: false, editing: null })}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="name" label={t('system:departments.field.name')} rules={[{ required: true, message: t('form.required', { label: t('system:departments.field.name') }) }]}>
            <Input placeholder={t('system:departments.ph.name')} />
          </Form.Item>
          <Form.Item name="parentId" label={t('system:departments.field.parent')}>
            <Select options={parentOptions} placeholder={t('system:departments.ph.parent')} allowClear />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
