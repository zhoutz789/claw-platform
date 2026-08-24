import React from 'react';
import { Tabs, Tag } from 'antd';
import CrudTable from '../components/CrudTable';
import { BOOL_STR, DATA_SCOPE, DATA_SCOPE_LABEL } from '../enums';

const roleColumns = [
  { title: '角色编码', dataIndex: 'roleCode', width: 180 },
  { title: '名称(i18n key)', dataIndex: 'nameI18n', width: 220 },
  { title: '权限授予', dataIndex: 'grants', ellipsis: true },
  { title: '自动授予', dataIndex: 'autoGrant', width: 100, render: (v) => (v ? '是' : '否') },
  { title: '授予规则', dataIndex: 'grantRule', width: 160 },
  { title: '数据范围', dataIndex: 'dataScope', width: 130, render: (v) => <Tag>{DATA_SCOPE_LABEL[v] || v}</Tag> },
  { title: '状态', dataIndex: 'status', width: 110 },
];

const roleFields = [
  { name: 'code', label: '角色编码', required: true, placeholder: '如 STATION_MANAGER' },
  { name: 'nameI18n', label: '名称(i18n key)', required: true, placeholder: 'role.station_manager.name' },
  { name: 'grants', label: '权限(JSON)', type: 'textarea', placeholder: '["station:read","station:write"]' },
  { name: 'autoGrant', label: '是否自动授予', type: 'select', options: BOOL_STR, initialValue: false },
  { name: 'grantRule', label: '授予规则', placeholder: '如 注册即授予' },
  { name: 'dataScope', label: '数据范围', type: 'select', options: DATA_SCOPE, initialValue: 'SELF' },
  { name: 'status', label: '状态', type: 'select', options: [{ label: 'ACTIVE', value: 'ACTIVE' }, { label: 'INACTIVE', value: 'INACTIVE' }] },
];

const userRoleColumns = [
  { title: 'ID', dataIndex: 'id', width: 70 },
  { title: '用户ID', dataIndex: 'userId', width: 100 },
  { title: '用户名', dataIndex: 'userName', width: 140 },
  { title: '角色编码', dataIndex: 'roleCode', width: 180 },
  { title: '角色名', dataIndex: 'roleName', width: 180 },
  { title: '来源', dataIndex: 'source', width: 100 },
  { title: '授予时间', dataIndex: 'grantedAt', width: 180 },
];

const userRoleFields = [
  { name: 'userId', label: '用户ID', type: 'number', required: true },
  { name: 'roleCode', label: '角色编码', required: true, placeholder: '如 CONSUMER' },
];

export default function Roles() {
  return (
    <Tabs
      defaultActiveKey="roles"
      items={[
        {
          key: 'roles',
          label: '角色模板',
          children: (
            <CrudTable
              title="角色模板"
              subtitle="平台角色（权限包）的创建与维护"
              endpoint="/v1/admin/roles"
              columns={roleColumns}
              fields={roleFields}
              rowKey="id"
            />
          ),
        },
        {
          key: 'userRoles',
          label: '用户角色分配',
          children: (
            <CrudTable
              title="用户角色分配"
              subtitle="将角色授予具体用户"
              endpoint="/v1/admin/roles/user-roles"
              columns={userRoleColumns}
              fields={userRoleFields}
              rowKey="id"
            />
          ),
        },
      ]}
    />
  );
}
