import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { getToken } from './auth';
import Login from './pages/Login';
import AdminLayout from './layout/AdminLayout';
import Dashboard from './pages/Dashboard';
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

export default function App() {
  const authed = getToken();
  return (
    <HashRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={authed ? <AdminLayout /> : <Navigate to="/login" replace />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="dashboard" element={<Dashboard />} />
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
        </Route>
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </HashRouter>
  );
}
