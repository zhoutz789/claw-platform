// 全局导航配置：侧边栏枢纽 + 全局搜索共用，单一数据源避免漂移。
// v5：按周老板指令彻底移除旧四大中心（运营/财务/风控/系统）——这些旧菜单"功能已不满足需要、用了碍眼"；
// 仅保留 A/B/C/D 期新设计 + 任务发布 + 工作台。并按 2026-08-26 审计结论，从任务发布中剔除非新能源资产的
// 物流/广告/录像/附近车辆，仅留 无人机任务 + 资产出租。
// 旧四大中心的源码与路由全部保留（menuStore v4 强制回退到本 BASE_NAV），日后需要一行加回即可。
import {
  DashboardOutlined, AppstoreOutlined, ShoppingOutlined, ProjectOutlined, RocketOutlined,
} from '@ant-design/icons';

// 五大模块（工作台 + A/B/C/D 期新设计 + 任务发布）；children 为各模块下的页面或二级分组。
// 叶子节点 key 与路由段一致，保证选中态与跳转正确。
export const NAV = [
  { key: 'workbench', label: '工作台', icon: DashboardOutlined, path: '/workbench' },
  {
    key: 'prod', label: '产品管理', icon: AppstoreOutlined,
    children: [
      { key: 'product-center', label: '产品中心', path: '/product-center' },
      { key: 'certificate', label: '合格证', path: '/certificate' },
      { key: 'bind-ownership', label: '绑定 / 产权', path: '/bind-ownership' },
      { key: 'product-template', label: '产品模板', path: '/product-template' },
      { key: 'data-binding', label: '数据接入', path: '/device-data-access' },
      { key: 'authorization', label: '赋权管理', path: '/authorization' },
    ],
  },
  {
    key: 'project', label: '项目管理', icon: ProjectOutlined,
    children: [
      { key: 'project-management', label: '项目中心', path: '/project-management' },
    ],
  },
  {
    key: 'goods', label: '商品管理', icon: ShoppingOutlined,
    children: [
      { key: 'goods-list', label: '商品列表', path: '/goods-list' },
      { key: 'product-wizard', label: '发布商品向导', path: '/product-wizard' },
      { key: 'brand-onboarding', label: '品牌方入驻', path: '/brand-onboarding' },
      { key: 'manufacturer', label: '厂家商品', path: '/manufacturer' },
      { key: 'order-manage', label: '订单管理', path: '/order-manage' },
    ],
  },
  {
    key: 'task', label: '任务发布', icon: RocketOutlined,
    children: [
      { key: 'task-drone', label: '无人机任务', path: '/task-drone' },
      { key: 'task-rent', label: '资产出租', path: '/task-rent' },
    ],
  },
];

// 拍平为「带父级标签」的搜索索引：[{ key,label,path,group }]，递归展开所有叶子
export const FLAT_NAV = (() => {
  const out = [];
  const walk = (nodes, group) => {
    for (const n of nodes) {
      if (n.children) walk(n.children, group);
      else if (n.path) out.push({ key: n.key, label: n.label, path: n.path, group });
    }
  };
  for (const g of NAV) {
    if (g.children) walk(g.children, g.label);
    else if (g.path) out.push({ key: g.key, label: g.label, path: g.path, group: '首页' });
  }
  return out;
})();

// 收集某选中 key 的所有祖先分组 key（用于自动展开二级菜单）
export const ancestorKeysOf = (key) => {
  const result = [];
  const walk = (nodes, trail) => {
    for (const n of nodes) {
      if (n.key === key) { result.push(...trail); return true; }
      if (n.children && walk(n.children, [...trail, n.key])) return true;
    }
    return false;
  };
  walk(NAV, []);
  return result;
};
