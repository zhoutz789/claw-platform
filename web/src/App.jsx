import { useEffect } from 'react';
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { getToken } from './auth';
import { loadPermissions } from './permStore';
import { ForbiddenPage, RequirePermRoute } from './components/Perm';
import Login from './pages/Login';
import AdminLayout from './layout/AdminLayout';
import Dashboard from './pages/Dashboard';
import Workbench from './pages/Workbench';
import Orders from './pages/Orders';
import Stations from './pages/Stations';
import Assets from './pages/Assets';
import SwapOrders from './pages/SwapOrders';
import Ledger from './pages/Ledger';
import Users from './pages/Users';
import Complaints from './pages/Complaints';
import Deposits from './pages/Deposits';
import Settlements from './pages/Settlements';
import Payments from './pages/Payments';
import Reconciliations from './pages/Reconciliations';
import Countries from './pages/Countries';
import RiskMonitor from './pages/RiskMonitor';
import Alerts from './pages/Alerts';
import CustodyChain from './pages/CustodyChain';
import Arbitration from './pages/Arbitration';
import ProfitReport from './pages/ProfitReport';
import FeeConfig from './pages/FeeConfig';
import Roles from './pages/Roles';
import Settings from './pages/Settings';
import Manufacturer from './pages/Manufacturer';
import AssetTrace from './pages/AssetTrace';
import Permission from './pages/Permission';
import Recovery from './pages/Recovery';
import Insurance from './pages/Insurance';
import Operator from './pages/Operator';
import SharedPool from './pages/SharedPool';
import ProductIot from './pages/ProductIot';
import ProductCenter from './pages/ProductCenter';
import ProjectManagement from './pages/ProjectManagement';
import GoodsList from './pages/GoodsList';
import ProductWizard from './pages/ProductWizard';
import Certificate from './pages/Certificate';
import BindOwnership from './pages/BindOwnership';
import DeviceDataAccess from './pages/DeviceDataAccess';
import BrandOnboarding from './pages/BrandOnboarding';
import MenuPermission from './pages/MenuPermission';
import MenuManager from './pages/MenuManager';
import AssetParams from './pages/AssetParams';
import AppPortal from './pages/AppPortal';
import Departments from './pages/Departments';
// —— 子菜单化：新增包装页 / 独立子菜单页 ——
import ProductTemplate from './pages/ProductTemplate';
import Authorization from './pages/Authorization';
import ProductPublish from './pages/ProductPublish';
import OrderManage from './pages/OrderManage';
import TaskDrone from './pages/TaskDrone';
import TaskLogi from './pages/TaskLogi';
import TaskAd from './pages/TaskAd';
import TaskVideo from './pages/TaskVideo';
import TaskRent from './pages/TaskRent';
import TaskNear from './pages/TaskNear';
// —— 增量 B · 库存 / 流转 / 渠道域 + 增量 A · 权限骨架 ——
import Production from './pages/Production';
import MfgInventory from './pages/MfgInventory';
import StationConsignment from './pages/StationConsignment';
import InventoryOverview from './pages/InventoryOverview';
import Transfers from './pages/Transfers';
import FulfillmentOrders from './pages/FulfillmentOrders';
import PickupScan from './pages/PickupScan';
import CommissionRules from './pages/CommissionRules';
// —— 模块四 · 服务站功能（库存 / 项目 / 结算三层解耦）——
import StationInventory from './pages/StationInventory';
import StationProjects from './pages/StationProjects';
import StationSettlement from './pages/StationSettlement';
import RoleTemplates from './pages/RoleTemplates';
import RoleGroups from './pages/RoleGroups';
import PrincipalBindings from './pages/PrincipalBindings';
import Merchants from './pages/Merchants';
// —— 增量 C · 入驻管理 ——
import OnboardingApply from './pages/OnboardingApply';
import OnboardingReview from './pages/OnboardingReview';
import OnboardingContent from './pages/OnboardingContent';
import OnboardingDepositTiers from './pages/OnboardingDepositTiers';
import OnboardingDepositConfirm from './pages/OnboardingDepositConfirm';
import OrgManage from './pages/OrgManage';
import SubAccounts from './pages/SubAccounts';
// —— 增量 D · 无人机 / 低空经济域（4 个新页面，menu:* 权限码见 V66 迁移） ——
import AirspaceZones from './pages/AirspaceZones';
import FlightPlans from './pages/FlightPlans';
import PilotLicenses from './pages/PilotLicenses';
import DroneOps from './pages/DroneOps';
import ErrorBoundary from './ErrorBoundary';

// 登录态下启动权限内核：拉取「我的权限」并下发后端权威菜单。
// 失败自动降级为「全部放行」，保证本地 54 页不被卡死。发后即忘（结果由 permStore 内部处理）。
function PermissionBootstrap() {
  useEffect(() => {
    if (getToken()) {
      loadPermissions().catch(() => {});
    }
  }, []);
  return null;
}

export default function App() {
  const authed = getToken();
  return (
    <HashRouter>
      <ErrorBoundary>
      <PermissionBootstrap />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/forbidden" element={<ForbiddenPage />} />
        <Route path="/" element={authed ? <AdminLayout /> : <Navigate to="/login" replace />}>
          <Route index element={<Navigate to="/workbench" replace />} />
          <Route path="workbench" element={<Workbench />} />
          <Route path="dashboard" element={<Workbench />} />
          <Route path="orders" element={<Orders />} />
          <Route path="stations" element={<Stations />} />
          <Route path="assets" element={<Assets />} />
          <Route path="asset-trace" element={<AssetTrace />} />
          <Route path="manufacturer" element={<Manufacturer />} />
          <Route path="recovery" element={<Recovery />} />
          <Route path="insurance" element={<Insurance />} />
          <Route path="operator" element={<Operator />} />
          <Route path="shared-pool" element={<SharedPool />} />
          <Route path="swap-orders" element={<SwapOrders />} />
          <Route path="ledger" element={<Ledger />} />
          <Route path="users" element={<Users />} />
          <Route path="complaints" element={<Complaints />} />
          <Route path="deposits" element={<Deposits />} />
          <Route path="settlements" element={<Settlements />} />
          <Route path="payments" element={<Payments />} />
          <Route path="reconciliations" element={<Reconciliations />} />
          <Route path="countries" element={<Countries />} />
          <Route path="risk" element={<RiskMonitor />} />
          <Route path="alerts" element={<Alerts />} />
          <Route path="custody" element={<CustodyChain />} />
          <Route path="arbitration" element={<Arbitration />} />
          <Route path="profit" element={<ProfitReport />} />
          <Route path="fee" element={<FeeConfig />} />
          <Route path="roles" element={<Roles />} />
          <Route path="permission" element={<Permission />} />
          <Route path="settings" element={<Settings />} />
          <Route path="product-iot" element={<ProductIot />} />
          <Route path="product-center" element={<ProductCenter />} />
          <Route path="project-management" element={<ProjectManagement />} />
          <Route path="certificate" element={<Certificate />} />
          <Route path="bind-ownership" element={<BindOwnership />} />
          <Route path="device-data-access" element={<DeviceDataAccess />} />
          {/* 任务发布中心：6 个独立子页（原 /task-publish 已拆分） */}
          <Route path="task-drone" element={<TaskDrone />} />
          <Route path="task-logi" element={<TaskLogi />} />
          <Route path="task-ad" element={<TaskAd />} />
          <Route path="task-video" element={<TaskVideo />} />
          <Route path="task-rent" element={<TaskRent />} />
          <Route path="task-near" element={<TaskNear />} />
          {/* 产品管理子菜单：独立子页 */}
          <Route path="product-template" element={<ProductTemplate />} />
          <Route path="authorization" element={<Authorization />} />
          {/* 旧 device-detail / device-twin 已被 ProductCenter 取代，路由移除 */}
          {/* 商品管理子菜单：独立子页 */}
          <Route path="goods-list" element={<GoodsList />} />
          <Route path="product-wizard" element={<ProductWizard />} />
          <Route path="product-publish" element={<ProductPublish />} />
          <Route path="order-manage" element={<OrderManage />} />
          <Route path="brand-onboarding" element={<BrandOnboarding />} />
          <Route path="app-portal" element={<AppPortal />} />
          <Route path="menu-permission" element={<MenuPermission />} />
          <Route path="menu-manager" element={<MenuManager />} />
          <Route path="asset-params" element={<AssetParams />} />
          <Route path="departments" element={<Departments />} />
          {/* 增量 B · 库存 / 流转 / 渠道域：生产 → 库存双视图 → 调拨 → 履约 → 取货扫码 → 提成规则 */}
          <Route path="production" element={<Production />} />
          <Route path="inventory-overview" element={<RequirePermRoute menuKey="inventory-overview"><InventoryOverview /></RequirePermRoute>} />
          <Route path="mfg-inventory" element={<MfgInventory />} />
          <Route path="station-consignment" element={<StationConsignment />} />
          <Route path="transfers" element={<Transfers />} />
          <Route path="fulfillment-orders" element={<FulfillmentOrders />} />
          <Route path="pickup-scan" element={<PickupScan />} />
          <Route path="commission-rules" element={<CommissionRules />} />
          {/* 模块四 · 服务站功能：库存 / 项目 / 结算三层解耦 */}
          <Route path="station-inventory" element={<RequirePermRoute menuKey="station-inventory"><StationInventory /></RequirePermRoute>} />
          <Route path="station-projects" element={<RequirePermRoute menuKey="station-projects"><StationProjects /></RequirePermRoute>} />
          <Route path="station-settlements" element={<RequirePermRoute menuKey="station-settlements"><StationSettlement /></RequirePermRoute>} />
          {/* 增量 A · 权限骨架：角色模板 / 角色组 / 主体绑定 */}
          <Route path="role-templates" element={<RoleTemplates />} />
          <Route path="role-groups" element={<RoleGroups />} />
          <Route path="principal-bindings" element={<PrincipalBindings />} />
          {/* Phase 2 骨架：商家入驻 */}
          <Route path="merchants" element={<Merchants />} />

          {/* 增量 C · 入驻管理（菜单可见性由 menu:{navKey} 权限码控制，见 V62） */}
          <Route path="onboarding-apply" element={<RequirePermRoute menuKey="onboarding-apply"><OnboardingApply /></RequirePermRoute>} />
          <Route path="onboarding-review" element={<RequirePermRoute menuKey="onboarding-review"><OnboardingReview /></RequirePermRoute>} />
          <Route path="onboarding-content" element={<RequirePermRoute menuKey="onboarding-content"><OnboardingContent /></RequirePermRoute>} />
          <Route path="onboarding-deposit-tiers" element={<RequirePermRoute menuKey="onboarding-deposit-tiers"><OnboardingDepositTiers /></RequirePermRoute>} />
          <Route path="onboarding-deposit-confirm" element={<RequirePermRoute menuKey="onboarding-deposit-confirm"><OnboardingDepositConfirm /></RequirePermRoute>} />
          <Route path="org-manage" element={<RequirePermRoute menuKey="org-manage"><OrgManage /></RequirePermRoute>} />
          <Route path="sub-accounts" element={<RequirePermRoute menuKey="sub-accounts"><SubAccounts /></RequirePermRoute>} />
          {/* 增量 D · 无人机 / 低空经济域（菜单可见性由 menu:{navKey} 权限码控制，见 V66） */}
          <Route path="airspace-zones" element={<RequirePermRoute menuKey="airspace-zones"><AirspaceZones /></RequirePermRoute>} />
          <Route path="flight-plans" element={<RequirePermRoute menuKey="flight-plans"><FlightPlans /></RequirePermRoute>} />
          <Route path="pilot-licenses" element={<RequirePermRoute menuKey="pilot-licenses"><PilotLicenses /></RequirePermRoute>} />
          <Route path="drone-ops" element={<RequirePermRoute menuKey="drone-ops"><DroneOps /></RequirePermRoute>} />
        </Route>
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
      </ErrorBoundary>
    </HashRouter>
  );
}
