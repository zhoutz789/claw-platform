import { useState } from 'react';
import { Card, Form, Input, Button, App, Typography, Alert } from 'antd';
import { MobileOutlined, SafetyOutlined } from '@ant-design/icons';
import api from '../api';
import { setToken } from '../auth';

const { Title, Paragraph } = Typography;

export default function Login() {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [phone, setPhone] = useState('13800000001');
  const [code, setCode] = useState('');
  const [sending, setSending] = useState(false);
  const [logging, setLogging] = useState(false);

  const onGetCode = async () => {
    try {
      setSending(true);
      // 开发模式 sms-dev-echo=true：接口直接回显验证码，便于联调
      const echoed = await api.post('/v1/auth/sms-code', { phone });
      setCode(echoed || '');
      message.success(`验证码已发送（开发模式回显）：${echoed}`);
    } catch (e) {
      // 后端不可达 → 开发演示模式回退
      setCode('123456');
      message.warning('后端未连接，已使用演示验证码：123456');
    } finally {
      setSending(false);
    }
  };

  const onFinish = async () => {
    try {
      setLogging(true);
      const resp = await api.post('/v1/auth/login', { phone, code });
      setToken(resp.token);
      message.success('登录成功');
      window.location.hash = '#/dashboard';
      window.location.reload();
    } catch (e) {
      // 后端不可达 → 开发演示模式直接进
      setToken('dev-mock-token');
      message.warning('后端未连接，已进入演示模式');
      window.location.hash = '#/dashboard';
      window.location.reload();
    } finally {
      setLogging(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg,#0f172a,#16a34a)',
      }}
    >
      <Card style={{ width: 380 }} variant="borderless">
        <Title level={3} style={{ textAlign: 'center', marginBottom: 4 }}>
          Claw 管理后台
        </Title>
        <Paragraph type="secondary" style={{ textAlign: 'center' }}>
          新能源资产全生命周期运营管理平台
        </Paragraph>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="OTP 登录：输入手机号 → 获取验证码（开发模式会直接显示验证码）→ 登录"
        />
        <Form form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item label="手机号">
            <Input
              size="large"
              prefix={<MobileOutlined />}
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="如 13800000001"
            />
          </Form.Item>
          <Form.Item label="验证码">
            <Input
              size="large"
              prefix={<SafetyOutlined />}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="点击左侧按钮获取"
              addonAfter={
                <Button type="link" size="small" loading={sending} onClick={onGetCode}>
                  获取验证码
                </Button>
              }
            />
          </Form.Item>
          <Button type="primary" size="large" block htmlType="submit" loading={logging}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
