import { useState, useEffect } from 'react';
import {
  Card, Select, Switch, Form, Button, Tag, Space, Alert, Typography, message, Empty, Spin,
} from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';

const { Text, Paragraph } = Typography;

// 赋权矩阵项（与 DeviceDetail 共享赋权 Tab 的最小权限模型风格保持一致）
const AUTH_ITEMS = [
  { key: 'use', label: '开关 / 使用', desc: '允许设备开关与基础使用' },
  { key: 'locate', label: '实时定位', desc: '实时位置对承租人可见（不隐藏）' },
  { key: 'revenue', label: '收益数据', desc: '向承租人展示收益数据' },
  { key: 'ownership', label: '产权 / 转让', desc: '允许产权转让 / 变更' },
];

// 赋权管理：列出真实资产（GET /v1/assets），选中资产后展示其赋权开关面板；
// 开关使用本地 state 管理，保存后端待接入。
export default function Authorization() {
  const [assets, setAssets] = useState([]);
  const [loading, setLoading] = useState(false);
  const [assetId, setAssetId] = useState(null);
  const [switches, setSwitches] = useState({
    use: true, locate: true, revenue: false, ownership: false,
  });

  useEffect(() => {
    let alive = true;
    setLoading(true);
    api.get('/v1/assets').then((a) => {
      if (!alive) return;
      const arr = a || [];
      setAssets(arr);
      setAssetId((prev) => prev ?? arr[0]?.id ?? null);
    }).catch((e) => {
      if (alive) { message.error('加载真实资产失败：' + e.message); setAssets([]); }
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  const dev = assets.find((d) => d.id === assetId) || assets[0] || null;

  const onSwitch = (key, checked) => setSwitches((s) => ({ ...s, [key]: checked }));

  const save = () => {
    // 真实赋权写入后端待接入；当前仅前端本地 state 管理开关
    message.info('设备赋权保存后端待接入');
  };

  return (
    <PageCard title="赋权管理（所有者可修改设备权限）">
      <Alert type="info" showIcon style={{ marginBottom: 14 }}
        message="赋权管理面向资产所有者：可逐项授予 / 收回设备权限（开关使用 / 实时定位 / 收益数据 / 产权）。权限模型遵循最小权限，承租人仅获所有者勾选的权限。" />

      <Space style={{ marginBottom: 14 }} wrap align="center">
        <Text>选择资产：</Text>
        <Select
          value={assetId}
          style={{ width: 320 }}
          loading={loading}
          onChange={setAssetId}
          options={assets.map((d) => ({ label: `${d.assetNo} · #${d.id}`, value: d.id }))}
          placeholder="选择真实资产"
        />
        {dev && <Tag color="blue">{dev.status || '未知'}</Tag>}
      </Space>

      {loading ? <Spin /> : !dev ? (
        <Empty description="暂无真实资产（后端待接入）" />
      ) : (
        <Card title={`赋权面板 · ${dev.assetNo}`} style={{ marginBottom: 14 }}>
          <Paragraph type="secondary">当前产权人：{dev.ownerId != null ? `用户#${dev.ownerId}` : '平台'}</Paragraph>
          <Form layout="vertical">
            {AUTH_ITEMS.map((it) => (
              <Form.Item key={it.key} label={it.label} tooltip={it.desc}>
                <Space>
                  <Switch checked={!!switches[it.key]} onChange={(c) => onSwitch(it.key, c)} />
                  <Text type="secondary">{it.desc}</Text>
                </Space>
              </Form.Item>
            ))}
            <Button type="primary" onClick={save}>保存赋权</Button>
          </Form>
          <Alert type="warning" showIcon style={{ marginTop: 12 }}
            message="赋权保存接口后端待接入；当前仅在前端本地 state 管理开关，刷新后不持久化。" />
        </Card>
      )}
    </PageCard>
  );
}
