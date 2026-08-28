import { useMemo, useState, useEffect } from 'react';
import { Layout, Menu, Input, Badge, Avatar, Space, Typography, Button, Dropdown, Empty } from 'antd';
import {
  DashboardOutlined, LogoutOutlined, BellOutlined, SearchOutlined, ArrowRightOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { clearToken } from '../auth';
import {
  AppstoreOutlined,
} from '@ant-design/icons';
import {
  useMenuNav, getFlatNav, ancestorKeysOfActive, getVisibleNav, ICON_BY_KEY,
} from '../menuStore';
import ErrorBoundary from '../ErrorBoundary';

const { Header, Sider, Content } = Layout;

// 当前路由对应的菜单 key（与 NAV 的 key 一致）
const keyOf = (pathname) => {
  const seg = pathname.replace('/', '');
  if (!seg || seg === 'dashboard' || seg === 'workbench') return 'workbench';
  return seg;
};

const labelOf = (key, nav, flat) => {
  const hit = flat.find((i) => i.key === key);
  if (hit) return hit.label;
  const top = nav.find((i) => i.key === key);
  return top?.label || '工作台';
};

export default function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const selected = keyOf(location.pathname);
  const nav = useMenuNav();
  const flat = useMemo(() => getFlatNav(), [nav]);
  const title = labelOf(selected, nav, flat);

  // 全局搜索：过滤全部菜单项，点击即跳转
  const [search, setSearch] = useState('');
  const hits = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return [];
    return flat.filter(
      (m) => m.label.toLowerCase().includes(q) || m.group.toLowerCase().includes(q) || m.key.includes(q)
    ).slice(0, 8);
  }, [search, flat]);

  const goSearch = (m) => {
    navigate(m.path);
    setSearch('');
  };

  // 递归构建菜单项：分组（含 children）渲染为 SubMenu，叶子渲染为可点击项
  const buildItems = (nodes, depth = 0) => {
    if (depth > 12) return []; // 护栏：异常嵌套直接截断，绝不让整页崩溃
    return nodes.map((n) => {
      const Icon = n.icon || ICON_BY_KEY[n.key] || AppstoreOutlined;
      return n.children
        ? { key: n.key, icon: <Icon />, label: n.label, children: buildItems(n.children, depth + 1) }
        : { key: n.key, label: n.label };
    });
  };
  const menuChildren = buildItems(getVisibleNav());

  // 自动展开包含当前选中项的所有祖先分组（支持二级嵌套）
  const [openKeys, setOpenKeys] = useState(() => ancestorKeysOfActive(selected));
  useEffect(() => {
    setOpenKeys((prev) => Array.from(new Set([...prev, ...ancestorKeysOfActive(selected)])));
  }, [selected]);

  // 演示数据提示：后端不可达/鉴权失败时回落到内置演示数据，顶部给出明确标识。
  const [mockMode, setMockMode] = useState(!!window.__CLAW_MOCK__);
  useEffect(() => {
    const onMock = () => setMockMode(true);
    window.addEventListener('claw:mock', onMock);
    return () => window.removeEventListener('claw:mock', onMock);
  }, []);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={240} style={{ background: 'var(--surface)', borderRight: '1px solid var(--line)' }}>
        <div
          style={{
            display: 'flex', alignItems: 'center', gap: 10, padding: '20px 20px 16px',
            borderBottom: '1px solid var(--line)',
          }}
        >
          <div style={{
            width: 34, height: 34, borderRadius: 10,
            background: 'linear-gradient(135deg,var(--brand),var(--energy))',
            display: 'grid', placeItems: 'center', color: '#fff', fontSize: 18,
          }}>⚡</div>
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
          openKeys={openKeys}
          onOpenChange={setOpenKeys}
          items={menuChildren}
          onClick={({ key }) => { if (flat.some((f) => f.key === key)) navigate(`/${key}`); }}
          style={{ background: 'transparent', borderInlineEnd: 'none', paddingTop: 8 }}
        />
      </Sider>

      <Layout>
        {mockMode && (
          <div
            style={{
              background: 'linear-gradient(90deg,#fef3c7,#fde68a)', color: '#92400e',
              fontSize: 12, fontWeight: 600, padding: '6px 24px', textAlign: 'center',
              borderBottom: '1px solid #fcd34d',
            }}
          >
            ⚠️ 演示数据模式：后端当前不可达 / 鉴权未通过，页面展示的是内置演示数据（闭环可见，但非真实库数据）。接入真实后端并登录后将自动切换为实时数据。
          </div>
        )}
        <Header
          style={{
            background: 'var(--surface)', padding: '0 24px',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            borderBottom: '1px solid var(--line)', height: 64,
          }}
        >
          <Space size="middle">
            <Typography.Title level={4} style={{ margin: 0 }}>{title}</Typography.Title>
          </Space>

          <Space size="middle">
            <Dropdown
              trigger={[]}
              open={search.trim().length > 0 && hits.length > 0}
              dropdownRender={() => (
                <div className="wb-search-panel">
                  {hits.map((m) => (
                    <div key={m.key} className="wb-search-item" onClick={() => goSearch(m)}>
                      <span>{m.label}</span>
                      <span className="wb-search-group">{m.group}<ArrowRightOutlined style={{ marginLeft: 6 }} /></span>
                    </div>
                  ))}
                </div>
              )}
            >
              <Input
                allowClear
                prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
                placeholder="搜索模块（如：对账、保险、站点）"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onPressEnter={() => hits[0] && goSearch(hits[0])}
                style={{ width: 280 }}
              />
            </Dropdown>

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
              onClick={() => { clearToken(); window.location.hash = '#/login'; }}
            >
              退出
            </Button>
          </Space>
        </Header>

        <Content style={{ margin: 16, padding: 16, background: 'transparent' }}>
          <ErrorBoundary resetKey={location.pathname}>
            <Outlet />
          </ErrorBoundary>
        </Content>
      </Layout>
    </Layout>
  );
}
