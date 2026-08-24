import { Layout, Menu, Input, Badge, Avatar, Space, Typography, Button } from 'antd';
import {
  DashboardOutlined, ShoppingCartOutlined, ShopOutlined, DatabaseOutlined, SwapOutlined,
  ReconciliationOutlined, WalletOutlined, PieChartOutlined, SettingOutlined, CreditCardOutlined,
  GlobalOutlined, SafetyOutlined, AlertOutlined, LinkOutlined, AuditOutlined, TeamOutlined,
  KeyOutlined, MessageOutlined, LogoutOutlined, BellOutlined, BankOutlined,
  PartitionOutlined, SafetyCertificateOutlined,
  ToolOutlined, ShareAltOutlined, SolutionOutlined, FileTextOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { clearToken } from '../auth';

const { Header, Sider, Content } = Layout;

const groups = [
  {
    label: '运营',
    items: [
      { key: 'dashboard', icon: <DashboardOutlined />, label: '数据大屏' },
      { key: 'orders', icon: <ShoppingCartOutlined />, label: '订单管理', badge: 5 },
      { key: 'stations', icon: <ShopOutlined />, label: '站点管理' },
      { key: 'assets', icon: <DatabaseOutlined />, label: '资产管理' },
      { key: 'asset-trace', icon: <PartitionOutlined />, label: '资产溯源' },
      { key: 'manufacturer', icon: <BankOutlined />, label: '厂家与商品' },
      { key: 'swap-orders', icon: <SwapOutlined />, label: '换电记录' },
      { key: 'recovery', icon: <ToolOutlined />, label: '残值回收' },
      { key: 'shared-pool', icon: <ShareAltOutlined />, label: '共享池' },
    ],
  },
  {
    label: '财务',
    items: [
      { key: 'reconciliations', icon: <ReconciliationOutlined />, label: '对账中心' },
      { key: 'ledger', icon: <WalletOutlined />, label: '资金账户' },
      { key: 'profit', icon: <PieChartOutlined />, label: '分账报告' },
      { key: 'fee', icon: <SettingOutlined />, label: '费率配置' },
      { key: 'payments', icon: <CreditCardOutlined />, label: '支付流水' },
      { key: 'settlements', icon: <GlobalOutlined />, label: '跨境结算' },
      { key: 'deposits', icon: <SafetyOutlined />, label: '押金管理' },
      { key: 'operator', icon: <SolutionOutlined />, label: '运营方财务' },
    ],
  },
  {
    label: '风控',
    items: [
      { key: 'risk', icon: <AlertOutlined />, label: '风控监控', badge: 2 },
      { key: 'alerts', icon: <BellOutlined />, label: '异常告警' },
      { key: 'custody', icon: <LinkOutlined />, label: '产权链追溯' },
      { key: 'insurance', icon: <FileTextOutlined />, label: '保险管理' },
      { key: 'arbitration', icon: <AuditOutlined />, label: '争议仲裁' },
      { key: 'complaints', icon: <MessageOutlined />, label: '投诉处理' },
    ],
  },
  {
    label: '系统',
    items: [
      { key: 'users', icon: <TeamOutlined />, label: '用户管理' },
      { key: 'roles', icon: <KeyOutlined />, label: '角色权限' },
      { key: 'permission', icon: <SafetyCertificateOutlined />, label: '权限矩阵' },
      { key: 'countries', icon: <GlobalOutlined />, label: '国家 / 法域' },
      { key: 'settings', icon: <SettingOutlined />, label: '系统配置' },
    ],
  },
];

const allItems = groups.flatMap((g) => g.items);
const labelOf = (key) => allItems.find((i) => i.key === key)?.label || '数据大屏';

export default function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const selected = location.pathname.replace('/', '') || 'dashboard';
  const title = labelOf(selected);

  const menuChildren = groups.map((g) => ({
    type: 'group',
    label: g.label,
    children: g.items.map((it) => ({
      key: it.key,
      icon: it.icon,
      label: it.badge ? (
        <span>
          {it.label}
          <Badge count={it.badge} size="small" style={{ marginLeft: 8, backgroundColor: '#e2573f' }} />
        </span>
      ) : (
        it.label
      ),
    })),
  }));

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={240} style={{ background: 'var(--surface)', borderRight: '1px solid var(--line)' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '20px 20px 16px',
            borderBottom: '1px solid var(--line)',
          }}
        >
          <div
            style={{
              width: 34,
              height: 34,
              borderRadius: 10,
              background: 'linear-gradient(135deg,var(--brand),var(--energy))',
              display: 'grid',
              placeItems: 'center',
              color: '#fff',
              fontSize: 18,
            }}
          >
            ⚡
          </div>
          <div style={{ fontWeight: 900, fontSize: 16 }}>
            Claw
            <small style={{ display: 'block', fontSize: 10, color: 'var(--muted)', fontWeight: 500 }}>
              新能源资产管理平台
            </small>
          </div>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selected]}
          items={menuChildren}
          onClick={({ key }) => navigate(`/${key}`)}
          style={{ background: 'transparent', borderInlineEnd: 'none', paddingTop: 8 }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: 'var(--surface)',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid var(--line)',
            height: 64,
          }}
        >
          <Typography.Title level={4} style={{ margin: 0 }}>
            {title}
          </Typography.Title>
          <Space size="middle">
            <Input.Search placeholder="搜索订单/用户/站点..." allowClear style={{ width: 240 }} />
            <Badge count={3} size="small">
              <BellOutlined style={{ fontSize: 18, color: 'var(--ink-2)' }} />
            </Badge>
            <Avatar style={{ background: 'linear-gradient(135deg,var(--brand),var(--energy))' }}>管</Avatar>
            <div style={{ lineHeight: 1.1 }}>
              <div style={{ fontSize: 13, fontWeight: 600 }}>管理员</div>
              <div style={{ fontSize: 10, color: 'var(--muted)' }}>SUPER_ADMIN</div>
            </div>
            <Button
              icon={<LogoutOutlined />}
              onClick={() => {
                clearToken();
                window.location.hash = '#/login';
              }}
            >
              退出
            </Button>
          </Space>
        </Header>
        <Content style={{ margin: 16, padding: 16, background: 'transparent' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
