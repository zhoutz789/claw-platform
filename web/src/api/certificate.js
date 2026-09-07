// ③ 合格证（可定制模板 + 可编辑识别信息）前端接口封装。
//
// 对接 AdminCertificateController：/api/v1/admin/certificates
//   GET    /device/{deviceId}              查设备合格证（mfg:certificate:view）
//   PUT    /device/{deviceId}              更新识别信息（mfg:certificate:edit）
//   GET    /template                       模板字段列表（mfg:certificate:template）
//   POST   /template                       新增模板字段
//   PUT    /template/{id}                  编辑模板字段
//   DELETE /template/{id}                  删除模板字段
// 风格与 category.js / supplyChain.js 保持一致：基于 src/api.js 的 axios 实例。
import api from '../api.js';

/** 查设备合格证（含 specJson 快照 + dataJson 识别信息）。 */
export const getCert = (deviceId) => api.get('/v1/admin/certificates/device/' + deviceId);

/** 更新设备合格证识别信息：{ dataJson } 为 JSON 字符串。 */
export const updateCertData = (deviceId, dataJson) =>
  api.put('/v1/admin/certificates/device/' + deviceId, { dataJson });

/** 合格证模板字段列表。 */
export const listCertTemplate = () => api.get('/v1/admin/certificates/template');

/** 新增模板字段：{ fieldKey, label, type, unit?, optionsJson?, required?, sortNo? }。 */
export const createCertTemplateField = (body) => api.post('/v1/admin/certificates/template', body);

/** 编辑模板字段。 */
export const updateCertTemplateField = (id, body) =>
  api.put('/v1/admin/certificates/template/' + id, body);

/** 删除模板字段。 */
export const deleteCertTemplateField = (id) => api.delete('/v1/admin/certificates/template/' + id);

export default {
  getCert,
  updateCertData,
  listCertTemplate,
  createCertTemplateField,
  updateCertTemplateField,
  deleteCertTemplateField,
};
