import { useState, useEffect } from 'react';
import {
  Tree, Table, Modal, Form, Input, InputNumber, Select, Button, Card,
  Descriptions, Tag, Space, Spin, Empty, message,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, LinkOutlined,
  UnlockOutlined, SafetyCertificateOutlined, WalletOutlined,
} from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
import { useTranslation } from 'react-i18next';

/* 项目树节点 -> antd Tree treeData */
const toTreeData = (nodes) => (nodes || []).map((n) => ({
  key: n.id,
  title: n.name,
  children: toTreeData(n.children),
}));

/* 扁平化树，便于按 id 查找节点原始数据 */
const flatten = (nodes, acc = []) => {
  (nodes || []).forEach((n) => { acc.push(n); flatten(n.children, acc); });
  return acc;
};

export default function ProjectManagement() {  const { t } = useTranslation('common');

  const [tree, setTree] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [expandedKeys, setExpandedKeys] = useState([]);
  const [loading, setLoading] = useState(false);

  // 选中项目详情
  const [devices, setDevices] = useState([]);
  const [account, setAccount] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [assets, setAssets] = useState([]);
  const [bindAssetId, setBindAssetId] = useState(null);

  // 项目新建/编辑弹窗
  const [projModal, setProjModal] = useState({ open: false, editing: null });
  const [projForm] = Form.useForm();

  // 授权弹窗
  const [authModal, setAuthModal] = useState({ open: false, pdId: null });
  const [authForm] = Form.useForm();

  // 记账弹窗
  const [entryModal, setEntryModal] = useState(false);
  const [entryForm] = Form.useForm();

  // 初始加载：项目树 + 资产下拉
  useEffect(() => {
    let alive = true;
    setLoading(true);
    api.get('/v1/projects')
      .then((d) => {
        if (!alive) return;
        const arr = Array.isArray(d) ? d : [];
        setTree(arr);
        setExpandedKeys(flatten(arr).map((n) => n.id));
      })
      .catch((e) => { if (alive) { message.error('加载项目树失败：' + e.message); setTree([]); } })
      .finally(() => { if (alive) setLoading(false); });
    api.get('/v1/assets')
      .then((d) => { if (alive) setAssets(Array.isArray(d) ? d : []); })
      .catch((e) => { if (alive) { message.error('加载资产列表失败：' + e.message); setAssets([]); } });
    return () => { alive = false; };
  }, []);

  // 刷新项目树（CRUD 后调用）
  const loadTree = () => {
    setLoading(true);
    api.get('/v1/projects')
      .then((d) => {
        const arr = Array.isArray(d) ? d : [];
        setTree(arr);
        setExpandedKeys(flatten(arr).map((n) => n.id));
      })
      .catch((e) => { message.error('加载项目树失败：' + e.message); setTree([]); })
      .finally(() => setLoading(false));
  };

  const flatNodes = flatten(tree);
  const selectedNode = flatNodes.find((n) => n.id === selectedId) || null;

  // 选中节点：并行加载设备 + 账户
  useEffect(() => {
    if (!selectedId) return;
    let alive = true;
    setDetailLoading(true);
    setDevices([]);
    setAccount(null);
    Promise.all([
      api.get(`/v1/projects/${selectedId}/devices`),
      api.get(`/v1/projects/${selectedId}/account`),
    ]).then(([devs, acc]) => {
      if (!alive) return;
      setDevices(Array.isArray(devs) ? devs : []);
      setAccount(acc || null);
    }).catch((e) => {
      if (alive) { message.error('加载项目详情失败：' + e.message); setDevices([]); setAccount(null); }
    }).finally(() => { if (alive) setDetailLoading(false); });
    return () => { alive = false; };
  }, [selectedId]);

  /* ---------- 项目 CRUD ---------- */
  const openAddRoot = () => {
    projForm.resetFields();
    projForm.setFieldsValue({ parentId: null, sortNo: 0 });
    setProjModal({ open: true, editing: null });
  };
  const openAddChild = () => {
    if (!selectedNode) return;
    projForm.resetFields();
    projForm.setFieldsValue({ parentId: selectedNode.id, sortNo: 0 });
    setProjModal({ open: true, editing: null });
  };
  const openEdit = () => {
    if (!selectedNode) return;
    projForm.setFieldsValue({
      name: selectedNode.name,
      parentId: selectedNode.parentId,
      sortNo: selectedNode.sortNo,
      status: selectedNode.status,
    });
    setProjModal({ open: true, editing: selectedNode.id });
  };
  const submitProj = () => {
    projForm.validateFields().then((v) => {
      const body = { name: v.name, parentId: v.parentId, sortNo: v.sortNo };
      const req = projModal.editing
        ? api.put(`/v1/projects/${projModal.editing}`, body)
        : api.post('/v1/projects', body);
      req.then(() => {
        message.success(projModal.editing ? '项目已更新' : '项目已创建');
        setProjModal({ open: false, editing: null });
        loadTree();
      }).catch((e) => message.error('保存失败：' + e.message));
    });
  };
  const delProj = () => {
    if (!selectedNode) return;
    Modal.confirm({
      title: t('common:m263'),
      content: '确认删除项目「' + selectedNode.name + '」？子项目一并移除。',
      okText: t('common:m83'), okType: 'danger', cancelText: t('common:m96'),
      onOk: () => api.delete(`/v1/projects/${selectedNode.id}`)
        .then(() => { message.success(t('common:m3')); setSelectedId(null); loadTree(); })
        .catch((e) => message.error('删除失败：' + e.message)),
    });
  };

  /* ---------- 设备绑定/解绑 ---------- */
  const bindDevice = () => {
    if (!selectedId) return;
    if (!bindAssetId) { message.warning(t('common:m264')); return; }
    api.post(`/v1/projects/${selectedId}/devices`, { assetId: bindAssetId })
      .then(() => {
        message.success(t('common:m265'));
        setBindAssetId(null);
        return api.get(`/v1/projects/${selectedId}/devices`);
      })
      .then((d) => setDevices(Array.isArray(d) ? d : []))
      .catch((e) => message.error('绑定失败：' + e.message));
  };
  const unbindDevice = (pdId) => {
    api.delete(`/v1/projects/devices/${pdId}`)
      .then(() => {
        message.success(t('common:m266'));
        setDevices((prev) => prev.filter((d) => d.id !== pdId));
      })
      .catch((e) => message.error('解绑失败：' + e.message));
  };

  /* ---------- 授权 ---------- */
  const openAuth = (pd) => {
    authForm.resetFields();
    authForm.setFieldsValue({ authType: 'AUTHORIZE' });
    setAuthModal({ open: true, pdId: pd.id });
  };
  const submitAuth = () => {
    authForm.validateFields().then((v) => {
      const body = { authType: v.authType };
      if (v.granteeUserId != null && v.granteeUserId !== '') body.granteeUserId = v.granteeUserId;
      if (v.scope) body.scope = v.scope;
      if (v.authType === 'SHARE') body.stationId = v.stationId;
      if (v.ownerSplitRate != null && v.ownerSplitRate !== '') body.ownerSplitRate = v.ownerSplitRate;
      if (v.stationSplitRate != null && v.stationSplitRate !== '') body.stationSplitRate = v.stationSplitRate;
      if (v.dailyUsageFee != null && v.dailyUsageFee !== '') body.dailyUsageFee = v.dailyUsageFee;
      if (v.perSwapFee != null && v.perSwapFee !== '') body.perSwapFee = v.perSwapFee;
      api.post(`/v1/projects/devices/${authModal.pdId}/authorize`, body)
        .then(() => {
          message.success(t('common:m267'));
          setAuthModal({ open: false, pdId: null });
        })
        .catch((e) => message.error('授权失败：' + e.message));
    });
  };

  /* ---------- 记账 ---------- */
  const submitEntry = () => {
    entryForm.validateFields().then((v) => {
      api.post(`/v1/projects/${selectedId}/entries`, { amount: v.amount, type: v.type, memo: v.memo })
        .then(() => {
          message.success(t('common:m268'));
          setEntryModal(false);
          api.get(`/v1/projects/${selectedId}/account`)
            .then((acc) => setAccount(acc || null))
            .catch(() => {});
        })
        .catch((e) => message.error('记账失败：' + e.message));
    });
  };

  const authType = Form.useWatch('authType', authForm);

  const devColumns = [
    { title: t('common:m5'), dataIndex: 'assetNo', render: (v, r) => <span>{v || '—'} <span style={{ color: '#8a9099' }}>#{r.id}</span></span> },
    { title: t('common:m36'), dataIndex: 'assetType', render: (v) => v || '—' },
    { title: t('common:m8'), dataIndex: 'assetStatus', render: (v) => (v ? <Tag>{v}</Tag> : '—') },
    { title: t('common:m58'), render: (_, r) => (
      <Space>
        <Button size="small" type="link" danger icon={<UnlockOutlined />} onClick={() => unbindDevice(r.id)}>{t('common:m269')}</Button>
        <Button size="small" type="link" icon={<SafetyCertificateOutlined />} onClick={() => openAuth(r)}>{t('common:m64')}</Button>
      </Space>
    ) },
  ];

  return (
    <PageCard title={t('common:m270')} extra={<span style={{ fontSize: 12, color: '#8a9099' }}>{t('common:m271')}</span>}>
      <div style={{ display: 'flex', gap: 16, minHeight: 480 }}>
        {/* 左栏：项目树 */}
        <div style={{ width: 280, flex: 'none', borderRight: '1px solid #f0f0f0', paddingRight: 12 }}>
          <div style={{ marginBottom: 8, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openAddRoot}>{t('common:m272')}</Button>
            <Button size="small" icon={<PlusOutlined />} disabled={!selectedNode} onClick={openAddChild}>{t('common:m273')}</Button>
            <Button size="small" icon={<EditOutlined />} disabled={!selectedNode} onClick={openEdit}>{t('common:m82')}</Button>
            <Button size="small" danger icon={<DeleteOutlined />} disabled={!selectedNode} onClick={delProj}>{t('common:m83')}</Button>
          </div>
          <Spin spinning={loading}>
            {tree.length ? (
              <Tree
                treeData={toTreeData(tree)}
                expandedKeys={expandedKeys}
                onExpand={setExpandedKeys}
                selectedKeys={selectedId ? [selectedId] : []}
                onSelect={(keys) => setSelectedId(keys[0] || null)}
                showLine
              />
            ) : <Empty description={t('common:m274')} />}
          </Spin>
        </div>

        {/* 右栏：详情 */}
        <div style={{ flex: 1, minWidth: 0 }}>
          {!selectedNode ? (
            <Empty description={t('common:m275')} style={{ marginTop: 80 }} />
          ) : (
            <Spin spinning={detailLoading}>
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <Card size="small" title={t('common:m276')}>
                  <Descriptions column={2} size="small">
                    <Descriptions.Item label={t('common:m277')}>{selectedNode.name}</Descriptions.Item>
                    <Descriptions.Item label={t('common:m8')}>{selectedNode.status ? <Tag color="blue">{selectedNode.status}</Tag> : '—'}</Descriptions.Item>
                    <Descriptions.Item label={t('common:m278')}>{selectedNode.accountId != null ? selectedNode.accountId : '—'}</Descriptions.Item>
                    <Descriptions.Item label={t('common:m279')}>{selectedNode.createdAt || '—'}</Descriptions.Item>
                  </Descriptions>
                </Card>

                <Card size="small" title={t('common:m280')}>
                  <Space style={{ marginBottom: 12 }}>
                    <Select
                      showSearch
                      style={{ width: 320 }}
                      placeholder={t('common:m281')}
                      value={bindAssetId}
                      onChange={setBindAssetId}
                      optionFilterProp="label"
                      options={assets.map((a) => ({ label: a.assetNo, value: a.id }))}
                      allowClear
                    />
                    <Button type="primary" icon={<LinkOutlined />} onClick={bindDevice}>{t('common:m282')}</Button>
                  </Space>
                  <Table rowKey="id" size="small" pagination={false} dataSource={devices} columns={devColumns}
                    locale={{ emptyText: '暂无绑定设备' }} />
                </Card>

                <Card size="small" title={t('common:m283')} extra={
                  <Button size="small" type="primary" icon={<WalletOutlined />}
                    onClick={() => { entryForm.resetFields(); entryForm.setFieldsValue({ type: 'INCOME' }); setEntryModal(true); }}>{t('common:m284')}</Button>
                }>
                  {account ? (
                    <Descriptions column={2} size="small">
                      <Descriptions.Item label={t('common:m285')}>{account.currency || ''} {account.balance != null ? account.balance : '—'}</Descriptions.Item>
                      <Descriptions.Item label={t('common:m278')}>{account.accountId != null ? account.accountId : '—'}</Descriptions.Item>
                      <Descriptions.Item label={t('common:m286')}>{account.updatedAt || '—'}</Descriptions.Item>
                    </Descriptions>
                  ) : <Empty description={t('common:m287')} />}
                </Card>
              </Space>
            </Spin>
          )}
        </div>
      </div>

      {/* 项目新建/编辑弹窗 */}
      <Modal
        title={projModal.editing ? '编辑项目' : '新建项目'}
        open={projModal.open}
        onOk={submitProj}
        onCancel={() => setProjModal({ open: false, editing: null })}
        okText={t('common:m97')} cancelText={t('common:m96')}
      >
        <Form form={projForm} layout="vertical">
          <Form.Item label={t('common:m288')} name="name" rules={[{ required: true, message: t('common:m289') }]}>
            <Input placeholder={t('common:m290')} />
          </Form.Item>
          <Form.Item label={t('common:m291')} name="parentId">
            <InputNumber style={{ width: '100%' }} placeholder={t('common:m292')} />
          </Form.Item>
          <Form.Item label={t('common:m293')} name="sortNo">
            <InputNumber style={{ width: '100%' }} placeholder="0" />
          </Form.Item>
          {projModal.editing && (
            <Form.Item label={t('common:m8')} name="status">
              <Input placeholder={t('common:m294')} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      {/* 授权弹窗 */}
      <Modal
        title={t('common:m295')}
        open={authModal.open}
        onOk={submitAuth}
        onCancel={() => setAuthModal({ open: false, pdId: null })}
        okText={t('common:m296')} cancelText={t('common:m96')}
        width={480}
      >
        <Form form={authForm} layout="vertical">
          <Form.Item label={t('common:m297')} name="authType" rules={[{ required: true }]}>
            <Select options={[
              { label: t('common:m298'), value: 'TRANSFER' },
              { label: t('common:m299'), value: 'SHARE' },
              { label: t('common:m300'), value: 'AUTHORIZE' },
            ]} />
          </Form.Item>
          <Form.Item label={t('common:m301')} name="granteeUserId">
            <InputNumber style={{ width: '100%' }} placeholder={t('common:m302')} />
          </Form.Item>
          <Form.Item label={t('common:m303')} name="scope">
            <Input placeholder="use" />
          </Form.Item>
          {authType === 'SHARE' && (
            <Form.Item label={t('common:m304')} name="stationId" rules={[{ required: true, message: t('common:m305') }]}>
              <InputNumber style={{ width: '100%' }} placeholder={t('common:m306')} />
            </Form.Item>
          )}
          <Form.Item label={t('common:m307')} name="ownerSplitRate">
            <InputNumber style={{ width: '100%' }} placeholder={t('common:m308')} />
          </Form.Item>
          <Form.Item label={t('common:m309')} name="stationSplitRate">
            <InputNumber style={{ width: '100%' }} placeholder={t('common:m310')} />
          </Form.Item>
          <Form.Item label={t('common:m311')} name="dailyUsageFee">
            <InputNumber style={{ width: '100%' }} placeholder="0" />
          </Form.Item>
          <Form.Item label={t('common:m312')} name="perSwapFee">
            <InputNumber style={{ width: '100%' }} placeholder="0" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 记账弹窗 */}
      <Modal
        title={t('common:m284')}
        open={entryModal}
        onOk={submitEntry}
        onCancel={() => setEntryModal(false)}
        okText={t('common:m313')} cancelText={t('common:m96')}
      >
        <Form form={entryForm} layout="vertical">
          <Form.Item label={t('common:m36')} name="type" rules={[{ required: true }]}>
            <Select options={[
              { label: t('common:m314'), value: 'INCOME' },
              { label: t('common:m315'), value: 'EXPENSE' },
            ]} />
          </Form.Item>
          <Form.Item label={t('common:m316')} name="amount" rules={[{ required: true, message: t('common:m317') }]}>
            <InputNumber style={{ width: '100%' }} placeholder="0.00" />
          </Form.Item>
          <Form.Item label={t('common:m318')} name="memo">
            <Input.TextArea rows={2} placeholder={t('common:m319')} />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
