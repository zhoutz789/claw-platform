import { useState } from 'react';
import {
  Table, Tag, Card, Space, Alert, Button, Modal, Form, Input, Select, Typography, message, Empty,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { getTemplates, setTemplateFields, genFieldKey } from '../mock/templates';

const { Text, Paragraph, Title } = Typography;

// 字段类型选项（数字 / 文本 / 单选 / 日期 / 布尔 + 单位 + 示例）
const FIELD_TYPES = [
  { label: '数字 (number)', value: 'number' },
  { label: '文本 (text)', value: 'text' },
  { label: '单选 (select)', value: 'select' },
  { label: '日期 (date)', value: 'date' },
  { label: '布尔 (boolean)', value: 'boolean' },
];

// 产品模板（绑定数据类别 / 增字段 / 数据映射）：复用 ProductIot「产品模板」Tab 的字段增删改逻辑，
// 本地覆盖层（localStorage，见 mock/templates）持久化；并新增「数据映射」说明区。
export default function ProductTemplate() {
  const [templates, setTemplates] = useState(() => getTemplates());
  const [tplId, setTplId] = useState(null);
  const [fieldModal, setFieldModal] = useState({ open: false, tplId: null, editing: null });
  const [fieldForm] = Form.useForm();

  const tplOf = (id) => templates.find((t) => t.id === id);
  const tpl = tplOf(tplId);

  // 模板字段本地持久化（本地建模辅助，尚未接入后端）
  const saveFields = (id, fields) => { setTemplateFields(id, fields); setTemplates(getTemplates()); };
  const openAddField = (id) => { fieldForm.resetFields(); setFieldModal({ open: true, tplId: id, editing: null }); };
  const openEditField = (id, f) => { fieldForm.setFieldsValue(f); setFieldModal({ open: true, tplId: id, editing: f.key }); };
  const submitField = () => {
    fieldForm.validateFields().then((v) => {
      const cur = tplOf(fieldModal.tplId);
      const fields = [...(cur?.fields || [])];
      if (fieldModal.editing) {
        const i = fields.findIndex((f) => f.key === fieldModal.editing);
        if (i >= 0) fields[i] = { ...fields[i], ...v };
      } else {
        fields.push({ key: genFieldKey(), ...v });
      }
      saveFields(fieldModal.tplId, fields);
      setFieldModal({ open: false, tplId: null, editing: null });
      message.success(fieldModal.editing ? '字段已更新（已持久化）' : '字段已新增（已持久化）');
    });
  };
  const removeField = (id, key) => {
    const cur = tplOf(id);
    saveFields(id, (cur?.fields || []).filter((f) => f.key !== key));
    message.success('字段已删除');
  };

  return (
    <PageCard title="产品模板（绑定数据类别 / 增字段 / 数据映射）">
      <Alert type="info" showIcon style={{ marginBottom: 14 }}
        message="产品模板为「类」级建模辅助：定义设备的共用字段类型（数字/文本/单选/日期/布尔 + 单位 + 示例）。字段增删改保存在本地覆盖层（localStorage），尚未接入后端持久化。" />

      <Table
        rowKey="id" pagination={false}
        dataSource={templates}
        columns={[
          { title: '模板名称', dataIndex: 'name', render: (v, r) => <a onClick={() => setTplId(tplId === r.id ? null : r.id)}>{v}</a> },
          { title: '编码', dataIndex: 'code' },
          { title: '字段数', render: (_, r) => r.fields.length },
          { title: '更新', dataIndex: 'updatedAt' },
        ]}
      />

      {tpl && (
        <Card style={{ marginTop: 14 }} title={`${tpl.name} · 字段定义（本地可自定义·未持久化）`}
          extra={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openAddField(tpl.id)}>新增字段</Button>}>
          <p style={{ color: 'var(--muted)', marginTop: -4 }}>模板为「类」，定义设备的共用字段类型；点「新增字段」可扩展建模。当前为本地辅助，接真实后端后由后端落库。</p>
          <Table rowKey="key" pagination={false} size="small"
            dataSource={tpl.fields}
            columns={[
              { title: '字段', dataIndex: 'label' },
              { title: '类型', dataIndex: 'type', render: (v) => <Tag>{v}</Tag> },
              { title: '单位', dataIndex: 'unit' },
              { title: '示例', dataIndex: 'example' },
              { title: '操作', render: (_, r) => (
                <Space>
                  <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEditField(tpl.id, r)}>编辑</Button>
                  <Button size="small" type="link" danger icon={<DeleteOutlined />} onClick={() => removeField(tpl.id, r.key)}>删除</Button>
                </Space>
              ) },
            ]}
          />

          <div style={{ marginTop: 16, padding: 16, border: '1px dashed var(--line)', borderRadius: 8, background: '#fafcff' }}>
            <Title level={5} style={{ marginTop: 0 }}>数据映射（说明）</Title>
            <Paragraph type="secondary" style={{ marginBottom: 0 }}>
              产品绑定该类产品的相关数据类别 → 增字段 → 绑定线下设备上传 / 实时数据，形成「数据映射」与「产品模板」。
              例如：电动车产品绑定「电池循环次数 / 续航里程 / 实时定位」等数据类别，厂家在模板中增字段后，线下设备按字段上传遥测，
              后台即可按模板聚合展示设备孪生详情。数据映射后端待接入。
            </Paragraph>
            <Alert type="warning" showIcon style={{ marginTop: 8 }}
              message="数据映射后端待接入：当前仅展示说明与本地字段建模，真实数据类别绑定与设备遥测接入待后端补齐。" />
          </div>
        </Card>
      )}

      <Modal
        title={fieldModal.editing ? '编辑字段' : '新增字段（自定义建模·本地）'}
        open={fieldModal.open}
        onOk={submitField}
        onCancel={() => setFieldModal({ open: false, tplId: null, editing: null })}
        okText="保存"
        cancelText="取消"
      >
        <Form form={fieldForm} layout="vertical" initialValues={{ type: 'number' }}>
          <Form.Item label="字段名称" name="label" rules={[{ required: true, message: '请输入字段名称' }]}>
            <Input placeholder="如 电池循环次数" />
          </Form.Item>
          <Form.Item label="字段类型" name="type" rules={[{ required: true }]}>
            <Select options={FIELD_TYPES} />
          </Form.Item>
          <Form.Item label="单位" name="unit"><Input placeholder="如 kWh / km / 次（可空）" /></Form.Item>
          <Form.Item label="示例值" name="example"><Input placeholder="用于建模示例" /></Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
