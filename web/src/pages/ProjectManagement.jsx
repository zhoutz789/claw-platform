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

export default function ProjectManagement() {
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
      title: '删除项目',
      content: '确认删除项目「' + selectedNode.name + '」？子项目一并移除。',
      okText: '删除', okType: 'danger', cancelText: '取消',
      onOk: () => api.delete(`/v1/projects/${selectedNode.id}`)
        .then(() => { message.success('已删除'); setSelectedId(null); loadTree(); })
        .catch((e) => message.error('删除失败：' + e.message)),
    });
  };

  /* ---------- 设备绑定/解绑 ---------- */
  const bindDevice = () => {
    if (!selectedId) return;
    if (!bindAssetId) { message.warning('请选择要绑定的资产'); return; }
    api.post(`/v1/projects/${selectedId}/devices`, { assetId: bindAssetId })
      .then(() => {
        message.success('设备已绑定');
        setBindAssetId(null);
        return api.get(`/v1/projects/${selectedId}/devices`);
      })
      .then((d) => setDevices(Array.isArray(d) ? d : []))
      .catch((e) => message.error('绑定失败：' + e.message));
  };
  const unbindDevice = (pdId) => {
    api.delete(`/v1/projects/devices/${pdId}`)
      .then(() => {
        message.success('已解绑');
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
          message.success('授权已提交');
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
          message.success('记账成功');
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
    { title: '设备编号', dataIndex: 'assetNo', render: (v, r) => <span>{v || '—'} <span style={{ color: '#8a9099' }}>#{r.id}</span></span> },
    { title: '类型', dataIndex: 'assetType', render: (v) => v || '—' },
    { title: '状态', dataIndex: 'assetStatus', render: (v) => (v ? <Tag>{v}</Tag> : '—') },
    { title: '操作', render: (_, r) => (
      <Space>
        <Button size="small" type="link" danger icon={<UnlockOutlined />} onClick={() => unbindDevice(r.id)}>解绑</Button>
        <Button size="small" type="link" icon={<SafetyCertificateOutlined />} onClick={() => openAuth(r)}>授权</Button>
      </Space>
    ) },
  ];

  return (
    <PageCard title="项目管理" extra={<span style={{ fontSize: 12, color: '#8a9099' }}>项目树 / 设备绑定 / 赋权 / 核算（C 期）</span>}>
      <div style={{ display: 'flex', gap: 16, minHeight: 480 }}>
        {/* 左栏：项目树 */}
        <div style={{ width: 280, flex: 'none', borderRight: '1px solid #f0f0f0', paddingRight: 12 }}>
          <div style={{ marginBottom: 8, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openAddRoot}>新建项目</Button>
            <Button size="small" icon={<PlusOutlined />} disabled={!selectedNode} onClick={openAddChild}>子项目</Button>
            <Button size="small" icon={<EditOutlined />} disabled={!selectedNode} onClick={openEdit}>编辑</Button>
            <Button size="small" danger icon={<DeleteOutlined />} disabled={!selectedNode} onClick={delProj}>删除</Button>
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
            ) : <Empty description="暂无项目" />}
          </Spin>
        </div>

        {/* 右栏：详情 */}
        <div style={{ flex: 1, minWidth: 0 }}>
          {!selectedNode ? (
            <Empty description="请选择左侧项目节点" style={{ marginTop: 80 }} />
          ) : (
            <Spin spinning={detailLoading}>
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <Card size="small" title="项目信息">
                  <Descriptions column={2} size="small">
                    <Descriptions.Item label="名称">{selectedNode.name}</Descriptions.Item>
                    <Descriptions.Item label="状态">{selectedNode.status ? <Tag color="blue">{selectedNode.status}</Tag> : '—'}</Descriptions.Item>
                    <Descriptions.Item label="账户ID">{selectedNode.accountId != null ? selectedNode.accountId : '—'}</Descriptions.Item>
                    <Descriptions.Item label="创建时间">{selectedNode.createdAt || '—'}</Descriptions.Item>
                  </Descriptions>
                </Card>

                <Card size="small" title="设备绑定">
                  <Space style={{ marginBottom: 12 }}>
                    <Select
                      showSearch
                      style={{ width: 320 }}
                      placeholder="选择资产（按 assetNo 搜索）"
                      value={bindAssetId}
                      onChange={setBindAssetId}
                      optionFilterProp="label"
                      options={assets.map((a) => ({ label: a.assetNo, value: a.id }))}
                      allowClear
                    />
                    <Button type="primary" icon={<LinkOutlined />} onClick={bindDevice}>绑定</Button>
                  </Space>
                  <Table rowKey="id" size="small" pagination={false} dataSource={devices} columns={devColumns}
                    locale={{ emptyText: '暂无绑定设备' }} />
                </Card>

                <Card size="small" title="项目核算" extra={
                  <Button size="small" type="primary" icon={<WalletOutlined />}
                    onClick={() => { entryForm.resetFields(); entryForm.setFieldsValue({ type: 'INCOME' }); setEntryModal(true); }}>
                    记一笔
                  </Button>
                }>
                  {account ? (
                    <Descriptions column={2} size="small">
                      <Descriptions.Item label="余额">{account.currency || ''} {account.balance != null ? account.balance : '—'}</Descriptions.Item>
                      <Descriptions.Item label="账户ID">{account.accountId != null ? account.accountId : '—'}</Descriptions.Item>
                      <Descriptions.Item label="更新时间">{account.updatedAt || '—'}</Descriptions.Item>
                    </Descriptions>
                  ) : <Empty description="暂无核算账户" />}
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
        okText="保存" cancelText="取消"
      >
        <Form form={projForm} layout="vertical">
          <Form.Item label="项目名称" name="name" rules={[{ required: true, message: '请输入项目名称' }]}>
            <Input placeholder="如：华南运营区" />
          </Form.Item>
          <Form.Item label="父项目ID（留空为顶层项目）" name="parentId">
            <InputNumber style={{ width: '100%' }} placeholder="留空为顶层项目" />
          </Form.Item>
          <Form.Item label="排序号" name="sortNo">
            <InputNumber style={{ width: '100%' }} placeholder="0" />
          </Form.Item>
          {projModal.editing && (
            <Form.Item label="状态" name="status">
              <Input placeholder="如：ACTIVE" />
            </Form.Item>
          )}
        </Form>
      </Modal>

      {/* 授权弹窗 */}
      <Modal
        title="设备授权"
        open={authModal.open}
        onOk={submitAuth}
        onCancel={() => setAuthModal({ open: false, pdId: null })}
        okText="提交授权" cancelText="取消"
        width={480}
      >
        <Form form={authForm} layout="vertical">
          <Form.Item label="授权类型" name="authType" rules={[{ required: true }]}>
            <Select options={[
              { label: 'TRANSFER 转移产权（granteeUserId 留空=进资产大厅）', value: 'TRANSFER' },
              { label: 'SHARE 共享（需 stationId）', value: 'SHARE' },
              { label: 'AUTHORIZE 授使用权', value: 'AUTHORIZE' },
            ]} />
          </Form.Item>
          <Form.Item label="被授权用户ID（granteeUserId，TRANSFER 可留空）" name="granteeUserId">
            <InputNumber style={{ width: '100%' }} placeholder="数字用户ID" />
          </Form.Item>
          <Form.Item label="权限范围（scope，如 use/locate/revenue）" name="scope">
            <Input placeholder="use" />
          </Form.Item>
          {authType === 'SHARE' && (
            <Form.Item label="站点ID（stationId，SHARE 必填）" name="stationId" rules={[{ required: true, message: 'SHARE 需填写 stationId' }]}>
              <InputNumber style={{ width: '100%' }} placeholder="数字站点ID" />
            </Form.Item>
          )}
          <Form.Item label="产权人分账比例（ownerSplitRate，可选）" name="ownerSplitRate">
            <InputNumber style={{ width: '100%' }} placeholder="如 0.3" />
          </Form.Item>
          <Form.Item label="站点分账比例（stationSplitRate，可选）" name="stationSplitRate">
            <InputNumber style={{ width: '100%' }} placeholder="如 0.1" />
          </Form.Item>
          <Form.Item label="日使用费（dailyUsageFee，可选）" name="dailyUsageFee">
            <InputNumber style={{ width: '100%' }} placeholder="0" />
          </Form.Item>
          <Form.Item label="单次换电费（perSwapFee，可选）" name="perSwapFee">
            <InputNumber style={{ width: '100%' }} placeholder="0" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 记账弹窗 */}
      <Modal
        title="记一笔"
        open={entryModal}
        onOk={submitEntry}
        onCancel={() => setEntryModal(false)}
        okText="记账" cancelText="取消"
      >
        <Form form={entryForm} layout="vertical">
          <Form.Item label="类型" name="type" rules={[{ required: true }]}>
            <Select options={[
              { label: '收入 INCOME', value: 'INCOME' },
              { label: '支出 EXPENSE', value: 'EXPENSE' },
            ]} />
          </Form.Item>
          <Form.Item label="金额" name="amount" rules={[{ required: true, message: '请输入金额' }]}>
            <InputNumber style={{ width: '100%' }} placeholder="0.00" />
          </Form.Item>
          <Form.Item label="备注" name="memo">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
