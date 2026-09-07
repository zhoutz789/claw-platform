import { useMemo, useState } from 'react';
import { Tree, Button, Modal, Form, Input, InputNumber, Space, Popconfirm, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useFetch } from '../hooks';
import PageCard from '../components/PageCard';
import api from '../api';
import * as catApi from '../api/category';

/**
 * 类别管理（② 通用多级商品分类树）。
 * 树形展示 + 新建根分类 / 新建子分类 / 改名改父 / 软删除。
 * 写操作受后端 category:* 权限位控制；无权限时按钮仍可见但接口返回 403，
 * 由 message 提示（与既有页面同口径）。
 */
export default function CategoryManage() {
  const { data, loading, reload } = useFetch(() => catApi.categoryTree());
  const [form] = Form.useForm();
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(null); // { mode, parentId, node? }

  const treeData = useMemo(() => {
    const map = (nodes) =>
      (nodes || []).map((n) => ({
        key: n.id,
        title: (
          <Space size={8}>
            <span>
              {n.name}
              {n.code ? <span style={{ color: '#999', marginLeft: 6, fontSize: 12 }}>({n.code})</span> : null}
            </span>
            <Button
              type="link"
              size="small"
              icon={<PlusOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                openModal({ mode: 'create-child', parentId: n.id });
              }}
            >
              子分类
            </Button>
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                openModal({ mode: 'edit', parentId: n.parentId, node: n });
              }}
            >
              编辑
            </Button>
            <Popconfirm
              title="确认删除该分类？"
              description="含子分类时将被拒绝，需先删除子节点。"
              onConfirm={(e) => {
                e?.stopPropagation();
                doDelete(n.id);
              }}
              onCancel={(e) => e?.stopPropagation()}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={(e) => e.stopPropagation()}
              >
                删除
              </Button>
            </Popconfirm>
          </Space>
        ),
        children: map(n.children),
      }));
    return map(data || []);
  }, [data]);

  const openModal = (payload) => {
    setEditing(payload);
    form.resetFields();
    if (payload.mode === 'edit' && payload.node) {
      form.setFieldsValue({ name: payload.node.name, sortNo: payload.node.sortNo });
    } else {
      form.setFieldsValue({ sortNo: 0 });
    }
    setOpen(true);
  };

  const doDelete = async (id) => {
    try {
      await catApi.deleteCategory(id);
      message.success('已删除');
      reload();
    } catch (e) {
      message.error(e?.response?.data?.message || '删除失败');
    }
  };

  const handleOk = async () => {
    const values = await form.validateFields();
    try {
      if (editing.mode === 'edit') {
        await catApi.updateCategory(editing.node.id, {
          name: values.name,
          sortNo: values.sortNo,
          parentId: editing.parentId ?? null,
        });
        message.success('已保存');
      } else {
        await catApi.createCategory({
          name: values.name,
          sortNo: values.sortNo,
          parentId: editing.parentId ?? null,
        });
        message.success('已创建');
      }
      setOpen(false);
      reload();
    } catch (e) {
      message.error(e?.response?.data?.message || '操作失败');
    }
  };

  return (
    <PageCard
      title="类别管理（商品多级分类树）"
      reload={reload}
      loading={loading}
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal({ mode: 'create-root', parentId: null })}>
          新建根分类
        </Button>
      }
    >
      {treeData.length === 0 && !loading ? (
        <div style={{ color: '#999', padding: 24 }}>暂无分类，点击右上角「新建根分类」开始建立分类树。</div>
      ) : (
        <Tree
          treeData={treeData}
          defaultExpandAll
          blockNode
          selectable={false}
          style={{ background: '#fff', padding: 12, borderRadius: 8 }}
        />
      )}
      <Modal
        open={open}
        title={
          editing?.mode === 'edit'
            ? '编辑分类'
            : editing?.mode === 'create-child'
            ? '新建子分类'
            : '新建根分类'
        }
        onOk={handleOk}
        onCancel={() => setOpen(false)}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="name" label="分类名称" rules={[{ required: true, message: '请输入分类名称' }]}>
            <Input placeholder="如：光伏组件 / 二轮电动车 / 农业植保无人机" maxLength={120} />
          </Form.Item>
          <Form.Item name="sortNo" label="同级排序（越小越靠前）">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          {editing?.mode === 'create-child' ? (
            <Form.Item label="父分类">
              <Input value={editing?.node?.name} disabled />
            </Form.Item>
          ) : null}
        </Form>
      </Modal>
    </PageCard>
  );
}
