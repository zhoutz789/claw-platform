import React, { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, Popconfirm, message, Card } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import api from '../api';
import PageCard from './PageCard';

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
}) {
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
      message.error(`加载失败：${e.message}`);
    } finally {
      setLoading(false);
    }
  }, [endpoint, query]);

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
      const v = record[f.name];
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
        message.success('已保存');
      } else {
        const body = transformCreate ? transformCreate(values) : values;
        await api.post(endpoint, body);
        message.success('已创建');
      }
      setModalOpen(false);
      load();
    } catch (e) {
      message.error(`操作失败：${e.message}`);
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record) => {
    try {
      await api.delete(`${endpoint}/${record[rowKey]}`);
      message.success('已删除');
      load();
    } catch (e) {
      message.error(`删除失败：${e.message}`);
    }
  };

  const openDetail = async (record) => {
    if (!detailPath) return;
    try {
      const res = await api.get(`${endpoint}/${record[rowKey]}${detailPath}`);
      setDetail(res);
      setDetailOpen(true);
    } catch (e) {
      message.error(`详情加载失败：${e.message}`);
    }
  };

  const actionColumn = editable || extraRowActions
    ? {
        title: '操作',
        key: '_actions',
        width: 200,
        render: (_, record) => (
          <Space size="small">
            {detailPath && <Button size="small" type="link" onClick={() => openDetail(record)}>详情</Button>}
            {extraRowActions && extraRowActions(record)}
            {editable && (
              <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>
            )}
            {editable && (
              <Popconfirm title="确认删除？" onConfirm={() => remove(record)} okText="删除" cancelText="取消">
                <Button size="small" type="link" danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            )}
          </Space>
        ),
      }
    : null;

  return (
    <PageCard title={title} subtitle={subtitle}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        </Space>
        {editable && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增</Button>
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
        title={editing ? `编辑 · ${title}` : `新增 · ${title}`}
        open={modalOpen}
        onOk={submit}
        confirmLoading={submitting}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          {fields.map((f) => (
            <Form.Item
              key={f.name}
              name={f.name}
              label={f.label}
              rules={f.required ? [{ required: true, message: `请输入${f.label}` }] : []}
              initialValue={f.initialValue}
            >
              {renderField(f)}
            </Form.Item>
          ))}
        </Form>
      </Modal>

      <Modal title="详情" open={detailOpen} onCancel={() => setDetailOpen(false)} footer={null} width={640}>
        {detail ? (
          <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 480, overflow: 'auto' }}>
            {JSON.stringify(detail, null, 2)}
          </pre>
        ) : '加载中…'}
      </Modal>
    </PageCard>
  );
}

function renderField(f) {
  switch (f.type) {
    case 'number':
      return <InputNumber style={{ width: '100%' }} placeholder={f.placeholder} disabled={f.disabled} precision={f.precision ?? undefined} />;
    case 'textarea':
      return <Input.TextArea rows={3} placeholder={f.placeholder} disabled={f.disabled} />;
    case 'select':
      return <Select options={f.options} placeholder={f.placeholder || '请选择'} allowClear disabled={f.disabled} />;
    case 'date':
      return <Input placeholder={f.placeholder || 'YYYY-MM-DD'} disabled={f.disabled} />;
    case 'text':
    default:
      return <Input placeholder={f.placeholder} disabled={f.disabled} />;
  }
}
