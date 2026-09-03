import { useState, useEffect } from 'react';
import { Table, Input, Button, Space, Tag, App } from 'antd';
import PageCard from '../components/PageCard';
import { ScopeBanner, useCurrentScope } from '../components/inventoryShared';
import api from '../api';
import { useTranslation } from 'react-i18next';

// 金边中心坐标，用于"附近站点"演示
const PHNOM_PENH = { lat: 11.5564, lng: 104.9282 };

export default function Stations() {  const { t } = useTranslation('common');

  const { message } = App.useApp();
  const scope = useCurrentScope();
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
    { title: t('common:m891'), dataIndex: 'code' },
    { title: t('common:m277'), dataIndex: 'name' },
    { title: t('common:m892'), dataIndex: 'area' },
    { title: t('common:m893'), dataIndex: 'city' },
    { title: t('common:m894'), dataIndex: 'openHours' },
    { title: t('common:m895'), dataIndex: 'distKm', render: (v) => (v == null ? '-' : Number(v).toFixed(1)) },
    { title: t('common:m896'), dataIndex: 'totalStock' },
    {
      title: t('common:m897'),
      dataIndex: 'categories',
      render: (v) =>
        Array.isArray(v) ? v.map((c) => <Tag key={c}>{c}</Tag>) : '-',
    },
  ];

  return (
    <PageCard
      title={t('common:m898')}
      loading={loading}
      extra={
        <Space>
          <Input
            placeholder={t('common:m899')}
            value={sku}
            onChange={(e) => setSku(e.target.value)}
            onPressEnter={onSearch}
            style={{ width: 240 }}
          />
          <Button type="primary" onClick={onSearch}>{t('common:m900')}</Button>
          <Button onClick={loadNearby}>{t('common:m901')}</Button>
        </Space>
      }
    >
      <ScopeBanner scope={scope} />
      <Table rowKey="id" loading={loading} columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
    </PageCard>
  );
}
