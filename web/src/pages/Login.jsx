import { useState } from 'react';
import { Card, Form, Input, Button, App, Typography, Alert, Segmented, Modal, Space } from 'antd';
import { MobileOutlined, SafetyOutlined, LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import api from '../api';
import { setToken } from '../auth';
import { loadPermissions } from '../permStore';
import LangSwitch from '../i18n/LangSwitch';

const { Title, Paragraph } = Typography;

export default function Login() {
  const { message } = App.useApp();
  const { t } = useTranslation();
  const [mode, setMode] = useState('otp'); // 'otp' | 'password'
  const [phone, setPhone] = useState('13800000007');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [sending, setSending] = useState(false);
  const [logging, setLogging] = useState(false);

  // 找回 / 修改密码弹窗：必须手机号 + 短信验证码验证
  const [forgotOpen, setForgotOpen] = useState(false);
  const [fpForm] = Form.useForm();
  const [fpPhone, setFpPhone] = useState('');
  const [fpCode, setFpCode] = useState('');
  const [fpNewPwd, setFpNewPwd] = useState('');
  const [fpSending, setFpSending] = useState(false);
  const [fpSubmitting, setFpSubmitting] = useState(false);

  const doGetCode = async (targetPhone, setBusy, setFieldCode) => {
    try {
      setBusy(true);
      // 开发模式 sms-dev-echo=true：接口直接回显验证码，便于联调
      const echoed = await api.post('/v1/auth/sms-code', { phone: targetPhone });
      const codeStr = typeof echoed === 'string' ? echoed : (echoed?.code || echoed?.data || '');
      if (setFieldCode) setFieldCode(codeStr);
      message.success(t('login.codeSent', { code: codeStr }));
    } catch (e) {
      if (!e.response) {
        // 后端不可达 → 开发演示模式回退
        if (setFieldCode) setFieldCode('123456');
        message.warning(t('login.backendDownCode'));
      }
    } finally {
      setBusy(false);
    }
  };

  const onGetCode = () => doGetCode(phone, setSending, setCode);

  const onFpGetCode = () => doGetCode(fpPhone, setFpSending, setFpCode);

  const onFinish = async () => {
    try {
      setLogging(true);
      const resp = mode === 'otp'
        ? await api.post('/v1/auth/login', { phone, code })
        : await api.post('/v1/auth/password-login', { phone, password });
      // 防御性取值：兼容旧镜像可能的不同响应结构
      const token = resp?.token || (typeof resp === 'string' ? resp : '');
      setToken(token);
      message.success(t('login.success'));
      loadPermissions().catch(() => {});
      window.location.hash = '#/dashboard';
      window.location.reload();
    } catch (e) {
      // 仅当后端完全不可达（无响应）时回退演示模式；
      // 若服务器已返回错误（验证码/密码错等），拦截器已提示，不静默登录。
      if (!e.response) {
        setToken('dev-mock-token');
        loadPermissions().catch(() => {});
        message.warning(t('login.backendDownLogin'));
        window.location.hash = '#/dashboard';
        window.location.reload();
      }
    } finally {
      setLogging(false);
    }
  };

  const openForgot = () => {
    // 预填当前手机号，便于已登录 / 已知账号场景
    setFpPhone(phone || '');
    setFpCode('');
    setFpNewPwd('');
    fpForm.resetFields();
    setForgotOpen(true);
  };

  const onForgotOk = async () => {
    try {
      setFpSubmitting(true);
      await api.post('/v1/auth/forgot-password', {
        phone: fpPhone,
        code: fpCode,
        newPassword: fpNewPwd,
      });
      message.success(t('login.forgotSuccess'));
      setForgotOpen(false);
      // 回填到密码登录表单，便于直接用新密码登录
      setMode('password');
      setPhone(fpPhone);
      setPassword(fpNewPwd);
    } catch (e) {
      // 服务器错误（验证码错 / 密码弱）由拦截器提示，停留在弹窗
    } finally {
      setFpSubmitting(false);
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

        <Segmented
          block
          style={{ marginBottom: 16 }}
          value={mode}
          onChange={(v) => setMode(v)}
          options={[
            { label: t('login.modeOtp'), value: 'otp' },
            { label: t('login.modePassword'), value: 'password' },
          ]}
        />

        {mode === 'otp' && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('login.otpTip')}
          />
        )}

        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item label={t('login.phone')}>
            <Input
              size="large"
              prefix={<MobileOutlined />}
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder={t('login.phonePlaceholder')}
            />
          </Form.Item>

          {mode === 'otp' ? (
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
          ) : (
            <Form.Item label={t('login.password')}>
              <Input.Password
                size="large"
                prefix={<LockOutlined />}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t('login.passwordPlaceholder')}
                onPressEnter={onFinish}
              />
            </Form.Item>
          )}

          <Button type="primary" size="large" block htmlType="submit" loading={logging}>
            {t('login.submit')}
          </Button>

          <div style={{ marginTop: 12, textAlign: 'right' }}>
            <Button type="link" size="small" onClick={openForgot}>
              {t('login.forgot')}
            </Button>
          </div>
        </Form>
      </Card>

      <Modal
        title={t('login.forgotTitle')}
        open={forgotOpen}
        onOk={onForgotOk}
        onCancel={() => setForgotOpen(false)}
        confirmLoading={fpSubmitting}
        okText={t('login.forgotSubmit')}
        cancelText={t('common.cancel')}
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('login.forgotTip')}
        />
        <Form form={fpForm} layout="vertical">
          <Form.Item label={t('login.phone')}>
            <Input
              size="large"
              prefix={<MobileOutlined />}
              value={fpPhone}
              onChange={(e) => setFpPhone(e.target.value)}
              placeholder={t('login.phonePlaceholder')}
            />
          </Form.Item>
          <Form.Item label={t('login.code')}>
            <Input
              size="large"
              prefix={<SafetyOutlined />}
              value={fpCode}
              onChange={(e) => setFpCode(e.target.value)}
              placeholder={t('login.codePlaceholder')}
              addonAfter={
                <Button type="link" size="small" loading={fpSending} onClick={onFpGetCode}>
                  {t('login.getCode')}
                </Button>
              }
            />
          </Form.Item>
          <Form.Item label={t('login.forgotNewPwd')}>
            <Input.Password
              size="large"
              prefix={<LockOutlined />}
              value={fpNewPwd}
              onChange={(e) => setFpNewPwd(e.target.value)}
              placeholder={t('login.passwordPlaceholder')}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
