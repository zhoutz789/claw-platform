import CrudTable from '../components/CrudTable';
import { BOOL_STR } from '../enums';

const columns = [
  { title: '配置键', dataIndex: 'configKey', width: 200 },
  { title: '配置值', dataIndex: 'configValue', width: 220, ellipsis: true },
  { title: '分类', dataIndex: 'category', width: 120 },
  { title: '说明', dataIndex: 'description', ellipsis: true },
  { title: '数据类型', dataIndex: 'dataType', width: 110 },
  {
    title: '可编辑', dataIndex: 'editable', width: 90,
    render: (v) => (v ? '是' : '否'),
  },
];

// 后端按 configKey 路由（PUT/DELETE /config/{key}），故 rowKey 用 configKey。
const fields = [
  { name: 'configKey', label: '配置键', required: true, placeholder: '如 site.title' },
  { name: 'configValue', label: '配置值', required: true },
  { name: 'category', label: '分类', placeholder: 'UI / BIZ / RISK' },
  { name: 'description', label: '说明', type: 'textarea' },
  { name: 'dataType', label: '数据类型', type: 'select', options: [{ label: 'STRING', value: 'STRING' }, { label: 'NUMBER', value: 'NUMBER' }, { label: 'BOOLEAN', value: 'BOOLEAN' }, { label: 'JSON', value: 'JSON' }] },
  { name: 'editable', label: '是否可编辑', type: 'select', options: BOOL_STR, initialValue: true },
];

export default function Settings() {
  return (
    <CrudTable
      title="系统配置"
      subtitle="平台级参数（站点标题、风控阈值开关等）的维护"
      endpoint="/v1/admin/settings/config"
      columns={columns}
      fields={fields}
      rowKey="configKey"
    />
  );
}
