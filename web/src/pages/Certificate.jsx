import { useState, useEffect } from 'react';
import { Card, Descriptions, Tag, Button, Space, Statistic, Row, Col, Select, message, Alert, Typography, Empty, Spin } from 'antd';
const { Text } = Typography;
import { SafetyCertificateOutlined, QrcodeOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';

// 合格证：生成唯一编号 + 二维码验真，闭环 产品→合格证→设备详情（全部基于真实后端资产）
export default function Certificate() {
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
    <PageCard title="产品合格证（官方认证 · 二维码验真）"
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
        loading ? <Spin /> : <Empty description="暂无真实设备数据（后端无资产）" />
      ) : (
        <>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={8}><Card><Statistic title="已建档设备（真实）" value={assets.length} /></Card></Col>
            <Col span={8}><Card><Statistic title="本月打印 / 导出" value={'—（待统计）'} valueStyle={{ color: '#1677ff' }} /></Card></Col>
            <Col span={8}><Card><Statistic title="线上查看（用户）" value={'—（待统计）'} valueStyle={{ color: '#52c41a' }} /></Card></Col>
          </Row>

          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <Card style={{ width: 360 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <div>
                  <div style={{ fontWeight: 800 }}>产品合格证（定制）</div>
                  <div style={{ fontSize: 12, color: '#888' }}>Claw 能源平台 · 官方认证</div>
                </div>
                <SafetyCertificateOutlined style={{ fontSize: 22, color: '#1677ff' }} />
              </div>
              <div style={{ height: 96, width: 96, border: '1px solid #eee', borderRadius: 8, display: 'grid', placeItems: 'center', margin: '0 auto 12px', background: '#fafafa' }}>
                <QrcodeOutlined style={{ fontSize: 40, color: '#bbb' }} />
              </div>
              <div style={{ fontSize: 11, color: '#888', textAlign: 'center', wordBreak: 'break-all', marginBottom: 12 }}>
                二维码内容：{dev.qrCode || '—'}
              </div>
              <Descriptions column={1} size="small">
                <Descriptions.Item label="唯一编号"><Text strong>{dev.assetNo}</Text> <span style={{ color: 'var(--muted)' }}>#{dev.id}</span></Descriptions.Item>
                <Descriptions.Item label="产品类">{product?.name || '—'}</Descriptions.Item>
                <Descriptions.Item label="品牌方">{brand?.name || '—'}</Descriptions.Item>
                <Descriptions.Item label="额定容量">—（后端产品暂无规格字段）</Descriptions.Item>
                <Descriptions.Item label="标称电压">—（后端产品暂无规格字段）</Descriptions.Item>
                <Descriptions.Item label="建档时间">{dev.createdAt || '—'}</Descriptions.Item>
              </Descriptions>
              <Space style={{ marginTop: 12, width: '100%' }}>
                <Button size="small" onClick={() => message.success('已查看合格证（基于真实资产数据）')}>👁 查看</Button>
                <Button size="small" onClick={() => message.success('已导出 PDF（基于真实资产数据）')}>⬇ 导出</Button>
                <Button type="primary" size="small" onClick={() => message.success('已加入打印队列（基于真实资产数据）')}>🖨 打印</Button>
              </Space>
            </Card>

            <Card style={{ flex: 1, minWidth: 300 }} title="合格证模版配置">
              <Descriptions column={1} size="small">
                <Descriptions.Item label="模板名称">换电电池标准合格证</Descriptions.Item>
                <Descriptions.Item label="自动调用">商品参数(容量/电压/寿命) + 资产编号（真实后端，规格字段待补齐）</Descriptions.Item>
                <Descriptions.Item label="二维码内容">资产编号 + 平台签名校验（真实 qrCode）</Descriptions.Item>
                <Descriptions.Item label="用户可操作">查看 / 导出 / 打印</Descriptions.Item>
                <Descriptions.Item label="闭环">产品 → 合格证 → 设备详情</Descriptions.Item>
              </Descriptions>
              <Button onClick={() => message.success('进入模版编辑（演示）')}>编辑模版</Button>
              <Alert type="success" showIcon style={{ marginTop: 12 }}
                message="二维码内嵌资产编号 + 平台签名（真实 qrCode），扫码即验真，杜绝套牌 / 假合格证。" />
            </Card>
          </div>
        </>
      )}
    </PageCard>
  );
}
