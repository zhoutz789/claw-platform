import { useState, useEffect } from 'react';
import {
  Card, Descriptions, Tag, Button, Space, Input, Select, Table, Steps, Alert, message, Typography, Empty, Spin,
} from 'antd';
import { LinkOutlined, SwapOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';

const { Text, Paragraph } = Typography;

const STATUS_LABEL = {
  IN_STOCK: '在库', IN_USE: '使用中', SHARED: '共享中', REPAIR: '维修中',
  DISABLED: '停用', RETIRED: '退役', RECYCLED: '回收中', SCRAPPED: '已报废',
};
const statusTag = (s) => <Tag color="blue">{STATUS_LABEL[s] || s}</Tag>;

// 后端 TransferType 枚举的合法值；无通用 SALE，支付获得所有权使用 TRADE_IN（交易/买卖）
const TRANSFER_TYPES = [
  'SWAP_EXCHANGE', 'RENTAL_START', 'RENTAL_END', 'SHARED_POOL_ENTRY',
  'SHARED_POOL_EXIT', 'RECOVERY', 'TRADE_IN', 'INITIAL_PURCHASE', 'DEPLOY',
];

// 绑定 / 产权：扫码绑定（仅一次）→ 产权转移（现值核算）→ 租赁（受限权限）
export default function BindOwnership() {
  const [assets, setAssets] = useState([]);
  const [loading, setLoading] = useState(false);
  const [deviceId, setDeviceId] = useState(null);
  const [binding, setBinding] = useState(false);
  const [transferring, setTransferring] = useState(false);
  const [stationId, setStationId] = useState('');
  const [newOwnerId, setNewOwnerId] = useState('2');
  const [transferType, setTransferType] = useState('TRADE_IN');

  useEffect(() => {
    let alive = true;
    setLoading(true);
    api.get('/v1/assets').then((a) => {
      if (!alive) return;
      const arr = a || [];
      setAssets(arr); setDeviceId((prev) => prev ?? arr[0]?.id ?? null);
    }).catch((e) => {
      if (alive) { message.error('加载真实资产失败：' + e.message); setAssets([]); }
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  const dev = assets.find((d) => d.id === deviceId) || assets[0] || null;

  // ① 扫码绑定：POST /v1/assets/{id}/bind
  const doBind = async () => {
    if (!dev) return;
    setBinding(true);
    try {
      await api.post(`/v1/assets/${dev.id}/bind`, {
        assetId: dev.id,
        stationId: stationId ? Number(stationId) : null,
        location: null,
      });
      message.success(`已绑定设备 ${dev.assetNo}`);
    } catch (e) {
      message.error('绑定失败：' + e.message);
    } finally {
      setBinding(false);
    }
  };

  // ② 产权转移：POST /v1/admin/custody/transfers
  const doTransfer = async () => {
    if (!dev) return;
    setTransferring(true);
    try {
      await api.post('/v1/admin/custody/transfers', {
        assetId: dev.id,
        assetType: dev.assetType,
        fromUserId: dev.ownerId,
        toUserId: newOwnerId ? Number(newOwnerId) : null,
        transferType,
        stationId: null,
        swapOrderId: null,
        assetSoh: null,
        assetSoc: null,
        assetCycleCount: null,
      });
      message.success(`已将资产 ${dev.assetNo} 产权转移给用户#${newOwnerId || '?'}`);
    } catch (e) {
      message.error('产权转移失败：' + e.message);
    } finally {
      setTransferring(false);
    }
  };

  // ③ 租赁权限矩阵（权限模型概念，静态保留）
  const leaseMatrix = [
    { fn: '开关 / 使用', lessee: <Tag color="green">✓ 授权</Tag>, note: '产权人勾选授予' },
    { fn: '实时定位', lessee: <Tag color="green">✓ 授权</Tag>, note: '实时可见，不隐藏' },
    { fn: '历史轨迹', lessee: <Tag color="blue">仅本人使用期</Tag>, note: '其余历史隐藏' },
    { fn: '收益数据', lessee: <Tag color="red">✗ 隐藏</Tag>, note: '仅产权人可见' },
    { fn: '产权 / 转让', lessee: <Tag color="red">✗ 禁止</Tag>, note: '仅产权人' },
  ];

  return (
    <PageCard title="绑定 / 产权"
      extra={dev ? (
        <Select value={deviceId} style={{ width: 280 }} onChange={setDeviceId}
          options={assets.map((d) => ({ label: `${d.assetNo} · #${d.id}`, value: d.id }))} />
      ) : null}>
      {!dev ? (
        loading ? <Spin /> : <Empty description="暂无真实资产" />
      ) : (
        <>
          <Alert type="info" showIcon style={{ marginBottom: 16 }}
            message="绑定规则：扫码 / 输唯一编号绑定，仅一次；再绑需产权转移；新用户付款系统核算的当前价值即获所有权，原用户丧失一切权利；租赁仅获产权人勾选权限。现值依赖真实购入成本，后端暂无 costPrice 字段，故以资产状态与建档时间展示。" />

          {/* ① 扫码绑定 */}
          <Card style={{ marginBottom: 16 }} title="① 用户添加新设备（绑定）">
            <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 0 }}>
              扫码设备二维码 / 输入唯一编号 → 绑定（全局唯一，仅一次）。当前选中：<Text strong>{dev.assetNo}</Text> #{dev.id}
            </Paragraph>
            <Space style={{ width: '100%' }} wrap>
              <Input prefix={<LinkOutlined />} style={{ maxWidth: 320 }} value={dev.assetNo} disabled />
              <Input style={{ maxWidth: 180 }} placeholder="站点ID(可选)" addonBefore="站点"
                value={stationId} onChange={(e) => setStationId(e.target.value)} />
              <Button type="primary" loading={binding} onClick={doBind}>绑定设备</Button>
              <Text type="secondary">仅一次；再绑需产权转移</Text>
            </Space>
          </Card>

          {/* ② 产权转移 */}
          <Card style={{ marginBottom: 16 }} title="② 产权转移（再绑定）">
            <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 0 }}>
              新用户支付设备当前价值 → 扫码 + 付款即拥有所有权
            </Paragraph>
            <Alert type="warning" showIcon style={{ margin: '8px 0 12px' }}
              message="现值核算依赖真实购入成本——后端资产当前暂无 costPrice 字段，无法核算现值。当前展示资产状态与建档时间：" />
            <Descriptions column={1} size="small" style={{ marginBottom: 12 }}>
              <Descriptions.Item label="资产状态">{statusTag(dev.status)}</Descriptions.Item>
              <Descriptions.Item label="建档时间">{dev.createdAt || '—'}</Descriptions.Item>
              <Descriptions.Item label="当前产权人">{dev.ownerId != null ? `用户#${dev.ownerId}` : '平台'}</Descriptions.Item>
            </Descriptions>
            <Space wrap>
              <Text>新产权人ID：</Text>
              <Input style={{ width: 120 }} value={newOwnerId} onChange={(e) => setNewOwnerId(e.target.value)} placeholder="如 2" />
              <Text>转移类型：</Text>
              <Select style={{ width: 200 }} value={transferType} onChange={setTransferType}
                options={TRANSFER_TYPES.map((t) => ({ label: t, value: t }))} />
              <Button type="primary" loading={transferring} onClick={doTransfer}>支付获得所有权</Button>
            </Space>
          </Card>

          {/* ③ 租赁权限矩阵 */}
          <Card title="③ 租赁（受限权限）">
            <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 0 }}>
              承租人仅获产权人勾选的功能权限，其余信息无权查看
            </Paragraph>
            <Table rowKey="fn" pagination={false} dataSource={leaseMatrix}
              columns={[
                { title: '功能', dataIndex: 'fn' },
                { title: '承租人可见 / 可操作', dataIndex: 'lessee' },
                { title: '说明', dataIndex: 'note' },
              ]} />
          </Card>
        </>
      )}
    </PageCard>
  );
}
