import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Drawer, Form, Input, Modal, Select, Space, Table, Tag,
} from 'antd';
import { PlusOutlined, ReloadOutlined, EditOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EMPTY, asArray, fmtTime } from '../components/supplyShared';
import {
  createRoleTemplate, flattenPermissionCatalog, listPermissionCatalog, listRoleTemplates,
  listTemplatePermissions, setTemplatePermissions, updateRoleTemplate,
} from '../api/supplyChain';
import { PRINCIPAL_TYPE } from '../enums';

/**
 * 角色模板管理页（增量 A · A2）。
 *
 * 4 类业务角色模板（厂家 / 服务站 / 用户 / 平台管理员）把约 170 个平铺权限码打包成
 * 「角色 → 功能模块 → 功能项」，授予时展开为账号的权限集合。
 * 对接后端 AdminRoleTemplateController（/api/v1/admin/role-templates）。
 */
export default function RoleTemplates() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();

  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [permOptions, setPermOptions] = useState([]);

  // 新增 / 编辑弹窗
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  // 权限配置抽屉
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [permRow, setPermRow] = useState(null);
  const [selectedPerms, setSelectedPerms] = useState([]);
  const [permLoading, setPermLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listRoleTemplates();
      setRows(asArray(d));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => { load(); }, [load]);

  // 权限码选项：后端 GET /admin/permissions/catalog 的全量目录树，拍平后供多选。
  useEffect(() => {
    let alive = true;
    listPermissionCatalog()
      .then((tree) => {
        if (alive) {
          const all = flattenPermissionCatalog(tree);
          // 菜单节点（menu:*）只用于菜单可见性，不作为模板可授予的业务权限码。
          setPermOptions(all.filter((o) => !String(o.value).startsWith('menu:')));
        }
      })
      .catch(() => { if (alive) setPermOptions([]); });
    return () => { alive = false; };
  }, []);

  const openCreate = () => {
    setEditing(null);
    setModalOpen(true);
    form.resetFields();
    form.setFieldsValue({ principalType: 'MANUFACTURER' });
  };

  const openEdit = (record) => {
    setEditing(record);
    setModalOpen(true);
    form.resetFields();
    // 后端 updateTemplate 只接收 name / description，code 与 principalType 置灰展示，不参与提交。
    form.setFieldsValue({ code: record.code, name: record.name, principalType: record.principalType, description: record.description });
  };

  const submit = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await updateRoleTemplate(editing.code, { name: v.name, description: v.description });
        message.success(t('supply:roleTemplates.msg.updated'));
      } else {
        await createRoleTemplate({
          code: v.code,
          name: v.name,
          principalType: v.principalType,
          description: v.description,
        });
        message.success(t('supply:roleTemplates.msg.created'));
      }
      setModalOpen(false);
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  const openPermissions = async (record) => {
    setPermRow(record);
    setDrawerOpen(true);
    setSelectedPerms([]);
    setPermLoading(true);
    try {
      const codes = await listTemplatePermissions(record.code);
      setSelectedPerms(asArray(codes));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
    } finally {
      setPermLoading(false);
    }
  };

  const savePermissions = async () => {
    if (!permRow) return;
    setPermLoading(true);
    try {
      await setTemplatePermissions(permRow.code, selectedPerms);
      message.success(t('supply:roleTemplates.msg.permissionsSaved'));
      setDrawerOpen(false);
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setPermLoading(false);
    }
  };

  const permOptionsWithSelected = useMemo(() => {
    const known = new Set(permOptions.map((o) => o.value));
    const extra = selectedPerms
      .filter((c) => !known.has(c))
      .map((c) => ({ label: c, value: c }));
    return [...permOptions, ...extra];
  }, [permOptions, selectedPerms]);

  const columns = [
    { title: t('supply:roleTemplates.col.id'), dataIndex: 'id', width: 80 },
    { title: t('supply:roleTemplates.col.code'), dataIndex: 'code', width: 180 },
    { title: t('supply:roleTemplates.col.name'), dataIndex: 'name', width: 180 },
    {
      title: t('supply:roleTemplates.col.principalType'),
      dataIndex: 'principalType',
      width: 130,
      render: (v) => (v ? <Tag color="blue">{t(`supply:enum.principalType.${v}`)}</Tag> : EMPTY),
    },
    { title: t('supply:roleTemplates.col.description'), dataIndex: 'description', ellipsis: true },
    {
      title: t('supply:roleTemplates.col.updatedAt'),
      dataIndex: 'updatedAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 220,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Perm code="role:template:manage">
            <Button
              size="small"
              type="link"
              icon={<SafetyCertificateOutlined />}
              onClick={() => openPermissions(r)}
            >
              {t('supply:roleTemplates.permissions')}
            </Button>
          </Perm>
          <Perm code="role:template:manage">
            <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(r)}>
              {t('action.edit')}
            </Button>
          </Perm>
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('supply:roleTemplates.title')}
      subtitle={t('supply:roleTemplates.subtitle')}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
          <Perm code="role:template:manage">
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              {t('supply:roleTemplates.create')}
            </Button>
          </Perm>
        </Space>
      }
    >
      {/* 「一键授予」后端本轮未暴露接口，显式说明，避免用户以为页面漏做 */}
      <Perm code="role:template:manage">
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('supply:roleTemplates.grantTodo')} />
      </Perm>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      <Modal
        title={editing ? t('supply:roleTemplates.edit') : t('supply:roleTemplates.create')}
        open={modalOpen}
        onOk={submit}
        confirmLoading={submitting}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
        width={520}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="code"
            label={t('supply:roleTemplates.field.code')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:roleTemplates.field.code') }) }]}
          >
            <Input disabled={Boolean(editing)} placeholder={t('supply:roleTemplates.ph.code')} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('supply:roleTemplates.field.name')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:roleTemplates.field.name') }) }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="principalType" label={t('supply:roleTemplates.field.principalType')}>
            <Select
              disabled={Boolean(editing)}
              options={PRINCIPAL_TYPE.map((o) => ({ value: o.value, label: t(`supply:enum.principalType.${o.value}`) }))}
            />
          </Form.Item>
          <Form.Item name="description" label={t('supply:roleTemplates.field.description')}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={`${t('supply:roleTemplates.permissions')} · ${permRow?.code || ''}`}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={720}
        extra={
          <Perm code="role:template:manage">
            <Button type="primary" loading={permLoading} onClick={savePermissions}>
              {t('supply:roleTemplates.savePermissions')}
            </Button>
          </Perm>
        }
      >
        <Perm code="role:template:manage">
          <Select
            mode="multiple"
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: '100%' }}
            placeholder={t('supply:roleTemplates.ph.permissionCodes')}
            loading={permLoading}
            value={selectedPerms}
            onChange={setSelectedPerms}
            options={permOptionsWithSelected}
            maxTagCount="responsive"
          />
        </Perm>
        <div style={{ color: '#999', marginTop: 12, fontSize: 12 }}>
          {t('supply:roleTemplates.field.permissionCodes')}：{selectedPerms.length}
        </div>
      </Drawer>
    </PageCard>
  );
}
