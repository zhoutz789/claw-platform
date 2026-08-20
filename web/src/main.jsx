import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

// S0 骨架：S5 交付 大屏/订单/对账/配置 四大模块
// 路由规划：/dashboard /orders /reconciliation /settings
function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <div style={{ padding: 48, fontFamily: 'sans-serif' }}>
        <h1>Claw 管理后台</h1>
        <p>S0 脚手架就绪。S5 交付：数据大屏 / 订单 / 财务对账 / 系统配置。</p>
      </div>
    </ConfigProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
