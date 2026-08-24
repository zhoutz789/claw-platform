import { useState, useEffect } from 'react';
import { Table, Input, Button, Space, Tag, App } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';

// 金边中心坐标，用于"附近站点"演示
const PHNOM_PENH = { lat: 11.5564, lng: 104.9282 };

export default function Stations() {
  const { message } = App.useApp();
  const [sku, setSku] = useState('');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);

  const loadNearby = async () => {
    setLoading(true);
    try {
      const d = await api.get(
        `/v1/stations/nearby?lat=${PHNOM_PENH.lat}&lng=${PHNOM_PENH.lng}&radiusKm=50`
      );
      setRows(d || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const onSearch = async () => {
    if (!sku.trim()) {
      loadNearby();
      return;
    }
    setLoading(true);
    try {
      const d = await api.get(`/v1/stations/search?sku=${encodeURIComponent(sku.trim())}`);
      setRows(d || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadNearby();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const cols = [
    { title: 'ID', dataIndex: 'id' },
    { title: '站点编码', dataIndex: 'code' },
    { title: '名称', dataIndex: 'name' },
    { title: '区域', dataIndex: 'area' },
    { title: '城市', dataIndex: 'city' },
    { title: '营业时间', dataIndex: 'openHours' },
    { title: '距离(km)', dataIndex: 'distKm', render: (v) => (v == null ? '-' : Number(v).toFixed(1)) },
    { title: '库存', dataIndex: 'totalStock' },
    {
      title: '电池型号',
      dataIndex: 'categories',
      render: (v) =>
        Array.isArray(v) ? v.map((c) => <Tag key={c}>{c}</Tag>) : '-',
    },
  ];

  return (
    <PageCard
      title="换电站点"
      loading={loading}
      extra={
        <Space>
          <Input
            placeholder="按 SKU 搜索站点 / 电池"
            value={sku}
            onChange={(e) => setSku(e.target.value)}
            onPressEnter={onSearch}
            style={{ width: 240 }}
          />
          <Button type="primary" onClick={onSearch}>
            搜索
          </Button>
          <Button onClick={loadNearby}>附近站点</Button>
        </Space>
      }
    >
      <Table rowKey="id" loading={loading} columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
    </PageCard>
  );
}
