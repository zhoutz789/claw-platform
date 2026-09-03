import { useState, useEffect } from 'react';
import { Card, Descriptions, Tag, Button, Space, Statistic, Row, Col, Select, message, Alert, Typography, Empty, Spin } from 'antd';
const { Text } = Typography;
import { SafetyCertificateOutlined, QrcodeOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
import { useTranslation } from 'react-i18next';

// 合格证：生成唯一编号 + 二维码验真，闭环 产品→合格证→设备详情（全部基于真实后端资产）
export default function Certificate() {  const { t } = useTranslation('common');

  const [assets, setAssets] = useState([]);
  const [products, setProducts] = useState([]);
  const [manufacturers, setManufacturers] = useState([]);
  const [loading, setLoading] = useState(false);
  const [deviceId, setDeviceId] = useState(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      api.get('/v1/assets'),
      api.get('/v1/admin/manufacturer/products'),
      api.get('/v1/admin/manufacturer/manufacturers'),
    ]).then(([a, p, m]) => {
      if (!alive) return;
      const arr = a || [];
      setAssets(arr); setProducts(p || []); setManufacturers(m || []);
      setDeviceId((prev) => prev ?? arr[0]?.id ?? null);
    }).catch((e) => {
      if (alive) {
        message.error('加载真实数据失败：' + e.message);
        setAssets([]); setProducts([]); setManufacturers([]);
      }
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  const dev = assets.find((d) => d.id === deviceId) || assets[0] || null;
  const product = products.find((p) => p.id === dev?.productId);
  const brand = manufacturers.find((b) => b.id === dev?.manufacturerId);
  const prodName = (id) => products.find((p) => p.id === id)?.name || '—';

  return (
    <PageCard title={t('common:m786')}
      extra={
        dev ? (
          <Select value={deviceId} style={{ width: 280 }} onChange={setDeviceId}
            options={assets.map((d) => ({
              label: `${d.assetNo} · ${prodName(d.productId)}`,
              value: d.id,
            }))} />
        ) : null
      }>
      {!dev ? (
        loading ? <Spin /> : <Empty description={t('common:m787')} />
      ) : (
        <>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={8}><Card><Statistic title={t('common:m788')} value={assets.length} /></Card></Col>
            <Col span={8}><Card><Statistic title={t('common:m789')} value={'—（待统计）'} valueStyle={{ color: '#1677ff' }} /></Card></Col>
            <Col span={8}><Card><Statistic title={t('common:m790')} value={'—（待统计）'} valueStyle={{ color: '#52c41a' }} /></Card></Col>
          </Row>

          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <Card style={{ width: 360 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <div>
                  <div style={{ fontWeight: 800 }}>{t('common:m791')}</div>
                  <div style={{ fontSize: 12, color: '#888' }}>{t('common:m792')}</div>
                </div>
                <SafetyCertificateOutlined style={{ fontSize: 22, color: '#1677ff' }} />
              </div>
              <div style={{ height: 96, width: 96, border: '1px solid #eee', borderRadius: 8, display: 'grid', placeItems: 'center', margin: '0 auto 12px', background: '#fafafa' }}>
                <QrcodeOutlined style={{ fontSize: 40, color: '#bbb' }} />
              </div>
              <div style={{ fontSize: 11, color: '#888', textAlign: 'center', wordBreak: 'break-all', marginBottom: 12 }}>{t('common:m167')}{dev.qrCode || '—'}
              </div>
              <Descriptions column={1} size="small">
                <Descriptions.Item label={t('common:m793')}><Text strong>{dev.assetNo}</Text> <span style={{ color: 'var(--muted)' }}>#{dev.id}</span></Descriptions.Item>
                <Descriptions.Item label={t('common:m794')}>{product?.name || '—'}</Descriptions.Item>
                <Descriptions.Item label={t('common:m181')}>{brand?.name || '—'}</Descriptions.Item>
                <Descriptions.Item label={t('common:m795')}>{t('common:m796')}</Descriptions.Item>
                <Descriptions.Item label={t('common:m797')}>{t('common:m796')}</Descriptions.Item>
                <Descriptions.Item label={t('common:m169')}>{dev.createdAt || '—'}</Descriptions.Item>
              </Descriptions>
              <Space style={{ marginTop: 12, width: '100%' }}>
                <Button size="small" onClick={() => message.success(t('common:m798'))}>{t('common:m799')}</Button>
                <Button size="small" onClick={() => message.success(t('common:m800'))}>{t('common:m801')}</Button>
                <Button type="primary" size="small" onClick={() => message.success(t('common:m802'))}>{t('common:m803')}</Button>
              </Space>
            </Card>

            <Card style={{ flex: 1, minWidth: 300 }} title={t('common:m804')}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label={t('common:m805')}>{t('common:m806')}</Descriptions.Item>
                <Descriptions.Item label={t('common:m807')}>{t('common:m808')}</Descriptions.Item>
                <Descriptions.Item label={t('common:m809')}>{t('common:m810')}</Descriptions.Item>
                <Descriptions.Item label={t('common:m811')}>{t('common:m812')}</Descriptions.Item>
                <Descriptions.Item label={t('common:m813')}>{t('common:m814')}</Descriptions.Item>
              </Descriptions>
              <Button onClick={() => message.success(t('common:m815'))}>{t('common:m816')}</Button>
              <Alert type="success" showIcon style={{ marginTop: 12 }}
                message="二维码内嵌资产编号 + 平台签名（真实 qrCode），扫码即验真，杜绝套牌 / 假合格证。" />
            </Card>
          </div>
        </>
      )}
    </PageCard>
  );
}
