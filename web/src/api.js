import axios from 'axios';

// 统一请求：baseURL 指向同源 /api（由 Vite 代理到后端 :8080）。
// 响应拦截：ApiResult 统一解包到 body.data，code!=0 抛错；401 清 token 跳登录。
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
    if (err.response && err.response.status === 401) {
      localStorage.removeItem('claw_token');
      if (window.location.hash !== '#/login') window.location.hash = '#/login';
    }
    const msg =
      (err.response && err.response.data && err.response.data.message) ||
      err.message ||
      '网络错误';
    return Promise.reject(new Error(msg));
  }
);

export default api;
