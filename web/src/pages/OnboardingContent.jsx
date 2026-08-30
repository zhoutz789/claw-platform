import { useCallback, useEffect, useState } from 'react';
import {
  App, Button, Card, Popconfirm, Space, Table, Tag, Upload,
} from 'antd';
import { CloudUploadOutlined, PlusOutlined, RollbackOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { APPLICANT_TYPES, EMPTY, beforeUploadAttachment, fmtTime, uploadFile } from '../components/onboardingShared';
import {
  listContentVersions, publishContract, rollbackContract, saveContractDraft,
} from '../api/onboarding';

/**
 * 入驻说明与合同配置页（增量 C · 页面 6 · O1/O2）。
 *
 * 富文本编辑合作要点（内容由后端保存为 HTML），<b>发布即生成新版本号</b>
 * （v{major}.{minor}，历史版本只读留存、可对比、可回滚）；
 * 公司签章合同扫描件与说明<b>同版本管理</b> —— 换扫描件即发新版本，
 * 争议以申请单签署时的快照版本为准（B5）。
 *
 * 对接后端 AdminOnboardingController。权限码 onboarding:content:manage。
 */
export default function OnboardingContent() {
  const { message } = App.useApp();
  const [applicantType, setApplicantType] = useState('STATION');
  const [lang, setLang] = useState('zh');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  const [title, setTitle] = useState('');
  const [html, setHtml] = useState('');
  const [fileUrl, setFileUrl] = useState('');
  const [major, setMajor] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listContentVersions(applicantType, lang);
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(`版本列表加载失败：${e.message}`);
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [applicantType, lang, message]);

  useEffect(() => { load(); }, [load]);

  const doSaveDraft = async () => {
    setSaving(true);
    try {
      await saveContractDraft({
        applicantType, lang, title, contentHtml: html, contractFileUrl: fileUrl,
      });
      message.success('草稿已保存');
      load();
    } catch (e) {
      message.error(`保存失败：${e.message}`);
    } finally {
      setSaving(false);
    }
  };

  const doPublish = async () => {
    if (!html.trim()) {
      message.error('请先填写合作要点内容');
      return;
    }
    setSaving(true);
    try {
      const d = await publishContract({
        applicantType, lang, title, contentHtml: html, contractFileUrl: fileUrl, major,
      });
      message.success(`已发布新版本 ${d?.version}`);
      load();
    } catch (e) {
      message.error(`发布失败：${e.message}`);
    } finally {
      setSaving(false);
    }
  };

  const doRollback = async (id) => {
    try {
      const d = await rollbackContract(id);
      message.success(`已回滚到版本 ${d?.version}`);
      load();
    } catch (e) {
      message.error(`回滚失败：${e.message}`);
    }
  };

  const columns = [
    { title: '版本号', dataIndex: 'version', width: 100, render: (v) => <Tag color="blue">v{v}</Tag> },
    { title: '标题', dataIndex: 'title', width: 220 },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (v) => (
        <Tag color={v === 'PUBLISHED' ? 'green' : v === 'ARCHIVED' ? 'default' : 'orange'}>
          {v === 'PUBLISHED' ? '生效中' : v === 'ARCHIVED' ? '已归档' : '草稿'}
        </Tag>
      ),
    },
    {
      title: '签章合同扫描件', dataIndex: 'contractFileUrl', width: 200,
      render: (v) => (v ? <a href={v} target="_blank" rel="noreferrer">预览 / 下载</a> : EMPTY),
    },
    { title: '发布时间', dataIndex: 'publishedAt', width: 160, render: (v) => fmtTime(v) },
    { title: '内容指纹', dataIndex: 'contentHash', render: (v) => (v ? `${String(v).slice(0, 16)}…` : EMPTY) },
    {
      title: '操作', key: '_actions', width: 120,
      render: (_, r) => (
        <Perm code="onboarding:content:manage">
          <Popconfirm title={`确认回滚到版本 ${r.version}？`} onConfirm={() => doRollback(r.id)}>
            <Button size="small" type="link" icon={<RollbackOutlined />}
              disabled={r.status === 'PUBLISHED'}>回滚</Button>
          </Popconfirm>
        </Perm>
      ),
    },
  ];

  return (
    <PageCard
      title="入驻说明与合同"
      subtitle="富文本编辑合作要点，发布即生成新版本号；历史版本可对比、可回滚"
      reload={load}
      loading={loading}
    >
      <Space wrap style={{ marginBottom: 12 }}>
        <span>主体类型</span>
        <select
          style={{ height: 32, minWidth: 120 }}
          value={applicantType}
          onChange={(e) => setApplicantType(e.target.value)}
        >
          {APPLICANT_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
        <span>语言</span>
        <select style={{ height: 32, minWidth: 90 }} value={lang} onChange={(e) => setLang(e.target.value)}>
          <option value="zh">中文</option>
          <option value="km">柬语</option>
          <option value="en">英语</option>
        </select>
      </Space>

      <Card size="small" title="版本列表" style={{ marginBottom: 12 }}>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={columns}
          size="small"
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
      </Card>

      <Card size="small" title="编辑并发布新版本">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <input
            className="ant-input"
            style={{ height: 32, width: 420 }}
            placeholder="标题，如：服务站入驻合作说明"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
          {/* 富文本编辑区：以 textarea 承载 HTML 源码，与后端 content_html 直接对应。
              首期不引第三方富文本编辑器（依赖与体积代价大），后续替换为用户友好的所见即所得编辑器时
              只需把本 textarea 换成编辑器组件，接口与存储格式不变。 */}
          <textarea
            className="ant-input"
            rows={12}
            placeholder="填写合作要点（支持 HTML，如 &lt;h2&gt;一、合作模式&lt;/h2&gt;&lt;p&gt;…&lt;/p&gt;）"
            value={html}
            onChange={(e) => setHtml(e.target.value)}
          />
          <Space wrap>
            <Upload
              accept="image/jpeg,image/png,application/pdf"
              beforeUpload={beforeUploadAttachment}
              maxCount={1}
              customRequest={async ({ file, onSuccess, onError }) => {
                try {
                  const url = await uploadFile(file);
                  setFileUrl(url);
                  message.success('合同扫描件已上传');
                  onSuccess({ url }, file);
                } catch (e) { onError(e); }
              }}
            >
              <Button icon={<CloudUploadOutlined />}>上传公司签章合同扫描件</Button>
            </Upload>
            {fileUrl && <a href={fileUrl} target="_blank" rel="noreferrer">查看已上传扫描件</a>}
            <label style={{ marginLeft: 12 }}>
              <input type="checkbox" checked={major} onChange={(e) => setMajor(e.target.checked)} />
              {' '}作为大版本发布（major+1）
            </label>
          </Space>
          <Space>
            <Perm code="onboarding:content:manage">
              <Button loading={saving} onClick={doSaveDraft}>保存草稿</Button>
              <Button type="primary" icon={<PlusOutlined />} loading={saving} onClick={doPublish}>
                发布新版本
              </Button>
            </Perm>
          </Space>
          <div style={{ color: '#8c8c8c', fontSize: 12 }}>
            发布后旧版本自动归档；同一主体类型 + 语言同时只有一个生效版本。
            申请单会<b>快照签署时的版本号</b>，后续改版不影响历史申请。
          </div>
        </Space>
      </Card>
    </PageCard>
  );
}
