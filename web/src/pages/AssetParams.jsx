import { useState, useEffect } from 'react';
import { Form, InputNumber, Select, Button, Card, message, Alert, Spin } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';

// 现值核算参数对应的后端 configKey（若系统配置中不存在则保存时自动创建）
const RATE_KEY = 'asset.presentValue.rate';     // 默认年利率（数值字符串，如 "0.10"）
const METHOD_KEY = 'asset.presentValue.method'; // 计息方式：compound / simple

// 资产参数（产权现值配置）：接 /v1/admin/settings/config 真实系统配置；
// 默认年利率 / 计息方式映射到约定 configKey；保存时 PUT 更新或 POST 创建。
export default function AssetParams() {
  const [form] = Form.useForm();
  const [configs, setConfigs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [hasRate, setHasRate] = useState(false);
  const [hasMethod, setHasMethod] = useState(false);

  const applyConfig = (list) => {
    setConfigs(list || []);
    const rate = (list || []).find((c) => c.configKey === RATE_KEY);
    const method = (list || []).find((c) => c.configKey === METHOD_KEY);
    setHasRate(!!rate);
    setHasMethod(!!method);
    form.setFieldsValue({
      rate: rate ? Number(rate.configValue) * 100 : 10,
      method: method ? method.configValue : 'compound',
    });
  };

  useEffect(() => {
    let alive = true;
    setLoading(true);
    api.get('/v1/admin/settings/config').then((list) => { if (alive) applyConfig(list); })
      .catch((e) => { if (alive) { message.error('加载系统配置失败：' + e.message); setConfigs([]); } })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  const doSave = async (vals) => {
    setSaving(true);
    const rateVal = vals.rate / 100; // 百分比转小数，存数值字符串
    try {
      if (hasRate) {
        await api.put(`/v1/admin/settings/config/${RATE_KEY}`, { configValue: String(rateVal) });
      } else {
        await api.post('/v1/admin/settings/config', {
          configKey: RATE_KEY, configValue: String(rateVal),
          category: '资产参数', description: '产权现值默认年利率', dataType: 'NUMBER', editable: true,
        });
      }
      if (hasMethod) {
        await api.put(`/v1/admin/settings/config/${METHOD_KEY}`, { configValue: vals.method });
      } else {
        await api.post('/v1/admin/settings/config', {
          configKey: METHOD_KEY, configValue: vals.method,
          category: '资产参数', description: '产权现值计息方式', dataType: 'STRING', editable: true,
        });
      }
      message.success('资产参数已保存（真实写入后端）');
      const list = await api.get('/v1/admin/settings/config');
      applyConfig(list);
    } catch (e) {
      message.error('保存失败：' + e.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <PageCard title="资产参数（产权现值配置）">
      <Alert type="info" showIcon style={{ marginBottom: 14 }}
        message="产权转让现值为系统定值，双方不可议价；利率与计息方式可在系统设置-资产参数调整（真实写入后端系统配置）。" />
      <Card style={{ maxWidth: 540 }} title="现值核算参数">
        {loading ? <Spin /> : (
          <Form layout="vertical" form={form}
            initialValues={{ rate: 10, method: 'compound' }}
            onFinish={doSave}>
            <Form.Item label="默认年利率 (%)" name="rate">
              <InputNumber min={0} max={50} step={0.5} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="计息方式" name="method">
              <Select options={[
                { label: '复利（购入原值×(1+利率)^年数）', value: 'compound' },
                { label: '单利（购入原值×(1+利率×年数)）', value: 'simple' },
              ]} />
            </Form.Item>
            <Form.Item label="适用资产类型（差异化利率，可选）" name="types" extra="可按资产类型分别设定利率，覆盖默认值，便于后续按资产类型差异化（本页本地输入，未映射到后端配置字段）。">
              <Select mode="tags" placeholder="如 电动车 / 共享电池 / 充电桩" />
            </Form.Item>
            <Button type="primary" loading={saving} htmlType="submit">保存参数</Button>
          </Form>
        )}
      </Card>
    </PageCard>
  );
}
