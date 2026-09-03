// 兼容层：保持历史页面 `import api from '../api'` 可用（src/api 现为目录）。
// 真正 axios 实例在 src/api.js，订单/登记接口封装在 src/api/order.js。
import api from '../api.js';

export default api;
export * from './order.js';
export * from './station.js';
