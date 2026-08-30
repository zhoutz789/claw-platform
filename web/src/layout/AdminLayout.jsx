import { useMemo, useState, useEffect } from 'react';
import { Layout, Menu, Input, Badge, Avatar, Space, Typography, Button, Dropdown } from 'antd';
import {
  DashboardOutlined, LogoutOutlined, BellOutlined, SearchOutlined, ArrowRightOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { clearToken } from '../auth';
import { navLabel, groupLabel } from '../nav';
import LangSwitch from '../i18n/LangSwitch';
import {
  ancestorKeysOfActive, resolveIcon,
  getEffectiveNav, getEffectiveFlatNav, useEffectiveNav,
} from '../menuStore';
import { usePermVersion, isAllGranted } from '../permStore';
import { ForbiddenPage } from '../components/Perm';
import ErrorBoundary from '../ErrorBoundary';

// 判断某 key 是否出现在导航树中（含任意层级），用于路由级权限守卫。
const keyInNav = (nodes, key) => {
  if (!Array.isArray(nodes)) return false;
  for (const n of nodes) {
    if (n && n.key === key) return true;
    if (n && n.children && keyInNav(n.children, key)) return true;
  }
  return false;
};

const { Header, Sider, Content } = Layout;

// 当前路由对应的菜单 key（与 NAV 的 key 一致）
const keyOf = (pathname) => {
  const seg = pathname.replace('/', '');
  if (!seg || seg === 'dashboard' || seg === 'workbench') return 'workbench';
  return seg;
};

// 页面标题：命中叶子用其显示名，否则退化到一级节点，都没有则回落「工作台」
const labelOf = (key, nav, flat, t) => {
  const hit = flat.find((i) => i.key === key);
  if (hit) return navLabel(hit);
  const top = nav.find((i) => i.key === key);
  if (top) return navLabel(top);
  return t('layout.workbench');
};

export default function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { t, i18n } = useTranslation();
  // 订阅权限状态变更（降级开关 / 后端菜单下发都会触发本组件重渲染）
  usePermVersion();
  const selected = keyOf(location.pathname);
  const nav = useEffectiveNav();
  const flat = useMemo(() => getEffectiveFlatNav(), [nav]);
  const title = labelOf(selected, nav, flat, t);
  // 路由级权限守卫：后端权威菜单未包含当前页（且无降级）即视为无权限 → 403。
  // workbench 作为首页永远放行；降级模式（allGranted）一律放行。
  const effectiveNav = getEffectiveNav();
  const routeAllowed = isAllGranted() || selected === 'workbench' || keyInNav(effectiveNav, selected);

  // 全局搜索：过滤全部菜单项，点击即跳转。
  // 先解析出「当前语言下的显示名」再匹配，保证切语言后按新语言也能搜到。
  const [search, setSearch] = useState('');
  const hits = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return [];
    return flat
      .map((m) => ({
        ...m,
        display: navLabel(m),
        displayGroup: groupLabel(m.groupKey, m.group),
      }))
      .filter((m) => (
        m.display.toLowerCase().includes(q)
        || m.displayGroup.toLowerCase().includes(q)
        || m.key.includes(q)
      ))
      .slice(0, 8);
    // i18n.language 进依赖：切换语言后搜索索引的显示名同步刷新
  }, [search, flat, i18n.language]);

  const goSearch = (m) => {
    navigate(m.path);
    setSearch('');
  };

  // 递归构建菜单项：分组（含 children）渲染为 SubMenu，叶子渲染为可点击项
  const buildItems = (nodes, depth = 0) => {
    if (depth > 12) return []; // 护栏：异常嵌套直接截断，绝不让整页崩溃
    return nodes.map((n) => {
      const Icon = resolveIcon(n);
      return n.children
        ? { key: n.key, icon: <Icon />, label: navLabel(n), children: buildItems(n.children, depth + 1) }
        // 一级叶子（如工作台）也显示图标，与分组视觉对齐
        : { key: n.key, icon: depth === 0 ? <Icon /> : undefined, label: navLabel(n) };
    });
  };
  const menuChildren = buildItems(getEffectiveNav());

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
            {t('app.brand')}
            <small style={{ display: 'block', fontSize: 10, color: 'var(--muted)', fontWeight: 500 }}>
              {t('app.brandSub')}
            </small>
          </div>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selected]}
          openKeys={openKeys}
          onOpenChange={setOpenKeys}
          items={menuChildren}
          // 跳转以节点自身 path 为准（用户在菜单管理里改过路径也能正确跳转）
          onClick={({ key }) => {
            const hit = flat.find((f) => f.key === key);
            if (hit) navigate(hit.path);
          }}
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
            ⚠️ {t('layout.mockBanner')}
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
                      <span>{m.display}</span>
                      <span className="wb-search-group">{m.displayGroup}<ArrowRightOutlined style={{ marginLeft: 6 }} /></span>
                    </div>
                  ))}
                </div>
              )}
            >
              <Input
                allowClear
                prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
                placeholder={t('layout.searchPlaceholder')}
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
              <div style={{ fontSize: 13, fontWeight: 600 }}>{t('layout.adminName')}</div>
              <div style={{ fontSize: 10, color: 'var(--muted)' }}>{t('layout.superAdmin')}</div>
            </div>
            {/* 语言切换器：常驻顶栏，与搜索框 / 头像并列，随时可切 */}
            <LangSwitch variant="dropdown" />
            <Button
              icon={<LogoutOutlined />}
              onClick={() => { clearToken(); window.location.hash = '#/login'; }}
            >
              {t('layout.logout')}
            </Button>
          </Space>
        </Header>

        <Content style={{ margin: 16, padding: 16, background: 'transparent' }}>
          <ErrorBoundary resetKey={location.pathname}>
            {routeAllowed ? <Outlet /> : <ForbiddenPage menuKey={selected} />}
          </ErrorBoundary>
        </Content>
      </Layout>
    </Layout>
  );
}
