import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Card, Form, InputNumber, Popconfirm, Select, Space, Table, Tabs, Tag,
} from 'antd';
import { SearchOutlined, LinkOutlined, DisconnectOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EMPTY, asArray, fmtTime, useSupplyOptions } from '../components/supplyShared';
import {
  bindPrincipal, listBindingsByPrincipal, listBindingsByUser, unbindPrincipal,
} from '../api/supplyChain';
import { BINDING_PRINCIPAL_TYPE } from '../enums';

/**
 * 主体绑定页（增量 A · A1/A3）。
 *
 * 把登录账号与业务主体（厂家 / 服务站）做 1:1 绑定（Q5 默认严格 1:1），
 * 绑定即授予对应业务角色包，也是增量 B 各页 @DataScope 解析数据范围的入口。
 * 对接后端 AdminPrincipalBindingController（/api/v1/admin/principal-bindings）。
 */
export default function PrincipalBindings() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const { manufacturerOptions, stationOptions, manufacturerName, stationName } = useSupplyOptions();

  const [tab, setTab] = useState('byUser');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [userForm] = Form.useForm();
  const [principalForm] = Form.useForm();

  const queryByUser = async () => {
    const v = await userForm.validateFields().catch(() => null);
    if (!v) return;
    setLoading(true);
    try {
      const d = await listBindingsByUser(v.userId);
      setRows(asArray(d));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  const queryByPrincipal = async () => {
    const v = await principalForm.validateFields().catch(() => null);
    if (!v) return;
    setLoading(true);
    try {
      const d = await listBindingsByPrincipal(v.principalType, v.principalId);
      setRows(asArray(d));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  const bind = async () => {
    const v = await userForm.validateFields().catch(() => null);
    if (!v) return;
    setSubmitting(true);
    try {
      await bindPrincipal({
        userId: v.userId,
        principalType: v.principalType,
        principalId: v.principalId,
      });
      message.success(t('supply:principalBindings.msg.bound'));
      await queryByUser();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  const unbind = async (record) => {
    try {
      await unbindPrincipal(record.userId, record.principalType);
      message.success(t('supply:principalBindings.msg.unbound'));
      if (tab === 'byUser') await queryByUser();
      else await queryByPrincipal();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    }
  };

  /** 主体名称展示：按主体类型分别解析厂家 / 服务站名称。 */
  const principalName = useCallback((type, id) => {
    if (id == null) return EMPTY;
    return type === 'MANUFACTURER' ? manufacturerName(id) : stationName(id);
  }, [manufacturerName, stationName]);

  const columns = [
    { title: t('supply:principalBindings.col.id'), dataIndex: 'id', width: 80 },
    { title: 'User ID', dataIndex: 'userId', width: 110 },
    {
      title: t('supply:principalBindings.col.principalType'),
      dataIndex: 'principalType',
      width: 130,
      render: (v) => (v ? <Tag color="blue">{t(`supply:enum.principalType.${v}`)}</Tag> : EMPTY),
    },
    {
      title: t('supply:principalBindings.col.principalId'),
      dataIndex: 'principalId',
      width: 200,
      render: (v, r) => `${principalName(r.principalType, v)} (#${v ?? EMPTY})`,
    },
    {
      title: t('supply:common.createdAt'),
      dataIndex: 'createdAt',
      width: 160,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 120,
      render: (_, r) => (
        <Perm any={['mfg:bind:manage', 'station:bind:manage']}>
          <Popconfirm
            title={t('supply:principalBindings.unbind')}
            okText={t('action.ok')}
            cancelText={t('action.cancel')}
            onConfirm={() => unbind(r)}
          >
            <Button size="small" type="link" danger icon={<DisconnectOutlined />}>
              {t('supply:principalBindings.unbind')}
            </Button>
          </Popconfirm>
        </Perm>
      ),
    },
  ];

  const boundPrincipalType = Form.useWatch('principalType', userForm);

  const byUserTab = (
    <div>
      <Card size="small" title={t('supply:principalBindings.bind')} style={{ marginBottom: 12 }}>
        <Form form={userForm} layout="vertical">
          <Space wrap align="start">
            <Form.Item
              name="userId"
              label="User ID"
              rules={[{ required: true, message: t('form.required', { label: 'User ID' }) }]}
            >
              <InputNumber style={{ width: 160 }} min={1} precision={0} />
            </Form.Item>
            <Form.Item
              name="principalType"
              label={t('supply:principalBindings.col.principalType')}
              rules={[{ required: true, message: t('form.required', { label: t('supply:principalBindings.col.principalType') }) }]}
            >
              <Select
                style={{ width: 160 }}
                options={BINDING_PRINCIPAL_TYPE.map((o) => ({ value: o.value, label: t(`supply:enum.principalType.${o.value}`) }))}
              />
            </Form.Item>
            <Form.Item
              name="principalId"
              label={t('supply:principalBindings.col.principalId')}
              rules={[{ required: true, message: t('form.required', { label: t('supply:principalBindings.col.principalId') }) }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                allowClear
                style={{ width: 240 }}
                options={boundPrincipalType === 'STATION' ? stationOptions : manufacturerOptions}
              />
            </Form.Item>
            <Form.Item label=" ">
              <Space>
                <Button icon={<SearchOutlined />} loading={loading} onClick={queryByUser}>
                  {t('action.search')}
                </Button>
                <Perm any={['mfg:bind:manage', 'station:bind:manage']}>
                  <Button type="primary" icon={<LinkOutlined />} loading={submitting} onClick={bind}>
                    {t('supply:principalBindings.bind')}
                  </Button>
                </Perm>
              </Space>
            </Form.Item>
          </Space>
        </Form>
      </Card>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />
    </div>
  );

  const byPrincipalTab = (
    <div>
      <Card size="small" title={t('supply:principalBindings.tab.byPrincipal')} style={{ marginBottom: 12 }}>
        <Form form={principalForm} layout="vertical">
          <Space wrap align="start">
            <Form.Item
              name="principalType"
              label={t('supply:principalBindings.col.principalType')}
              rules={[{ required: true, message: t('form.required', { label: t('supply:principalBindings.col.principalType') }) }]}
            >
              <Select
                style={{ width: 160 }}
                options={BINDING_PRINCIPAL_TYPE.map((o) => ({ value: o.value, label: t(`supply:enum.principalType.${o.value}`) }))}
              />
            </Form.Item>
            <Form.Item
              name="principalId"
              label={t('supply:principalBindings.col.principalId')}
              rules={[{ required: true, message: t('form.required', { label: t('supply:principalBindings.col.principalId') }) }]}
            >
              <InputNumber style={{ width: 160 }} min={1} precision={0} />
            </Form.Item>
            <Form.Item label=" ">
              <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={queryByPrincipal}>
                {t('action.search')}
              </Button>
            </Form.Item>
          </Space>
        </Form>
      </Card>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />
    </div>
  );

  return (
    <PageCard
      title={t('supply:principalBindings.title')}
      subtitle={t('supply:principalBindings.subtitle')}
    >
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('supply:principalBindings.tip')} />
      <Tabs
        activeKey={tab}
        onChange={(k) => { setTab(k); setRows([]); }}
        items={[
          { key: 'byUser', label: t('supply:principalBindings.tab.byUser'), children: byUserTab },
          { key: 'byPrincipal', label: t('supply:principalBindings.tab.byPrincipal'), children: byPrincipalTab },
        ]}
      />
    </PageCard>
  );
}
