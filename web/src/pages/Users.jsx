import { Table, Tag, Space, Modal, Select, Button, Input, message } from 'antd';
import { useState, useMemo } from 'react';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';

const kycColor = { VERIFIED: 'green', PENDING: 'orange', UNVERIFIED: 'default', REJECTED: 'red' };

export default function Users() {
  const { data, loading, reload } = useFetch(() => api.get('/v1/admin/users'));
  const { data: roleList } = useFetch(() => api.get('/v1/admin/roles'));
  const { data: deptList } = useFetch(() => api.get('/v1/admin/departments'));
  const rows = data || [];
  const depts = deptList || [];

  const roleOpts = useMemo(() => (roleList || []).map((r) => ({ label: `${r.roleCode}`, value: r.roleCode })), [roleList]);
  const deptOpts = useMemo(() => (depts || []).map((d) => ({ label: d.name, value: d.id })), [depts]);
  const deptName = (id) => (depts.find((d) => d.id === id)?.name) || '-';

  const [roleModal, setRoleModal] = useState(null); // { id, roles:[] }
  const [deptModal, setDeptModal] = useState(null); // { id, departmentId }
  const [newDept, setNewDept] = useState('');
  const [saving, setSaving] = useState(false);

  const openAssign = (u) => setRoleModal({ id: u.id, roles: u.roles || [] });
  const saveRoles = async () => {
    setSaving(true);
    try {
      await api.put(`/v1/admin/users/${roleModal.id}/roles`, { roleCodes: roleModal.roles });
      message.success('用户角色已更新');
      setRoleModal(null);
      reload();
    } catch (e) { message.error(e.message); }
    finally { setSaving(false); }
  };

  const openDept = (u) => setDeptModal({ id: u.id, departmentId: u.departmentId || null });
  const saveDept = async () => {
    setSaving(true);
    try {
      await api.put(`/v1/admin/users/${deptModal.id}/department`, { departmentId: deptModal.departmentId });
      message.success('用户部门已更新（数据范围生效）');
      setDeptModal(null);
      reload();
    } catch (e) { message.error(e.message); }
    finally { setSaving(false); }
  };

  const addDept = async () => {
    if (!newDept.trim()) return;
    try {
      await api.post('/v1/admin/departments', { name: newDept.trim() });
      message.success('部门已创建');
      setNewDept('');
      reload();
    } catch (e) { message.error(e.message); }
  };

  const cols = [
    { title: '用户ID', dataIndex: 'id' },
    { title: '手机号', dataIndex: 'phone' },
    { title: '姓名', dataIndex: 'fullName', render: (v) => v || '-' },
    { title: 'KYC', dataIndex: 'kycStatus', render: (v) => <Tag color={kycColor[v] || 'default'}>{v || '-'}</Tag> },
    { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v}</Tag> },
    { title: '语言', dataIndex: 'locale' },
    { title: '部门', dataIndex: 'departmentId', render: (v) => <Tag color={v ? 'purple' : 'default'}>{deptName(v)}</Tag> },
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
      render: (_, r) => (
        <Space>
          <Button size="small" type="link" onClick={() => openAssign(r)}>分配角色</Button>
          <Button size="small" type="link" onClick={() => openDept(r)}>设部门</Button>
        </Space>
      ),
    },
  ];
  return (
    <PageCard title="平台用户（含角色包 / 数据范围）" reload={reload} loading={loading}>
      <Space style={{ marginBottom: 12 }}>
        <Input placeholder="新建部门名称" value={newDept} onChange={(e) => setNewDept(e.target.value)} style={{ width: 200 }} />
        <Button type="dashed" onClick={addDept}>新建部门</Button>
      </Space>
      <Table rowKey="id" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
      <Modal title="分配 / 调整角色" open={!!roleModal} onCancel={() => setRoleModal(null)} onOk={saveRoles} confirmLoading={saving}>
        <p style={{ color: 'var(--muted)', fontSize: 12 }}>为用户增删改角色（覆盖式保存）。</p>
        <Select mode="multiple" style={{ width: '100%' }} value={roleModal?.roles || []}
          onChange={(v) => setRoleModal((m) => ({ ...m, roles: v }))} options={roleOpts} placeholder="选择角色" />
      </Modal>
      <Modal title="设置所属部门（数据范围维度）" open={!!deptModal} onCancel={() => setDeptModal(null)} onOk={saveDept} confirmLoading={saving}>
        <p style={{ color: 'var(--muted)', fontSize: 12 }}>设为某部门后，该用户若具 DEPARTMENT 角色将仅见同部门数据。</p>
        <Select style={{ width: '100%' }} allowClear placeholder="选择部门" value={deptModal?.departmentId || undefined}
          onChange={(v) => setDeptModal((m) => ({ ...m, departmentId: v || null }))} options={deptOpts} />
      </Modal>
    </PageCard>
  );
}
