import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import 'antd/dist/reset.css';
import './theme.css';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#176a48',
          borderRadius: 12,
          colorBgContainer: '#fffdf7',
          colorBorder: '#e4dfd1',
          colorText: '#15281f',
          colorTextSecondary: '#46594f',
          fontFamily: '"Space Grotesk","Noto Sans SC",system-ui,-apple-system,sans-serif',
        },
      }}
    >
      <AntApp>
        <App />
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>
);
