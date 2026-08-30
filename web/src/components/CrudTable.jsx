import React, { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, Popconfirm, message, Card } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import api from '../api';
import PageCard from './PageCard';
import { Perm } from './Perm';

/**
 * 通用后台 CRUD 表格组件。
 * 负责：拉取列表、新增(弹窗表单)、编辑(弹窗表单预填)、删除(二次确认)、错误/加载态。
 *
 * props:
 *  - title          页面标题
 *  - endpoint       相对 /api 的基路径，如 /v1/admin/fee/rules
 *  - columns        AntD 列定义（不含操作列，操作列自动追加）
 *  - fields         表单字段配置数组（见下方说明）
 *  - rowKey         主键字段，默认 'id'
 *  - editable       是否显示新增/编辑/删除，默认 true
 *  - query          列表查询参数对象（可选）
 *  - detailPath     若提供，点击行打开抽屉执行 GET `${endpoint}/${id}${detailPath}` 展示详情
 *  - extraRowActions (record) => ReactNode[]  行内额外操作按钮（如处置/仲裁/结算）
 *  - transformCreate (values) => body
 *  - transformUpdate (values, record) => body
 *  - perm            权限码：若提供，则「新增 / 编辑 / 删除」按钮整体按该码显隐（无权限仍渲染表格）
 *
 * fields 配置项额外支持：
 *  - showWhen: { field: 'x', in: ['A','B'] }  仅当字段 x 的当前值落在 in 列表时显示该字段（条件字段）
 *  - loadTransform: (v) => any                  从后端回填表单时对该字段值做转换（如 JSON 串 -> 数组）
 *
 * fields 配置项：{ name, label, type:'text'|'number'|'textarea'|'select'|'date', required,
 *                  options:[{label,value}], placeholder, disabled, initialValue, span(栅格) }
 */
export default function CrudTable({
  title,
  subtitle,
  endpoint,
  columns = [],
  fields = [],
  rowKey = 'id',
  editable = true,
  query,
  detailPath,
  extraRowActions,
  transformCreate,
  transformUpdate,
  perm,
}) {
  const { t, i18n } = useTranslation();
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null); // null=新增, record=编辑
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const [detail, setDetail] = useState(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get(endpoint, { params: query });
      setData(Array.isArray(res) ? res : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
    } finally {
      setLoading(false);
    }
  }, [endpoint, query, t, i18n.language]);

  useEffect(() => { load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    fields.forEach((f) => { if (f.initialValue != null) form.setFieldValue(f.name, f.initialValue); });
    setModalOpen(true);
  };

  const openEdit = (record) => {
    setEditing(record);
    form.resetFields();
    fields.forEach((f) => {
      const raw = record[f.name];
      const v = f.loadTransform && raw != null ? f.loadTransform(raw) : raw;
      form.setFieldValue(f.name, v == null ? undefined : v);
    });
    setModalOpen(true);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        const body = transformUpdate ? transformUpdate(values, editing) : values;
        await api.put(`${endpoint}/${editing[rowKey]}`, body);
        message.success(t('msg.saved'));
      } else {
        const body = transformCreate ? transformCreate(values) : values;
        await api.post(endpoint, body);
        message.success(t('msg.created'));
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
      await api.delete(`${endpoint}/${record[rowKey]}`);
      message.success(t('msg.deleted'));
      load();
    } catch (e) {
      message.error(t('msg.deleteFailed', { msg: e.message }));
    }
  };

  const openDetail = async (record) => {
    if (!detailPath) return;
    try {
      const res = await api.get(`${endpoint}/${record[rowKey]}${detailPath}`);
      setDetail(res);
      setDetailOpen(true);
    } catch (e) {
      message.error(t('msg.detailLoadFailed', { msg: e.message }));
    }
  };

  const actionColumn = editable || extraRowActions
    ? {
        title: t('table.actions'),
        key: '_actions',
        width: 200,
        render: (_, record) => (
          <Space size="small">
            {detailPath && <Button size="small" type="link" onClick={() => openDetail(record)}>{t('action.detail')}</Button>}
            {extraRowActions && extraRowActions(record)}
            {editable && (
              <Perm code={perm}>
                <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(record)}>{t('action.edit')}</Button>
              </Perm>
            )}
            {editable && (
              <Perm code={perm}>
                <Popconfirm
                  title={t('confirm.delete')}
                  onConfirm={() => remove(record)}
                  okText={t('confirm.deleteOk')}
                  cancelText={t('action.cancel')}
                >
                  <Button size="small" type="link" danger icon={<DeleteOutlined />}>{t('action.delete')}</Button>
                </Popconfirm>
              </Perm>
            )}
          </Space>
        ),
      }
    : null;

  return (
    <PageCard title={title} subtitle={subtitle}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>{t('action.refresh')}</Button>
        </Space>
        {editable && (
          <Perm code={perm}>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>{t('action.add')}</Button>
          </Perm>
        )}
      </div>
      <Table
        rowKey={rowKey}
        loading={loading}
        dataSource={data}
        columns={actionColumn ? [...columns, actionColumn] : columns}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        size="middle"
        scroll={{ x: 'max-content' }}
      />

      <Modal
        title={editing ? `${t('action.edit')} · ${title}` : `${t('action.add')} · ${title}`}
        open={modalOpen}
        onOk={submit}
        confirmLoading={submitting}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          {fields.map((f) => {
            const control = renderField(f, t);
            // 若字段声明了 perm，用 <Perm> 包裹该控件（如「父角色」仅特定权限可见）。
            const wrapped = f.perm ? <Perm code={f.perm}>{control}</Perm> : control;
            const fieldItem = (
              <Form.Item
                key={f.name}
                name={f.name}
                label={f.label}
                disabled={editing && f.disabledOnEdit}
                rules={f.required ? [{ required: true, message: t('form.required', { label: f.label }) }] : []}
                initialValue={f.initialValue}
              >
                {wrapped}
              </Form.Item>
            );
            // 条件字段：仅当依赖字段的当前值落在 in 列表时才渲染（如 dataScope=TYPE 才显示类型多选）。
            if (f.showWhen) {
              const { field: ctrlField, in: inValues } = f.showWhen;
              return (
                <Form.Item key={f.name} noStyle shouldUpdate>
                  {(form) => {
                    const val = form.getFieldValue(ctrlField);
                    return inValues.includes(val) ? fieldItem : null;
                  }}
                </Form.Item>
              );
            }
            return fieldItem;
          })}
        </Form>
      </Modal>

      <Modal title={t('table.detailTitle')} open={detailOpen} onCancel={() => setDetailOpen(false)} footer={null} width={640}>
        {detail ? (
          <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 480, overflow: 'auto' }}>
            {JSON.stringify(detail, null, 2)}
          </pre>
        ) : t('msg.loading')}
      </Modal>
    </PageCard>
  );
}

/**
 * 按字段类型渲染表单控件。
 * @param {Object} f 字段配置
 * @param {Function} t i18next 翻译函数（用于控件内置占位文案）
 * @returns {JSX.Element} 表单控件
 */
function renderField(f, t) {
  switch (f.type) {
    case 'number':
      return <InputNumber style={{ width: '100%' }} placeholder={f.placeholder} disabled={f.disabled} precision={f.precision ?? undefined} />;
    case 'textarea':
      return <Input.TextArea rows={3} placeholder={f.placeholder} disabled={f.disabled} />;
    case 'select': {
      const base = f.options || [];
      // excludeValues：静态数组或函数 (editing) => array，用于剔除非法可选项（如成环的父角色）。
      const excl = typeof f.excludeValues === 'function'
        ? f.excludeValues(editing)
        : (f.excludeValues || []);
      const opts = excl.length ? base.filter((o) => !excl.includes(o.value)) : base;
      return <Select options={opts} placeholder={f.placeholder || t('form.placeholderSelect')} allowClear disabled={f.disabled} mode={f.mode} />;
    }
    case 'date':
      return <Input placeholder={f.placeholder || t('form.datePlaceholder')} disabled={f.disabled} />;
    case 'text':
    default:
      return <Input placeholder={f.placeholder} disabled={f.disabled} />;
  }
}
