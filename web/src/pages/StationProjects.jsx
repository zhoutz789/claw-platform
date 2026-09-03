import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Form, Input, InputNumber, Modal, Select, Space, Table, Tree, Tag,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, LinkOutlined, ReloadOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EMPTY, fmtTime, useSupplyOptions } from '../components/supplyShared';
import { ScopeBanner } from '../components/inventoryShared';
import {
  listStationProjects, createStationProject, updateStationProject, deleteStationProject,
  listStationProjectAllocs, allocStationInventory, deallocStationInventory,
  listStationInventory, getStationScope,
} from '../api/station';

/**
 * 服务站项目层页面（模块四 · ②）。
 *
 * 左：站下项目树（Tree）；右：项目信息 + 占用库存表（挂载/解除）+ 可用量展示。
 * 占用只写 alloc 表（BC-3）：可用量由后端读时计算（stockQty − Σallocated）下发，前端直接展示。
 * 写操作由 <Perm> 门控（station:project:manage / alloc）。
 */
export default function StationProjects() {
  const { t } = useTranslation(['common', 'station']);
  const { message } = App.useApp();
  const { stationOptions, stationName } = useSupplyOptions();

  const [scope, setScope] = useState(null);
  const [stationId, setStationId] = useState(undefined);
  const [tree, setTree] = useState([]);
  const [treeLoading, setTreeLoading] = useState(false);

  const [selected, setSelected] = useState(null); // 选中的项目节点
  const [allocs, setAllocs] = useState([]);
  const [allocLoading, setAllocLoading] = useState(false);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState(null); // 非 null 表示编辑模式
  const [form] = Form.useForm();

  const [allocOpen, setAllocOpen] = useState(false);
  const [allocForm] = Form.useForm();
  const [stockOptions, setStockOptions] = useState([]);
  const [submitting, setSubmitting] = useState(false);

  // 作用域解析：决定可选服务站与默认站。
  useEffect(() => {
    getStationScope()
      .then((v) => {
        setScope(v);
        if (v && Array.isArray(v.allowedStationIds) && v.allowedStationIds.length === 1) {
          setStationId(v.allowedStationIds[0]);
        }
      })
      .catch(() => setScope(null));
  }, []);

  const effectiveStations = useMemo(() => {
    if (!scope || !Array.isArray(scope.allowedStationIds)) return stationOptions;
    const set = new Set(scope.allowedStationIds);
    return stationOptions.filter((o) => set.has(o.value));
  }, [scope, stationOptions]);

  const bannerScope = useMemo(() => (scope ? {
    scopeLevel: scope.level,
    principalId: scope.overrideStationId,
    subordinateStationIds: scope.allowedStationIds || [],
  } : null), [scope]);

  const loadTree = useCallback(async () => {
    setTreeLoading(true);
    try {
      const data = await listStationProjects();
      setTree(Array.isArray(data) ? data : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setTree([]);
    } finally {
      setTreeLoading(false);
    }
  }, [message, t]);

  useEffect(() => { loadTree(); }, [loadTree]);

  // 递归拍平 + 建 id→node 映射，供 Tree 渲染与选中取数。
  const { treeData, nodeMap } = useMemo(() => {
    const map = new Map();
    const toData = (nodes) => (nodes || []).map((n) => {
      map.set(n.id, n);
      return {
        key: n.id,
        title: (
          <span>
            {n.name}
            {n.status === 'ARCHIVED' && <Tag style={{ marginLeft: 6 }}>{t('station:project.statusArchived')}</Tag>}
          </span>
        ),
        children: toData(n.children),
      };
    });
    return { treeData: toData(tree), nodeMap: map };
  }, [tree, t]);

  const loadAllocs = useCallback(async (projectId) => {
    setAllocLoading(true);
    try {
      const data = await listStationProjectAllocs(projectId);
      setAllocs(Array.isArray(data) ? data : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setAllocs([]);
    } finally {
      setAllocLoading(false);
    }
  }, [message, t]);

  const onSelect = (keys) => {
    const id = keys && keys[0];
    if (!id) { setSelected(null); setAllocs([]); return; }
    const node = nodeMap.get(Number(id));
    setSelected(node || null);
    if (node) loadAllocs(node.id);
  };

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    // 新建默认落到当前默认站（STATION 用户即自身站）
    form.setFieldsValue({ stationId });
    setFormOpen(true);
  };

  const openEdit = (node) => {
    setEditing(node);
    form.setFieldsValue({
      stationId: node.stationId,
      name: node.name,
      parentId: node.parentId || undefined,
      sortNo: node.sortNo || 0,
    });
    setFormOpen(true);
  };

  const submitForm = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const body = {
        stationId: Number(values.stationId),
        name: values.name,
        parentId: values.parentId || null,
        sortNo: values.sortNo || 0,
      };
      if (editing) {
        await updateStationProject(editing.id, {
          name: body.name, parentId: body.parentId, sortNo: body.sortNo,
        });
      } else {
        await createStationProject(body);
      }
      message.success(t('msg.success'));
      setFormOpen(false);
      loadTree();
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const onDelete = async (node) => {
    if (!window.confirm(`${t('station:project.delete')}：${node.name}？`)) return;
    try {
      await deleteStationProject(node.id);
      message.success(t('msg.success'));
      if (selected && selected.id === node.id) { setSelected(null); setAllocs([]); }
      loadTree();
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    }
  };

  const openAlloc = async (node) => {
    setSelected(node);
    allocForm.resetFields();
    setAllocOpen(true);
    try {
      const stock = await listStationInventory({ stationId: node.stationId });
      setStockOptions((Array.isArray(stock) ? stock : []).map((s) => ({
        label: `${s.skuCode || EMPTY}（${t('station:inventory.col.stockQty')} ${s.stockQty ?? 0}）`,
        value: s.id,
      })));
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setStockOptions([]);
    }
  };

  const submitAlloc = async () => {
    const values = await allocForm.validateFields();
    setSubmitting(true);
    try {
      await allocStationInventory(selected.id, {
        stationStockId: Number(values.stationStockId),
        qty: Number(values.qty),
        note: values.note || null,
      });
      message.success(t('msg.success'));
      setAllocOpen(false);
      loadAllocs(selected.id);
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const onDealloc = async (allocId) => {
    try {
      await deallocStationInventory(allocId);
      message.success(t('msg.success'));
      if (selected) loadAllocs(selected.id);
    } catch (e) {
      message.error(e.message || t('msg.failed'));
    }
  };

  const allocColumns = [
    { title: t('station:project.col.skuCode'), dataIndex: 'skuCode', width: 160, render: (v) => v || EMPTY },
    {
      title: t('station:project.col.allocatedQty'),
      dataIndex: 'allocatedQty',
      width: 120,
      render: (v) => (v == null ? EMPTY : v),
    },
    {
      title: t('station:project.col.availableQty'),
      dataIndex: 'availableQty',
      width: 120,
      render: (v) => (v == null ? EMPTY : <Tag color="blue">{v}</Tag>),
    },
    { title: t('station:project.col.note'), dataIndex: 'note', width: 160, render: (v) => v || EMPTY },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 160,
      fixed: 'right',
      render: (_, r) => (
        <Perm code="station:project:alloc">
          <Button size="small" type="link" danger onClick={() => onDealloc(r.id)}>
            {t('station:project.dealloc')}
          </Button>
        </Perm>
      ),
    },
  ];

  const parentOptions = useMemo(() => {
    const opts = (tree || []).map((n) => ({ label: n.name, value: n.id }));
    if (editing) return opts.filter((o) => o.value !== editing.id);
    return opts;
  }, [tree, editing]);

  return (
    <PageCard
      title={t('station:project.title')}
      subtitle={t('station:project.subtitle')}
      extra={
        <Space>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('station:inventory.selectStation')}
            style={{ width: 200 }}
            options={effectiveStations}
            value={stationId}
            onChange={setStationId}
          />
          <Button icon={<ReloadOutlined />} onClick={loadTree} loading={treeLoading}>
            {t('action.refresh')}
          </Button>
        </Space>
      }
    >
      <ScopeBanner scope={bannerScope} stationName={stationName} />

      {effectiveStations.length === 0 && (
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('station:inventory.noStation')} />
      )}

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        {/* 左：项目树 */}
        <div style={{ width: 320, flex: '0 0 320px', border: '1px solid #f0f0f0', borderRadius: 8, padding: 12 }}>
          <div style={{ marginBottom: 8 }}>
            <Perm code="station:project:manage">
              <Button type="primary" icon={<PlusOutlined />} size="small" onClick={openCreate}>
                {t('station:project.create')}
              </Button>
            </Perm>
          </div>
          {treeData.length === 0 ? (
            <div style={{ color: '#999' }}>{t('station:project.treeEmpty')}</div>
          ) : (
            <Tree
              treeData={treeData}
              onSelect={onSelect}
              defaultExpandAll
              style={{ maxHeight: 520, overflow: 'auto' }}
            />
          )}
        </div>

        {/* 右：项目详情 + 占用 */}
        <div style={{ flex: 1, minWidth: 0 }}>
          {!selected ? (
            <Alert type="info" showIcon message={t('station:project.treeEmpty')} />
          ) : (
            <>
              <div style={{ marginBottom: 12 }}>
                <Space wrap>
                  <strong>{selected.name}</strong>
                  <Tag color={selected.status === 'ACTIVE' ? 'green' : 'default'}>
                    {selected.status === 'ACTIVE' ? t('station:project.statusActive') : t('station:project.statusArchived')}
                  </Tag>
                  <span>{t('station:project.col.stationId')}：{stationName(selected.stationId)}</span>
                  <Perm code="station:project:manage">
                    <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(selected)}>
                      {t('station:project.edit')}
                    </Button>
                    <Button size="small" icon={<DeleteOutlined />} danger onClick={() => onDelete(selected)}>
                      {t('station:project.delete')}
                    </Button>
                  </Perm>
                </Space>
              </div>

              <div style={{ marginBottom: 12 }}>
                <Perm code="station:project:alloc">
                  <Button
                    type="primary"
                    icon={<LinkOutlined />}
                    onClick={() => openAlloc(selected)}
                  >
                    {t('station:project.alloc')}
                  </Button>
                </Perm>
              </div>

              <Table
                rowKey="id"
                loading={allocLoading}
                dataSource={allocs}
                columns={allocColumns}
                size="middle"
                locale={{ emptyText: t('station:inventory.empty') }}
                scroll={{ x: 'max-content' }}
                pagination={{ pageSize: 10, showSizeChanger: true }}
              />
            </>
          )}
        </div>
      </div>

      {/* 新建 / 编辑项目 */}
      <Modal
        title={editing ? t('station:project.edit') : t('station:project.create')}
        open={formOpen}
        onOk={submitForm}
        confirmLoading={submitting}
        onCancel={() => setFormOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="stationId" label={t('station:project.col.stationId')} rules={[{ required: true }]}>
            <Select options={effectiveStations} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="name" label={t('station:project.newName')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="parentId" label={t('station:project.parent')}>
            <Select allowClear options={parentOptions} placeholder={t('station:project.parent')} />
          </Form.Item>
          <Form.Item name="sortNo" label={t('station:project.sortNo')} initialValue={0}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 占用库存 */}
      <Modal
        title={t('station:project.allocTitle')}
        open={allocOpen}
        onOk={submitAlloc}
        confirmLoading={submitting}
        onCancel={() => setAllocOpen(false)}
        destroyOnClose
      >
        <Form form={allocForm} layout="vertical">
          <Form.Item name="stationStockId" label={t('station:project.selectStock')} rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={stockOptions}
              placeholder={t('station:project.selectStock')}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item name="qty" label={t('station:project.qty')} rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="note" label={t('station:project.note')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}
