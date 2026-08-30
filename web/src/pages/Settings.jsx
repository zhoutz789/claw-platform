import { useTranslation } from 'react-i18next';
import { Descriptions, Card } from 'antd';
import CrudTable from '../components/CrudTable';
import { BOOL_STR } from '../enums';
import { useFetch } from '../hooks';
import api from '../api';

// 列定义与表单字段含文案，做成工厂函数以便随语言变化重建。
const columns = (t) => [
  { title: t('system:settings.col.configKey'), dataIndex: 'configKey', width: 200 },
  { title: t('system:settings.col.configValue'), dataIndex: 'configValue', width: 220, ellipsis: true },
  { title: t('system:settings.col.category'), dataIndex: 'category', width: 120 },
  { title: t('system:settings.col.description'), dataIndex: 'description', ellipsis: true },
  { title: t('system:settings.col.dataType'), dataIndex: 'dataType', width: 110 },
  {
    title: t('system:settings.col.editable'),
    dataIndex: 'editable',
    width: 90,
    render: (v) => (v ? t('action.yes') : t('action.no')),
  },
];

// 后端按 configKey 路由（PUT/DELETE /config/{key}），故 rowKey 用 configKey。
const fields = (t) => [
  { name: 'configKey', label: t('system:settings.field.configKey'), required: true, placeholder: t('system:settings.ph.configKey') },
  { name: 'configValue', label: t('system:settings.field.configValue'), required: true },
  { name: 'category', label: t('system:settings.field.category'), placeholder: t('system:settings.ph.category') },
  { name: 'description', label: t('system:settings.field.description'), type: 'textarea' },
  {
    name: 'dataType',
    label: t('system:settings.field.dataType'),
    type: 'select',
    options: [
      { label: 'STRING', value: 'STRING' },
      { label: 'NUMBER', value: 'NUMBER' },
      { label: 'BOOLEAN', value: 'BOOLEAN' },
      { label: 'JSON', value: 'JSON' },
    ],
  },
  {
    name: 'editable',
    label: t('system:settings.field.editable'),
    type: 'select',
    options: BOOL_STR,
    initialValue: true,
  },
];

export default function Settings() {
  const { t, i18n } = useTranslation();
  // 拉取平台配置用于顶部「基础信息」卡片（配置项数量 / 分类数）。
  const { data: configList } = useFetch(() => api.get('/v1/admin/settings/config'));
  const configs = Array.isArray(configList) ? configList : [];
  const langName = { zh: '中文', en: 'English', km: 'ខ្មែរ' }[i18n.language] || i18n.language;
  const categoryCount = new Set(configs.map((c) => c.category)).size;

  return (
    <>
      <Card title={t('system:settings.platform.title')} style={{ marginBottom: 16 }}>
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label={t('system:settings.platform.brand')}>{t('app.brand')}</Descriptions.Item>
          <Descriptions.Item label={t('system:settings.platform.language')}>{langName}</Descriptions.Item>
          <Descriptions.Item label={t('system:settings.platform.configCount')}>{configs.length}</Descriptions.Item>
          <Descriptions.Item label={t('system:settings.platform.category')}>{categoryCount}</Descriptions.Item>
        </Descriptions>
        <p style={{ color: 'var(--muted)', fontSize: 12, marginTop: 12 }}>
          {t('system:settings.platform.desc')}
        </p>
      </Card>
      <CrudTable
        title={t('system:settings.title')}
        subtitle={t('system:settings.subtitle')}
        endpoint="/v1/admin/settings/config"
        columns={columns(t)}
        fields={fields(t)}
        rowKey="configKey"
        perm="setting:update"
      />
    </>
  );
}
