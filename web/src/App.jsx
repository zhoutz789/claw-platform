import { useEffect } from 'react';
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { getToken } from './auth';
import { loadPermissions } from './permStore';
import { ForbiddenPage } from './components/Perm';
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
        </Route>
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
      </ErrorBoundary>
    </HashRouter>
  );
}
