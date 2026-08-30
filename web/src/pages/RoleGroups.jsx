import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Button, Drawer, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag,
} from 'antd';
import { PlusOutlined, ReloadOutlined, EditOutlined, DeleteOutlined, ApartmentOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EMPTY, asArray, fmtTime } from '../components/supplyShared';
import {
  addGroupTemplate, createRoleGroup, deleteRoleGroup, listGroupTemplates, listRoleGroups,
  listRoleTemplates, removeGroupTemplate, updateRoleGroup,
} from '../api/supplyChain';

/**
 * 角色组管理页（增量 A · A3）。
 *
 * 角色组是「系统管理第 6 项能力」的载体：一组聚合多个角色模板，用于权限的批量治理。
 * 对接后端 AdminRoleGroupController（/api/v1/admin/role-groups）。
 */
export default function RoleGroups() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();

  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [templateOptions, setTemplateOptions] = useState([]);

  // 新增 / 编辑弹窗
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  // 关联模板抽屉
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [groupRow, setGroupRow] = useState(null);
  const [templates, setTemplates] = useState([]);
  const [tmplLoading, setTmplLoading] = useState(false);
  const [pickedTemplate, setPickedTemplate] = useState(undefined);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listRoleGroups();
      setRows(asArray(d));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => { load(); }, [load]);

  // 模板下拉：GET /admin/role-templates
  useEffect(() => {
    let alive = true;
    listRoleTemplates()
      .then((list) => {
        if (alive) {
          setTemplateOptions(asArray(list).map((x) => ({
            label: `${x.name || x.code}${x.code ? `（${x.code}）` : ''}`,
            value: x.code,
          })));
        }
      })
      .catch(() => { if (alive) setTemplateOptions([]); });
    return () => { alive = false; };
  }, []);

  const openCreate = () => {
    setEditing(null);
    setModalOpen(true);
    form.resetFields();
  };

  const openEdit = (record) => {
    setEditing(record);
    setModalOpen(true);
    form.resetFields();
    form.setFieldsValue({ code: record.code, name: record.name, description: record.description });
  };

  const submit = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await updateRoleGroup(editing.code, { name: v.name, description: v.description });
        message.success(t('supply:roleGroups.msg.updated'));
      } else {
        await createRoleGroup({ code: v.code, name: v.name, description: v.description });
        message.success(t('supply:roleGroups.msg.created'));
      }
      setModalOpen(false);
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record) => {
    try {
      await deleteRoleGroup(record.code);
      message.success(t('supply:roleGroups.msg.deleted'));
      load();
    } catch (e) {
      message.error(t('msg.deleteFailed', { msg: e.message }));
    }
  };

  const openTemplates = async (record) => {
    setGroupRow(record);
    setDrawerOpen(true);
    setTemplates([]);
    setTmplLoading(true);
    try {
      const codes = await listGroupTemplates(record.code);
      setTemplates(asArray(codes));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
    } finally {
      setTmplLoading(false);
    }
  };

  const addTemplate = async () => {
    if (!pickedTemplate) {
      message.warning(t('supply:roleGroups.msg.needTemplate'));
      return;
    }
    setTmplLoading(true);
    try {
      await addGroupTemplate(groupRow.code, pickedTemplate);
      const codes = await listGroupTemplates(groupRow.code);
      setTemplates(asArray(codes));
      setPickedTemplate(undefined);
      message.success(t('supply:roleGroups.msg.templateAdded'));
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setTmplLoading(false);
    }
  };

  const removeTemplate = async (templateCode) => {
    setTmplLoading(true);
    try {
      await removeGroupTemplate(groupRow.code, templateCode);
      const codes = await listGroupTemplates(groupRow.code);
      setTemplates(asArray(codes));
      message.success(t('supply:roleGroups.msg.templateRemoved'));
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setTmplLoading(false);
    }
  };

  const columns = [
    { title: t('supply:roleGroups.col.id'), dataIndex: 'id', width: 80 },
    { title: t('supply:roleGroups.col.code'), dataIndex: 'code', width: 200 },
    { title: t('supply:roleGroups.col.name'), dataIndex: 'name', width: 180 },
    { title: t('supply:roleGroups.col.description'), dataIndex: 'description', ellipsis: true },
    { title: t('supply:roleGroups.col.createdBy'), dataIndex: 'createdBy', width: 100 },
    {
      title: t('supply:roleGroups.col.createdAt'),
      dataIndex: 'createdAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 260,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Perm code="role:group:manage">
            <Button size="small" type="link" icon={<ApartmentOutlined />} onClick={() => openTemplates(r)}>
              {t('supply:roleGroups.templates')}
            </Button>
          </Perm>
          <Perm code="role:group:manage">
            <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(r)}>
              {t('action.edit')}
            </Button>
          </Perm>
          <Perm code="role:group:manage">
            <Popconfirm
              title={t('confirm.delete')}
              okText={t('confirm.deleteOk')}
              cancelText={t('action.cancel')}
              onConfirm={() => remove(r)}
            >
              <Button size="small" type="link" danger icon={<DeleteOutlined />}>{t('action.delete')}</Button>
            </Popconfirm>
          </Perm>
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('supply:roleGroups.title')}
      subtitle={t('supply:roleGroups.subtitle')}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
          <Perm code="role:group:manage">
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              {t('supply:roleGroups.create')}
            </Button>
          </Perm>
        </Space>
      }
    >
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
        title={editing ? t('supply:roleGroups.edit') : t('supply:roleGroups.create')}
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
            label={t('supply:roleGroups.field.code')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:roleGroups.field.code') }) }]}
          >
            <Input disabled={Boolean(editing)} placeholder={t('supply:roleGroups.ph.code')} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('supply:roleGroups.field.name')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:roleGroups.field.name') }) }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="description" label={t('supply:roleGroups.field.description')}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={`${t('supply:roleGroups.templates')} · ${groupRow?.code || ''}`}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={620}
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Perm code="role:group:manage">
            <Space wrap>
              <Select
                showSearch
                optionFilterProp="label"
                allowClear
                style={{ width: 300 }}
                placeholder={t('supply:roleGroups.field.templateCode')}
                options={templateOptions}
                value={pickedTemplate}
                onChange={setPickedTemplate}
              />
              <Button type="primary" loading={tmplLoading} onClick={addTemplate}>
                {t('supply:roleGroups.addTemplate')}
              </Button>
            </Space>
          </Perm>
          <Space wrap>
            {templates.length === 0 && <span style={{ color: '#999' }}>{EMPTY}</span>}
            {templates.map((code) => (
              <Tag
                key={code}
                closable={false}
                color="blue"
                style={{ padding: '4px 8px' }}
              >
                {code}
                <Perm code="role:group:manage">
                  <Button
                    type="link"
                    size="small"
                    danger
                    style={{ marginLeft: 4, padding: 0, height: 'auto' }}
                    loading={tmplLoading}
                    onClick={() => removeTemplate(code)}
                  >
                    {t('supply:roleGroups.remove')}
                  </Button>
                </Perm>
              </Tag>
            ))}
          </Space>
        </Space>
      </Drawer>
    </PageCard>
  );
}
