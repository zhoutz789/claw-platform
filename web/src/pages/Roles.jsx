import { useState, useEffect, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Tabs, Tag, message } from 'antd';
import CrudTable from '../components/CrudTable';
import api from '../api';
import { BOOL_STR, DATA_SCOPE, DATA_SCOPE_LABEL, DATA_SCOPE_TYPE_OPTIONS, enumLabel } from '../enums';

/**
 * 计算某角色的全部后代 id（含其自身由调用方追加）。
 * 用于在「父角色」下拉中剔除自己与后代，防止形成环（后端亦有逻辑防环兜底）。
 * @param {Array} roles 扁平角色列表（含 id / parentId）
 * @param {number} rootId 起点角色 id
 * @returns {Set<number>} 后代 id 集合
 */
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
      if (!result.has(k)) {
        result.add(k);
        stack.push(k);
      }
    }
  }
  return result;
}

/** 把后端返回的数组 / JSON 串统一解析为前端多选所需的数组。 */
const parseArr = (v) => {
  if (Array.isArray(v)) return v;
  if (typeof v === 'string' && v) {
    try {
      const a = JSON.parse(v);
      return Array.isArray(a) ? a : [];
    } catch {
      return [];
    }
  }
  return [];
};

/** 把前端多选数组序列化为后端存储用的 JSON 串；空值统一为 '[]'。 */
const toJson = (v) => {
  if (Array.isArray(v)) return JSON.stringify(v);
  if (typeof v === 'string' && v) return v;
  return '[]';
};

/** 拍平部门树为 {label,value} 下拉项（保留层级前缀）。 */
const flattenDepts = (nodes, acc = []) => {
  (nodes || []).forEach((n) => {
    acc.push({ label: `${n.orgCode ? n.orgCode + ' · ' : ''}${n.name}`, value: n.id });
    if (n.children) flattenDepts(n.children, acc);
  });
  return acc;
};

export default function Roles() {
  const { t } = useTranslation();
  const [allRoles, setAllRoles] = useState([]);
  const [deptTree, setDeptTree] = useState([]);

  // 拉取全部角色，供「父角色」下拉与列表的父名称展示使用。
  useEffect(() => {
    let alive = true;
    api.get('/v1/admin/roles')
      .then((list) => { if (alive) setAllRoles(Array.isArray(list) ? list : []); })
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  // CUSTOM 数据范围需要部门树作为可选项（GET /departments/tree）。
  useEffect(() => {
    let alive = true;
    api.get('/v1/admin/departments/tree')
      .then((tree) => { if (alive) setDeptTree(Array.isArray(tree) ? tree : []); })
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  const parentOptions = useMemo(
    () => allRoles.map((r) => ({ label: r.nameI18n || r.roleCode, value: r.id })),
    [allRoles]
  );
  const deptOptions = useMemo(() => flattenDepts(deptTree), [deptTree]);

  // 角色数据范围提交前统一序列化：数组 -> JSON 串；隐藏的条件字段清空为 '[]'。
  const serializeScope = useCallback((values) => ({
    ...values,
    dataScopeTypes: values.dataScope === 'TYPE' ? toJson(values.dataScopeTypes) : '[]',
    dataRuleIds: values.dataScope === 'CUSTOM' ? toJson(values.dataRuleIds) : '[]',
  }), []);

  const roleColumns = (tt) => [
    { title: tt('system:roles.col.roleCode'), dataIndex: 'roleCode', width: 180 },
    { title: tt('system:roles.col.nameI18n'), dataIndex: 'nameI18n', width: 220 },
    { title: tt('system:roles.col.parent'), dataIndex: 'parentId', width: 160, render: (v) => {
      if (v == null) return '—';
      const p = allRoles.find((r) => r.id === v);
      return p ? (p.nameI18n || p.roleCode) : `#${v}`;
    } },
    { title: tt('system:roles.col.grants'), dataIndex: 'grants', ellipsis: true },
    { title: tt('system:roles.col.autoGrant'), dataIndex: 'autoGrant', width: 100, render: (v) => (v ? tt('action.yes') : tt('action.no')) },
    { title: tt('system:roles.col.grantRule'), dataIndex: 'grantRule', width: 160 },
    { title: tt('system:roles.col.dataScope'), dataIndex: 'dataScope', width: 130, render: (v) => <Tag>{enumLabel(DATA_SCOPE_LABEL, v)}</Tag> },
    { title: tt('system:roles.col.status'), dataIndex: 'status', width: 110 },
  ];

  const roleFields = (tt) => [
    { name: 'code', label: tt('system:roles.field.code'), required: true, placeholder: tt('system:roles.ph.code'), disabledOnEdit: true },
    { name: 'nameI18n', label: tt('system:roles.field.nameI18n'), required: true, placeholder: tt('system:roles.ph.nameI18n') },
    { name: 'grants', label: tt('system:roles.field.grants'), type: 'textarea', placeholder: tt('system:roles.ph.grants') },
    { name: 'autoGrant', label: tt('system:roles.field.autoGrant'), type: 'select', options: BOOL_STR, initialValue: false },
    { name: 'grantRule', label: tt('system:roles.field.grantRule'), placeholder: tt('system:roles.ph.grantRule') },
    {
      name: 'dataScope', label: tt('system:roles.field.dataScope'), type: 'select', options: DATA_SCOPE, initialValue: 'SELF',
      perm: 'role:update',
    },
    {
      // TYPE 数据范围：选取可看的业务类型集合（与 V40 权限资源码一致）。
      name: 'dataScopeTypes', label: tt('system:roles.field.dataScopeTypes'), type: 'select', mode: 'multiple',
      options: DATA_SCOPE_TYPE_OPTIONS, initialValue: [], loadTransform: parseArr,
      showWhen: { field: 'dataScope', in: ['TYPE'] }, perm: 'role:update',
    },
    {
      // CUSTOM 数据范围：选取可看的部门（数据来自 GET /departments/tree）。
      name: 'dataRuleIds', label: tt('system:roles.field.dataRuleIds'), type: 'select', mode: 'multiple',
      options: deptOptions, initialValue: [], loadTransform: parseArr,
      showWhen: { field: 'dataScope', in: ['CUSTOM'] }, perm: 'role:update',
    },
    {
      name: 'parentId',
      label: tt('system:roles.field.parentId'),
      type: 'select',
      options: parentOptions,
      placeholder: tt('system:roles.ph.parentId'),
      // 排除「自己 + 自己的后代」，防止角色继承成环（#9）。
      excludeValues: (editing) => {
        if (!editing) return [];
        const ids = descendantIds(allRoles, editing.id);
        ids.add(editing.id);
        return [...ids];
      },
      // 用前端权限内核组件包裹：无 role:update 权限的用户看不到该控件。
      perm: 'role:update',
    },
    { name: 'status', label: tt('system:roles.field.status'), type: 'select', options: [{ label: 'ACTIVE', value: 'ACTIVE' }, { label: 'INACTIVE', value: 'INACTIVE' }] },
  ];

  const userRoleColumns = (tt) => [
    { title: tt('system:roles.col.id'), dataIndex: 'id', width: 70 },
    { title: tt('system:roles.col.userId'), dataIndex: 'userId', width: 100 },
    { title: tt('system:roles.col.userName'), dataIndex: 'userName', width: 140 },
    { title: tt('system:roles.col.roleCode'), dataIndex: 'roleCode', width: 180 },
    { title: tt('system:roles.col.roleName'), dataIndex: 'roleName', width: 180 },
    { title: tt('system:roles.col.source'), dataIndex: 'source', width: 100 },
    { title: tt('system:roles.col.grantedAt'), dataIndex: 'grantedAt', width: 180 },
  ];

  const userRoleFields = (tt) => [
    { name: 'userId', label: tt('system:roles.field.userId'), type: 'number', required: true },
    { name: 'roleCode', label: tt('system:roles.field.roleCode'), required: true, placeholder: tt('system:roles.ph.code') },
  ];

  return (
    <Tabs
      defaultActiveKey="roles"
      items={[
        {
          key: 'roles',
          label: t('system:roles.tab.roles'),
          children: (
            <CrudTable
              title={t('system:roles.roles.title')}
              subtitle={t('system:roles.roles.subtitle')}
              endpoint="/v1/admin/roles"
              columns={roleColumns(t)}
              fields={roleFields(t)}
              rowKey="id"
              perm="role:update"
              transformCreate={serializeScope}
              transformUpdate={serializeScope}
            />
          ),
        },
        {
          key: 'userRoles',
          label: t('system:roles.tab.userRoles'),
          children: (
            <CrudTable
              title={t('system:roles.userRoles.title')}
              subtitle={t('system:roles.userRoles.subtitle')}
              endpoint="/v1/admin/roles/user-roles"
              columns={userRoleColumns(t)}
              fields={userRoleFields(t)}
              rowKey="id"
            />
          ),
        },
      ]}
    />
  );
}
