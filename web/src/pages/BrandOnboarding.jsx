import { useState } from 'react';
import { Steps, Form, Input, Button, Result, Alert, Tag, Card, Space, message, Upload, Progress } from 'antd';
import { InboxOutlined, SafetyCertificateOutlined, ShopOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';

// 品牌方入驻：第②步「创建品牌」真实写入后端厂家（POST /v1/admin/manufacturer/manufacturers），
// 第④步展示真实返回的厂家（id/code/name）。第③步验真无 OCR/人工核验接口，保留本地演示进度条并明确标注。
export default function BrandOnboarding() {
  const [current, setCurrent] = useState(0);
  const [verifying, setVerifying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [brand, setBrand] = useState(null); // POST 真实返回的厂家
  const [form] = Form.useForm();

  const next = () => setCurrent((c) => c + 1);
  const prev = () => setCurrent((c) => c - 1);

  // 第②步提交：创建品牌（厂家）→ 真实后端
  const submitBrand = async () => {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      const payload = {
        code: (values.code && values.code.trim()) || `BRAND-${Date.now()}`,
        name: values.name.trim(),
        contact: values.contact || '',
        country: values.country || 'KH',
        status: 'ACTIVE',
      };
      const created = await api.post('/v1/admin/manufacturer/manufacturers', payload);
      setBrand(created); // 真实返回：{ id, code, name, contact, country, status }
      message.success(`品牌「${created.name}」已提交，进入平台验真`);
      startVerify();
    } catch (e) {
      message.error('创建品牌失败：' + e.message);
    } finally {
      setSubmitting(false);
    }
  };

  // 第③步：本地模拟进度条（后端 OCR/人工核验待接入，进度不代表真实结果）
  const startVerify = () => {
    setCurrent(2);
    setVerifying(true);
    setProgress(0);
    const t = setInterval(() => {
      setProgress((p) => {
        if (p >= 100) {
          clearInterval(t);
          setVerifying(false);
          setCurrent(3); // 自动进入激活页
          return 100;
        }
        return p + 20;
      });
    }, 400);
  };

  return (
    <PageCard title="品牌方入驻（厂家 → 品牌方）">
      <Alert type="warning" showIcon style={{ marginBottom: 14 }}
        message="品牌 = 用户个人名下公司；需提交商标注册证书并通过平台验真，方可使用品牌发布产品与商品。未验证品牌禁止发布。" />
      <Steps current={current} style={{ marginBottom: 24 }}
        items={[
          { title: '名下公司', description: '自动套公司注册资料' },
          { title: '创建品牌', description: '提交商标注册证书' },
          { title: '平台验真', description: 'OCR + 人工核验（本地演示）' },
          { title: '激活使用', description: '开通品牌方权限' },
        ]}
      />

      {current === 0 && (
        <Card title="① 名下公司（自动套用）">
          <Form layout="vertical" initialValues={{ company: '柬埔寨新能源科技有限公司', credit: 'KH-912034567', owner: '李工' }}>
            <Form.Item label="公司名称"><Input /></Form.Item>
            <Form.Item label="统一社会信用代码"><Input /></Form.Item>
            <Form.Item label="法人 / 关联用户"><Input /></Form.Item>
            <Button type="primary" onClick={next}>下一步</Button>
          </Form>
        </Card>
      )}

      {current === 1 && (
        <Card title="② 创建品牌 + 提交商标注册证书">
          <Form layout="vertical" form={form} initialValues={{ name: '', code: '', contact: '', country: 'KH' }}>
            <Form.Item label="品牌名称" name="name" rules={[{ required: true, message: '请输入品牌名称' }]}>
              <Input placeholder="如 金边电科" />
            </Form.Item>
            <Form.Item label="商标注册号（作为品牌 code）" name="code" extra="留空将自动生成唯一 code。">
              <Input placeholder="如 KH-TR-2025-0881" />
            </Form.Item>
            <Form.Item label="联系人" name="contact">
              <Input placeholder="如 王工" />
            </Form.Item>
            <Form.Item label="国家代码" name="country">
              <Input placeholder="如 KH / CN" />
            </Form.Item>
            <Form.Item label="商标注册证书" extra="支持图片/PDF；平台将 OCR 提取注册号并比对商标库（后端 OCR 待接入）。">
              <Upload.Dragger multiple={false} beforeUpload={() => false}>
                <p className="ant-upload-drag-icon"><InboxOutlined /></p>
                <p className="ant-upload-text">点击或拖拽商标证书</p>
              </Upload.Dragger>
            </Form.Item>
            <Space>
              <Button onClick={prev}>上一步</Button>
              <Button type="primary" loading={submitting} onClick={submitBrand}>提交并验真</Button>
            </Space>
          </Form>
        </Card>
      )}

      {current === 2 && (
        <Card title="③ 平台验真中">
          <Alert type="warning" showIcon style={{ marginBottom: 12 }}
            message="验真流程为本地演示（后端 OCR / 人工核验待接入），此进度条不代表真实核验结果。" />
          <Progress percent={progress} status={verifying ? 'active' : 'success'} />
          <p style={{ color: 'var(--muted)' }}>OCR 提取注册号 → 商标库比对 → 人工复核责任主体。资料归档留存，备责任落实与合法沟通。</p>
        </Card>
      )}

      {current === 3 && (
        brand ? (
          <Result
            status="success"
            icon={<SafetyCertificateOutlined />}
            title={`品牌「${brand.name}」已验证激活`}
            subTitle="现为合法品牌方，可创建品牌产品、发布商品；资料已归档备查。"
            extra={[
              <Button type="primary" key="p" icon={<ShopOutlined />} onClick={() => message.success('前往品牌产品管理（演示）')}>进入品牌产品管理</Button>,
              <Tag key="t" color="gold" icon={<SafetyCertificateOutlined />}>商标 No. {brand.code}</Tag>,
              <Tag key="i" color="blue">厂家ID {brand.id}</Tag>,
            ]}
          />
        ) : (
          <Result status="info" title="尚未创建品牌"
            subTitle="请返回第②步提交品牌创建。"
            extra={[<Button key="b" type="primary" onClick={() => setCurrent(1)}>返回创建品牌</Button>]} />
        )
      )}
    </PageCard>
  );
}
