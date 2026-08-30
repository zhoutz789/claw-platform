import { Table, Tag, Space, Modal, Select, Button, Input, message } from 'antd';
import { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import api from '../api';

const kycColor = { VERIFIED: 'green', PENDING: 'orange', UNVERIFIED: 'default', REJECTED: 'red' };

export default function Users() {
  const { t } = useTranslation();
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
      message.success(t('system:users.msg.rolesUpdated'));
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
      message.success(t('system:users.msg.deptUpdated'));
      setDeptModal(null);
      reload();
    } catch (e) { message.error(e.message); }
    finally { setSaving(false); }
  };

  const addDept = async () => {
    if (!newDept.trim()) return;
    try {
      await api.post('/v1/admin/departments', { name: newDept.trim() });
      message.success(t('system:users.msg.deptCreated'));
      setNewDept('');
      reload();
    } catch (e) { message.error(e.message); }
  };

  const cols = [
    { title: t('system:users.col.id'), dataIndex: 'id' },
    { title: t('system:users.col.phone'), dataIndex: 'phone' },
    { title: t('system:users.col.fullName'), dataIndex: 'fullName', render: (v) => v || '-' },
    { title: t('system:users.col.kyc'), dataIndex: 'kycStatus', render: (v) => <Tag color={kycColor[v] || 'default'}>{v || '-'}</Tag> },
    { title: t('system:users.col.status'), dataIndex: 'status', render: (v) => <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v}</Tag> },
    { title: t('system:users.col.locale'), dataIndex: 'locale' },
    { title: t('system:users.col.department'), dataIndex: 'departmentId', render: (v) => <Tag color={v ? 'purple' : 'default'}>{deptName(v)}</Tag> },
    {
      title: t('system:users.col.roles'),
      dataIndex: 'roles',
      render: (v) =>
        Array.isArray(v) && v.length ? (
          <Space size={[2, 2]} wrap>
            {v.map((r) => (<Tag key={r} color="geekblue">{r}</Tag>))}
          </Space>
        ) : ('-'),
    },
    {
      title: t('system:users.col.actions'),
          render: (_, r) => (
            <Space>
              <Perm code="user:update">
                <Button size="small" type="link" onClick={() => openAssign(r)}>{t('system:users.act.assignRoles')}</Button>
              </Perm>
              <Perm code="user:update">
                <Button size="small" type="link" onClick={() => openDept(r)}>{t('system:users.act.setDept')}</Button>
              </Perm>
            </Space>
          ),
        },
  ];
  return (
    <PageCard title={t('system:users.title')} reload={reload} loading={loading}>
      <Space style={{ marginBottom: 12 }}>
        <Input
          placeholder={t('system:users.newDeptPlaceholder')}
          value={newDept}
          onChange={(e) => setNewDept(e.target.value)}
          style={{ width: 200 }}
        />
        <Perm code="department:create">
          <Button type="dashed" onClick={addDept}>{t('system:users.addDept')}</Button>
        </Perm>
      </Space>
      <Table rowKey="id" columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
      <Modal title={t('system:users.modal.assignTitle')} open={!!roleModal} onCancel={() => setRoleModal(null)} onOk={saveRoles} confirmLoading={saving}>
        <p style={{ color: 'var(--muted)', fontSize: 12 }}>{t('system:users.modal.assignHint')}</p>
        <Select mode="multiple" style={{ width: '100%' }} value={roleModal?.roles || []}
          onChange={(v) => setRoleModal((m) => ({ ...m, roles: v }))} options={roleOpts}
          placeholder={t('system:users.modal.assignPlaceholder')} />
      </Modal>
      <Modal title={t('system:users.modal.deptTitle')} open={!!deptModal} onCancel={() => setDeptModal(null)} onOk={saveDept} confirmLoading={saving}>
        <p style={{ color: 'var(--muted)', fontSize: 12 }}>{t('system:users.modal.deptHint')}</p>
        <Select style={{ width: '100%' }} allowClear placeholder={t('system:users.modal.deptPlaceholder')}
          value={deptModal?.departmentId || undefined}
          onChange={(v) => setDeptModal((m) => ({ ...m, departmentId: v || null }))} options={deptOpts} />
      </Modal>
    </PageCard>
  );
}
