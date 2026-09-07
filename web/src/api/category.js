// ② 类别管理（通用多级商品分类树）前端接口封装。
//
// 对接 AdminCategoryController：/api/v1/admin/categories
//   GET    /categories            扁平列表
//   GET    /categories/tree       嵌套树
//   POST   /categories            新建（category:create）
//   PUT    /categories/{id}       更名 / 改父（category:update）
//   DELETE /categories/{id}        软删除（category:delete，含子节点时拒绝）
// 风格与 capacity.js / supplyChain.js 保持一致：基于 src/api.js 的 axios 实例。
import api from '../api.js';

/** 扁平列表（未删除，按 tenantId）。 */
export const listCategories = () => api.get('/v1/admin/categories');

/** 嵌套树（前端树形组件直接用）。 */
export const categoryTree = () => api.get('/v1/admin/categories/tree');

/** 新建分类：{ name, parentId?, sortNo? }。 */
export const createCategory = (body) => api.post('/v1/admin/categories', body);

/** 修改分类：{ name?, parentId?, sortNo? }。 */
export const updateCategory = (id, body) => api.put('/v1/admin/categories/' + id, body);

/** 软删除分类（含子节点时后端拒绝）。 */
export const deleteCategory = (id) => api.delete('/v1/admin/categories/' + id);

export default {
  listCategories,
  categoryTree,
  createCategory,
  updateCategory,
  deleteCategory,
};
