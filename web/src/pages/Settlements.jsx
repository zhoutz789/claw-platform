import { useState } from 'react';
import { Form, Input, InputNumber, Button, Card, App, Descriptions, Tag } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';

export default function Settlements() {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState(null);

  const onFinish = async (vals) => {
    setSubmitting(true);
    setResult(null);
    try {
      const d = await api.post('/v1/settlements/cross-border', vals);
      setResult(d);
      message.success('跨境结算试算成功');
    } catch (e) {
      message.error(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PageCard title="跨境结算试算（KHM ⇄ 邻国）">
      <Card style={{ maxWidth: 520, marginBottom: 16 }}>
        <Form
          form={form}
          layout="vertical"
          initialValues={{ fromCountry: 'KHM', toCountry: 'VNM', goodsValue: 1000, currency: 'USD' }}
          onFinish={onFinish}
        >
          <Form.Item label="起运国" name="fromCountry" rules={[{ required: true }]}>
            <Input placeholder="如 KHM" />
          </Form.Item>
          <Form.Item label="目的国" name="toCountry" rules={[{ required: true }]}>
            <Input placeholder="如 VNM" />
          </Form.Item>
          <Form.Item label="货值" name="goodsValue" rules={[{ required: true }]}>
            <InputNumber min={0.01} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="币种" name="currency" rules={[{ required: true }]}>
            <Input placeholder="如 USD" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting}>
            试算
          </Button>
        </Form>
      </Card>
      {result && (
        <Card title="结算结果" size="small">
          <Descriptions column={2} bordered size="small">
            {Object.entries(result).map(([k, v]) => (
              <Descriptions.Item key={k} label={k}>
                {typeof v === 'object' ? JSON.stringify(v) : String(v)}
              </Descriptions.Item>
            ))}
          </Descriptions>
          <Tag color="blue" style={{ marginTop: 12 }}>
            注：此为试算/报价接口，实际跨境清算按净额多边对账执行
          </Tag>
        </Card>
      )}
    </PageCard>
  );
}
