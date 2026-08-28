import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Button, Tag, Space, message, Spin, Empty } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';

/* 商品列表（D 期）——商品 = products（厂家发布）。对照 increment3-d-goods-wizard.html 的商品列表区。 */
export default function GoodsList() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [goods, setGoods] = useState([]);

  const load = () => {
    setLoading(true);
    api.get('/v1/admin/manufacturer/products')
      .then((list) => setGoods(Array.isArray(list) ? list : []))
      .catch((e) => { message.error('加载商品失败：' + (e.message || '未知')); setGoods([]); })
      .finally(() => setLoading(false));
  };
  useEffect(() => { load(); }, []);

  const del = (id) => {
    if (!window.confirm('确认删除该商品？')) return;
    api.delete('/v1/admin/manufacturer/products/' + id)
      .then(() => { message.success('已删除'); setGoods(goods.filter((g) => g.id !== id)); })
      .catch((e) => message.error('删除失败：' + (e.message || '未知')));
  };

  const copyShare = (g) => {
    const link = g.shareCode ? ('https://claw.app/g/' + g.shareCode) : '（尚未生成分享码，编辑后可生成）';
    navigator.clipboard?.writeText(link).then(() => message.success('分享链接已复制')).catch(() => message.info(link));
  };

  return (
    <PageCard title="商品列表" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/product-wizard')}>+ 发布商品</Button>}>
      <Spin spinning={loading}>
        <Table
          rowKey="id"
          dataSource={goods}
          pagination={{ pageSize: 10 }}
          locale={{ emptyText: <Empty description="暂无商品，点右上「发布商品」新建" /> }}
          columns={[
            { title: '商品ID', dataIndex: 'id', width: 90 },
            { title: '标题', dataIndex: 'name', render: (v) => <b>{v || '—'}</b> },
            { title: '产品', dataIndex: 'name' },
            { title: '类别', dataIndex: 'category', render: (v) => v || '—' },
            { title: '品牌', dataIndex: 'brand', render: (v) => v || '—' },
            { title: '状态', dataIndex: 'status', render: (v) => <Tag color="green">{v || '在售'}</Tag> },
            {
              title: '操作', render: (_, g) => (
                <Space>
                  <Button size="small" icon={<EditOutlined />} onClick={() => navigate('/product-wizard?editId=' + g.id)}>编辑</Button>
                  <Button size="small" onClick={() => copyShare(g)}>复制分享</Button>
                  <Button size="small" danger icon={<DeleteOutlined />} onClick={() => del(g.id)}>删除</Button>
                </Space>
              ),
            },
          ]}
        />
      </Spin>
    </PageCard>
  );
}
