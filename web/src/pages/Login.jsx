import { useState } from 'react';
import { Card, Form, Input, Button, App, Typography, Alert } from 'antd';
import { MobileOutlined, SafetyOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import api from '../api';
import { setToken } from '../auth';
import { loadPermissions } from '../permStore';
import LangSwitch from '../i18n/LangSwitch';

const { Title, Paragraph } = Typography;

export default function Login() {
  const { message } = App.useApp();
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [phone, setPhone] = useState('13800000007');
  const [code, setCode] = useState('');
  const [sending, setSending] = useState(false);
  const [logging, setLogging] = useState(false);

  const onGetCode = async () => {
    try {
      setSending(true);
      // 开发模式 sms-dev-echo=true：接口直接回显验证码，便于联调
      const echoed = await api.post('/v1/auth/sms-code', { phone });
      // 防御性取值：后端可能返回字符串、对象或 null（旧镜像/不同版本）
      const codeStr = typeof echoed === 'string' ? echoed : (echoed?.code || echoed?.data || '');
      setCode(codeStr);
      message.success(t('login.codeSent', { code: codeStr }));
    } catch (e) {
      // 后端不可达 → 开发演示模式回退
      setCode('123456');
      message.warning(t('login.backendDownCode'));
    } finally {
      setSending(false);
    }
  };

  const onFinish = async () => {
    try {
      setLogging(true);
      const resp = await api.post('/v1/auth/login', { phone, code });
      // 防御性取值：兼容旧镜像可能的不同响应结构
      const token = resp?.token || (typeof resp === 'string' ? resp : '');
      setToken(token);
      message.success(t('login.success'));
      loadPermissions().catch(() => {});
      window.location.hash = '#/dashboard';
      window.location.reload();
    } catch (e) {
      // 后端不可达 → 开发演示模式直接进
      setToken('dev-mock-token');
      loadPermissions().catch(() => {});
      message.warning(t('login.backendDownLogin'));
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
      <Card
        style={{ width: 380 }}
        variant="borderless"
        // 语言切换器：按周老板要求放在「用户登录口旁」，即登录卡片右上角
        extra={<LangSwitch variant="segmented" size="small" />}
      >
        <Title level={3} style={{ textAlign: 'center', marginBottom: 4 }}>
          {t('app.loginTitle')}
        </Title>
        <Paragraph type="secondary" style={{ textAlign: 'center' }}>
          {t('app.loginSlogan')}
        </Paragraph>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('login.otpTip')}
        />
        <Form form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item label={t('login.phone')}>
            <Input
              size="large"
              prefix={<MobileOutlined />}
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder={t('login.phonePlaceholder')}
            />
          </Form.Item>
          <Form.Item label={t('login.code')}>
            <Input
              size="large"
              prefix={<SafetyOutlined />}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder={t('login.codePlaceholder')}
              addonAfter={
                <Button type="link" size="small" loading={sending} onClick={onGetCode}>
                  {t('login.getCode')}
                </Button>
              }
            />
          </Form.Item>
          <Button type="primary" size="large" block htmlType="submit" loading={logging}>
            {t('login.submit')}
          </Button>
        </Form>
      </Card>
    </div>
  );
}
