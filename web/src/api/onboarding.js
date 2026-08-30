// 入驻管理（增量 C）接口封装。
//
// 全部对接 claw-platform/backend 的真实 Controller，无任何 mock 数据。
// Controller 与基路径对照（后端文件位于 web/v1/）：
//   OnboardingController        /api/v1/onboarding          申请方（说明/申请/材料/缴款）
//   AdminOnboardingController   /api/v1/admin/onboarding    平台侧（审核/合同/档位/到账确认）
//   AdminOrgController          /api/v1/admin/orgs          组织治理（禁用/启用/改档/额度）
//   AdminSubAccountController   /api/v1/org/sub-accounts    子账号与授权
import api from '../api.js';

/**
 * 去掉未填的查询参数，避免拼出 "status=undefined" 这类脏 query。
 * @param {Object} [params] 原始查询参数
 * @returns {Object} 清洗后的查询参数
 */
export function cleanParams(params = {}) {
  return Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  );
}

/* ============================== 申请方 ============================== */

/** 当前生效的入驻说明 + 合同扫描件（含版本号）。 */
export const getOnboardingContent = (applicantType, lang = 'zh') =>
  api.get(`/v1/onboarding/content/${applicantType}`, { params: { lang } });

/** 入驻说明历史版本列表（可对比 / 回滚）。 */
export const listContentVersions = (applicantType, lang = 'zh') =>
  api.get(`/v1/onboarding/content/${applicantType}/versions`, { params: { lang } });

/** 该类主体的材料清单（前端据此动态渲染表单）。 */
export const listMaterials = (applicantType) =>
  api.get(`/v1/onboarding/materials/${applicantType}`);

/** 该类主体的保证金档位（含授信额度）。 */
export const listDepositTiers = (applicantType) =>
  api.get(`/v1/onboarding/deposit-tiers/${applicantType}`);

/** 我的入驻进度（全部主体类型）。 */
export const listMyApplications = () => api.get('/v1/onboarding/applications/my');

/** 我的某主体类型申请。 */
export const listMyApplicationsByType = (applicantType) =>
  api.get(`/v1/onboarding/applications/my/${applicantType}`);

/** 取或建草稿（「继续填写」入口）。 */
export const getDraft = (applicantType, lang = 'zh') =>
  api.get('/v1/onboarding/applications/draft', { params: { applicantType, lang } });

/** 保存草稿（分步保存，不做必填校验）。 */
export const saveDraft = (payload) => api.post('/v1/onboarding/applications/draft', payload);

/** 提交申请（全量必填校验 + 合同版本快照）。 */
export const submitApplication = (payload) => api.post('/v1/onboarding/applications/submit', payload);

/** 申请详情（含驳回原因与不合格材料项）。 */
export const getApplication = (id) => api.get(`/v1/onboarding/applications/${id}`);

/** 撤回申请。 */
export const cancelApplication = (id) => api.post(`/v1/onboarding/applications/${id}/cancel`);

/** 重新激活已超时的申请。 */
export const reactivateApplication = (id) => api.post(`/v1/onboarding/applications/${id}/reactivate`);

/** 某申请单的全部材料。 */
export const listAttachments = (applicationId) =>
  api.get(`/v1/onboarding/applications/${applicationId}/attachments`);

/** 替换某材料项的附件（改完重提）。 */
export const replaceAttachments = (applicationId, payload) =>
  api.post(`/v1/onboarding/applications/${applicationId}/attachments`, payload);

/** 删除某个附件。 */
export const deleteAttachment = (attachmentId) =>
  api.delete(`/v1/onboarding/attachments/${attachmentId}`);

/** 上传缴款凭证。 */
export const submitVoucher = (applicationId, payload) =>
  api.post(`/v1/onboarding/applications/${applicationId}/voucher`, payload);

/** 我的缴款记录。 */
export const listMyDeposits = () => api.get('/v1/onboarding/deposits/my');

/** 申请单状态中文标签映射。 */
export const getStatusLabels = () => api.get('/v1/onboarding/status-labels');

/* ============================== 平台侧 ============================== */

/** 入驻申请列表（按主体类型 / 状态筛选）。 */
export const listApplications = (params = {}) =>
  api.get('/v1/admin/onboarding/applications', { params: cleanParams(params) });

/** 申请详情（一屏决策）。 */
export const getAdminApplication = (id) => api.get(`/v1/admin/onboarding/applications/${id}`);

/** 审核：APPROVE / REJECT / RETURN，支持逐材料项结论。 */
export const reviewApplication = (id, payload) =>
  api.post(`/v1/admin/onboarding/applications/${id}/review`, payload);

/** 置身份证认证结果。 */
export const setKycStatus = (id, payload) =>
  api.post(`/v1/admin/onboarding/applications/${id}/kyc`, payload);

/** 缴款超时扫描。 */
export const sweepExpiredApplications = () =>
  api.post('/v1/admin/onboarding/applications/expire-sweep');

/** 待重试激活列表（激活失败人工补偿入口）。 */
export const listPendingActivations = () => api.get('/v1/admin/onboarding/activations/pending');

/** 人工重试激活（幂等）。 */
export const retryActivation = (id) =>
  api.post(`/v1/admin/onboarding/applications/${id}/retry-activation`);

/** 合同版本列表。 */
export const listContracts = (applicantType, lang = 'zh') =>
  api.get('/v1/admin/onboarding/contracts', { params: { applicantType, lang } });

/** 保存说明草稿。 */
export const saveContractDraft = (payload) => api.post('/v1/admin/onboarding/contracts/draft', payload);

/** 发布新版本（自动生成版本号）。 */
export const publishContract = (payload) => api.post('/v1/admin/onboarding/contracts/publish', payload);

/** 回滚到历史版本。 */
export const rollbackContract = (id) => api.post(`/v1/admin/onboarding/contracts/${id}/rollback`);

/** 材料清单（含停用项）。 */
export const listMaterialsAdmin = (applicantType) =>
  api.get(`/v1/admin/onboarding/materials/${applicantType}`);

/** 新增 / 更新材料要求。 */
export const upsertMaterial = (payload) => api.post('/v1/admin/onboarding/materials', payload);

/** 启用 / 停用材料要求。 */
export const setMaterialEnabled = (id, enabled) =>
  api.post(`/v1/admin/onboarding/materials/${id}/enabled`, null, { params: { enabled } });

/** 删除材料要求。 */
export const deleteMaterial = (id) => api.delete(`/v1/admin/onboarding/materials/${id}`);

/** 保证金档位列表。 */
export const listTiersAdmin = (applicantType) =>
  api.get('/v1/admin/onboarding/deposit-tiers', { params: cleanParams({ applicantType }) });

/** 新增 / 更新档位（保证金金额 / 授信倍率 / 绝对额度覆盖）。 */
export const upsertTier = (payload) => api.post('/v1/admin/onboarding/deposit-tiers', payload);

/** 缴款列表（按状态筛选）。 */
export const listDeposits = (status) =>
  api.get('/v1/admin/onboarding/deposits', { params: cleanParams({ status }) });

/** 确认到账 → 触发自动激活。 */
export const confirmDeposit = (id) => api.post(`/v1/admin/onboarding/deposits/${id}/confirm`);

/** 驳回缴款凭证。 */
export const rejectDeposit = (id, reason) =>
  api.post(`/v1/admin/onboarding/deposits/${id}/reject`, { reason });

/** 逐材料项审核。 */
export const reviewAttachment = (attachmentId, payload) =>
  api.post(`/v1/admin/onboarding/attachments/${attachmentId}/review`, payload);

/* ============================== 组织治理 ============================== */

/** 组织管理列表。 */
export const listOrgs = (params = {}) =>
  api.get('/v1/admin/orgs', { params: cleanParams(params) });

/** 单个组织。 */
export const getOrg = (type, id) => api.get(`/v1/admin/orgs/${type}/${id}`);

/** 禁用组织（原因必填 + 二次确认）。 */
export const disableOrg = (type, id, reason) =>
  api.post(`/v1/admin/orgs/${type}/${id}/disable`, { reason });

/** 启用组织。 */
export const enableOrg = (type, id, reason) =>
  api.post(`/v1/admin/orgs/${type}/${id}/enable`, { reason });

/** 改档（重算授信额度）。 */
export const changeOrgTier = (type, id, tierId, reason) =>
  api.post(`/v1/admin/orgs/${type}/${id}/tier`, { tierId, reason });

/** 授信额度占用详情。 */
export const getCreditUsage = (type, id) => api.get(`/v1/admin/orgs/${type}/${id}/credit`);

/** 组织的保证金缴款记录。 */
export const listOrgDeposits = (type, id) => api.get(`/v1/admin/orgs/${type}/${id}/deposits`);

/** 组织状态变更历史。 */
export const listOrgStatusLogs = (type, id) => api.get(`/v1/admin/orgs/${type}/${id}/status-logs`);

/* ============================== 子账号 ============================== */

/** 当前主体的子账号列表。 */
export const listSubAccounts = () => api.get('/v1/org/sub-accounts');

/** 新建子账号。 */
export const createSubAccount = (payload) => api.post('/v1/org/sub-accounts', payload);

/** 停用子账号。 */
export const disableSubAccount = (id) => api.post(`/v1/org/sub-accounts/${id}/disable`);

/** 启用子账号。 */
export const enableSubAccount = (id) => api.post(`/v1/org/sub-accounts/${id}/enable`);

/** 授权（ALL / PARTIAL）。 */
export const grantSubAccount = (id, payload) => api.post(`/v1/org/sub-accounts/${id}/grant`, payload);

/** 撤销授权。 */
export const revokeSubAccount = (id) => api.post(`/v1/org/sub-accounts/${id}/revoke`);

/** 查看子账号当前授权与明细。 */
export const getSubAccountGrant = (id) => api.get(`/v1/org/sub-accounts/${id}/grant`);

/** 权限码目录（菜单树 + 按钮码）。 */
export const listPermissionCatalog = () => api.get('/v1/org/sub-accounts/permission-catalog');

/** 当前登录身份（是否子账号 / 所属主体）。 */
export const getSubAccountMe = () => api.get('/v1/org/sub-accounts/me');
