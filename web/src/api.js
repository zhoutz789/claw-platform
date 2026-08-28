import axios from 'axios';
import { resolveMock } from './mock/api';

// 演示模式：未登录时直接赋予 dev-mock-token，绕过登录网关即可进入后台体验（与 Login 演示分支一致）。
if (!localStorage.getItem('claw_token')) {
  localStorage.setItem('claw_token', 'dev-mock-token');
}

// 统一请求：baseURL 指向同源 /api（由 Vite 代理到后端 :8080）。
// 响应拦截：ApiResult 统一解包到 body.data，code!=0 抛错；401 清 token 跳登录。
// 数据策略：后端可达时一律使用真实数据；仅当后端彻底不可达（网络故障）才回落
// 内置演示数据作为安全网，避免页面白屏。不再用演示数据冒充真实结果。
const api = axios.create({ baseURL: '/api', timeout: 15000 });

api.interceptors.request.use((cfg) => {
  const t = localStorage.getItem('claw_token');
  if (t) cfg.headers.Authorization = `Bearer ${t}`;
  return cfg;
});

api.interceptors.response.use(
  (resp) => {
    const body = resp.data;
    if (body && typeof body.code === 'number' && body.code !== 0) {
      return Promise.reject(new Error(body.message || `错误码 ${body.code}`));
    }
    return body && 'data' in body ? body.data : body;
  },
  (err) => {
    const cfg = err.config || {};
    const status = err.response && err.response.status;
    // 仅“后端完全不可达（网络层故障）”才算故障；后端返回的任何 HTTP 状态码
    // 都视为真实结果，不再用演示数据冒充，从而暴露尚未实现的接口（数据钻取清单）。
    const isNetworkFailure = !err.response || err.code === 'ERR_NETWORK' || err.code === 'ECONNABORTED';

    if (isNetworkFailure) {
      // 安全网：后端彻底连不上才回落演示数据，保证页面不白屏。
      const mock = resolveMock(cfg);
      if (mock !== undefined) {
        window.__CLAW_MOCK__ = true;
        try { window.dispatchEvent(new CustomEvent('claw:mock', { detail: { url: cfg.url, status } })); } catch (e) { /* noop */ }
        return Promise.resolve(mock);
      }
    }

    // 401 登出逻辑（演示 token 永不登出）。
    if (status === 401) {
      if (localStorage.getItem('claw_token') !== 'dev-mock-token') {
        localStorage.removeItem('claw_token');
        if (window.location.hash !== '#/login') window.location.hash = '#/login';
      }
    }
    const msg =
      (err.response && err.response.data && err.response.data.message) ||
      err.message ||
      '网络错误';
    return Promise.reject(new Error(msg));
  }
);

export default api;
