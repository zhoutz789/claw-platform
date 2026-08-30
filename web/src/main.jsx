import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, App as AntApp } from 'antd';
import { useTranslation } from 'react-i18next';
import 'antd/dist/reset.css';
import './theme.css';
import App from './App';
// i18n 初始化必须在首次渲染前完成（副作用式导入，勿删）。
import { ANTD_LOCALE, getLang } from './i18n';

/**
 * 根组件：把 antd 的 locale 与当前语言绑定。
 * useTranslation() 会订阅 i18next 的 languageChanged 事件，
 * 因此切换语言时 ConfigProvider.locale 自动在 zh_CN / en_US / km_KH 之间切换，
 * antd 内置文案（分页、空状态、日期选择器、表单校验提示等）随即三语。
 *
 * @returns {JSX.Element} 应用根节点
 */
function Root() {
  useTranslation();
  const lang = getLang();
  return (
    <ConfigProvider
      locale={ANTD_LOCALE[lang]}
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
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>
);
