import { Table, Tag, Space, Modal, Select, Button, message } from 'antd';
import { useState, useMemo } from 'react';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';

const kycColor = { VERIFIED: 'green', PENDING: 'orange', UNVERIFIED: 'default', REJECTED: 'red' };

export default function Users() {
  const { data, loading, reload } = useFetch(() => api.get('/v1/admin/users'));
  const { data: roleList } = useFetch(() => api.get('/v1/admin/roles'));
  const rows = data || [];

  const roleOpts = useMemo(() => (roleList || []).map((r) => ({ label: `${r.roleCode}`, value: r.roleCode })), [roleList]);

  const [modal, setModal] = useState(null); // { id, roles:[] }
  const [saving, setSaving] = useState(false);

  const openAssign = (u) => setModal({ id: u.id, roles: u.roles || [] });
  const save = async () => {
    setSaving(true);
    try {
      await api.put(`/v1/admin/users/${modal.id}/roles`, { roleCodes: modal.roles });
      message.success('用户角色已更新');
      setModal(null);
      reload();
    } catch (e) { message.error(e.message); }
    finally { setSaving(false); }
  };

  const cols = [
    { title: '用户ID', dataIndex: 'id' },
    { title: '手机号', dataIndex: 'phone' },
    { title: '姓名', dataIndex: 'fullName', render: (v) => v || '-' },
    { title: 'KYC', dataIndex: 'kycStatus', render: (v) => <Tag color={kycColor[v] || 'default'}>{v || '-'}</Tag> },
    { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v}</Tag> },
    { title: '语言', dataIndex: 'locale' },
    {
      title: '角色包',
      dataIndex: 'roles',
      render: (v) =>
        Array.isArray(v) && v.length ? (
          <Space size={[2, 2]} wrap>
            {v.map((r) => (<Tag key={r} color="geekblue">{r}</Tag>))}
          </Space>
        ) : ('-'),
    },
    {
      title: '操作',
      render: (_, r) => <Button size="small" type="link" onClick={() => openAssign(r)}>分配角色</Button>,
    },
  ];
  return (
    <PageCard title="平台用户（含角色包）" reload={reload} loading={loading}>
      <Table rowKey="id" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
      <Modal title="分配 / 调整角色" open={!!modal} onCancel={() => setModal(null)} onOk={save} confirmLoading={saving}>
        <p style={{ color: 'var(--muted)', fontSize: 12 }}>为用户增删改角色（覆盖式保存）。</p>
        <Select mode="multiple" style={{ width: '100%' }} value={modal?.roles || []}
          onChange={(v) => setModal((m) => ({ ...m, roles: v }))} options={roleOpts} placeholder="选择角色" />
      </Modal>
    </PageCard>
  );
}
