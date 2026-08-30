import { Row, Col, Card, Statistic, Table, Tag, Spin, Typography, Divider } from 'antd';
import { useFetch } from '../hooks';
import api from '../api';
import { ASSET_STATUS_LABEL, enumLabel } from '../enums';

const { Text } = Typography;

export default function Dashboard() {
  const { data, loading } = useFetch(() => api.get('/v1/admin/dashboard'));
  const totalSwap = data?.totalSwapOrders || 0;
  const ready = data?.readyBatteries || 0;
  const charging = data?.chargingBatteries || 0;
  const online = ready + charging;

  const sourceCols = [
    { title: '指标', dataIndex: 'label' },
    { title: '来源表', dataIndex: 'sourceTable', render: (v) => <Tag color="geekblue">{v}</Tag> },
    { title: '口径', dataIndex: 'note' },
  ];
  const stageCols = [
    { title: '状态', dataIndex: 'k', render: (v) => enumLabel(ASSET_STATUS_LABEL, v) },
    { title: '数量', dataIndex: 'v' },
  ];
  const stageData = data?.assetByStage ? Object.entries(data.assetByStage).map(([k, v]) => ({ k, v })) : [];

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <span style={{ fontSize: 12, color: 'var(--muted)' }}>实时概览 · 全部指标绑定真实库表（见下方「数据来源口径」）</span>
        <span style={{ fontSize: 12, color: 'var(--muted)' }}>
          {new Date().toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })} 实时
        </span>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}><Card><Statistic title="累计换电订单" value={totalSwap} valueStyle={{ color: 'var(--brand)' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="在线电池(满电+充电)" value={online} suffix={`(满${ready}/充${charging})`} /></Card></Col>
        <Col span={6}><Card><Statistic title="资产总数" value={data?.assetCount || 0} /></Card></Col>
        <Col span={6}><Card><Statistic title="累计采购额" value={data?.purchaseTotal || 0} precision={2} prefix="$" /></Card></Col>
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}><Card><Statistic title="厂家数" value={data?.manufacturerCount || 0} /></Card></Col>
        <Col span={6}><Card><Statistic title="商品数" value={data?.productCount || 0} /></Card></Col>
        <Col span={6}><Card><Statistic title="SKU 数" value={data?.skuCount || 0} /></Card></Col>
        <Col span={6}><Card><Statistic title="三专户(个)" value={(data?.escrowAccounts || []).length} /></Card></Col>
      </Row>

      <Row gutter={16}>
        <Col span={14}>
          <Card title="资产状态分布（来源：claw.assets.status）">
            <Table rowKey="k" size="small" dataSource={stageData} columns={stageCols} pagination={false}
              locale={{ emptyText: '暂无资产' }} />
            <Divider style={{ margin: '12px 0' }} />
            <Text type="secondary" style={{ fontSize: 12 }}>三专户余额</Text>
            <Table rowKey="escrowType" size="small" style={{ marginTop: 8 }}
              dataSource={data?.escrowAccounts || []}
              columns={[{ title: '专户', dataIndex: 'escrowType' }, { title: '余额', dataIndex: 'balance' }]}
              pagination={false} locale={{ emptyText: '暂无' }} />
          </Card>
        </Col>
        <Col span={10}>
          <Card title="数据来源口径（说清楚统计从哪来）">
            <Table rowKey="key" size="small" dataSource={data?.sources || []} columns={sourceCols} pagination={false} />
          </Card>
        </Col>
      </Row>
    </Spin>
  );
}
