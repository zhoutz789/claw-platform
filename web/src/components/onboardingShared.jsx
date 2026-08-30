// 入驻管理域（增量 C）页面共享的取数与展示工具。
//
// 只做三件事，避免 7 个新页各写一遍：
//   1. 入驻申请状态的中文标签与颜色（周老板要求的「待审 / 待缴保证金 / 已付款 / 已激活 /
//      已驳回 / 已禁用」）；
//   2. 文件上传（复用既有 POST /api/v1/admin/upload）；
//   3. 金额与占比格式化。
import { Tag } from 'antd';
import dayjs from 'dayjs';
import api from '../api';

/** 空值占位符。 */
export const EMPTY = '—';

/** 主体类型可选项（与后端 PrincipalType 一致）。 */
export const APPLICANT_TYPES = [
  { value: 'STATION', label: '服务站' },
  { value: 'MANUFACTURER', label: '厂家' },
  { value: 'MERCHANT', label: '商家' },
];

/** 组织入驻状态（与后端 OnboardingStatus 一致；激活态为 ACTIVATED，勿与运营状态 status 的 ACTIVE 混淆）。 */
export const ORG_STATUS = [
  { value: 'PENDING', label: '待激活' },
  { value: 'ACTIVATED', label: '已激活' },
  { value: 'DISABLED', label: '已禁用' },
  { value: 'REJECTED', label: '已驳回' },
];

/**
 * 入驻申请单状态的中文标签与颜色（周老板要求的列表标签）。
 * key 与后端 OnboardingApplicationStatus 枚举同名。
 */
export const APP_STATUS_META = {
  DRAFT:               { label: '草稿',       color: 'default' },
  SUBMITTED:           { label: '已提交',     color: 'blue' },
  REVIEWING:           { label: '待审',       color: 'processing' },
  APPROVED:            { label: '待缴保证金', color: 'gold' },
  RETURNED:            { label: '退回补正',   color: 'orange' },
  REJECTED:            { label: '已驳回',     color: 'red' },
  PENDING_PAY_CONFIRM: { label: '待确认到账', color: 'cyan' },
  DEPOSIT_PAID:        { label: '已付款',     color: 'green' },
  ACTIVATED:           { label: '已激活',     color: 'success' },
  EXPIRED:             { label: '已超时',     color: 'default' },
  CANCELLED:           { label: '已撤回',     color: 'default' },
};

/** 管理页可筛选的状态（周老板点名的 6 个 + 常用补集）。 */
export const APP_STATUS_FILTER = [
  { value: 'REVIEWING', label: '待审' },
  { value: 'APPROVED', label: '待缴保证金' },
  { value: 'PENDING_PAY_CONFIRM', label: '待确认到账' },
  { value: 'DEPOSIT_PAID', label: '已付款' },
  { value: 'ACTIVATED', label: '已激活' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'RETURNED', label: '退回补正' },
  { value: 'EXPIRED', label: '已超时' },
  { value: 'CANCELLED', label: '已撤回' },
  { value: 'DRAFT', label: '草稿' },
];

/**
 * 渲染申请单状态标签。
 * @param {string} v 状态枚举值
 * @param {string} [orgStatus] 已激活时关联组织的入驻状态（据此显示「已禁用」）
 * @returns {JSX.Element} 标签
 */
export function AppStatusTag({ value, orgStatus }) {
  // ACTIVATED 的展示跟随组织 onboasing_status：组织被禁用时显示红标「已禁用」
  if (value === 'ACTIVATED' && orgStatus === 'DISABLED') {
    return <Tag color="red">已禁用</Tag>;
  }
  const meta = APP_STATUS_META[value] || { label: value || EMPTY, color: 'default' };
  return <Tag color={meta.color}>{meta.label}</Tag>;
}

/**
 * 渲染组织入驻状态标签（组织管理页用）。
 * @param {Object} props 组件属性
 * @param {string} props.value 入驻状态
 * @returns {JSX.Element} 标签
 */
export function OrgStatusTag({ value }) {
  const map = {
    PENDING:   { label: '待激活', color: 'default' },
    // 后端激活态统一为 ACTIVATED；保留 ACTIVE 兜底，防止存量/过渡期数据退化成原始字符串
    ACTIVATED: { label: '已激活', color: 'green' },
    ACTIVE:    { label: '已激活', color: 'green' },
    DISABLED:  { label: '已禁用', color: 'red' },
    REJECTED:  { label: '已驳回', color: 'volcano' },
  };
  const m = map[value] || { label: value || EMPTY, color: 'default' };
  return <Tag color={m.color}>{m.label}</Tag>;
}

/**
 * 格式化后端返回的时间（Instant，ISO-8601 UTC 字符串）。
 * @param {string|number|null|undefined} v 时间值
 * @param {string} [pattern] dayjs 输出格式
 * @returns {string} 格式化后的时间文本
 */
export function fmtTime(v, pattern = 'YYYY-MM-DD HH:mm') {
  if (v === null || v === undefined || v === '') return EMPTY;
  const d = dayjs(v);
  return d.isValid() ? d.format(pattern) : EMPTY;
}

/**
 * 格式化金额（千分位 + 2 位小数）。
 * @param {number|string|null|undefined} v 金额
 * @param {string} [currency] 币种后缀
 * @returns {string} 格式化文本
 */
export function fmtMoney(v, currency = '') {
  if (v === null || v === undefined || v === '') return EMPTY;
  const n = Number(v);
  if (Number.isNaN(n)) return EMPTY;
  const text = n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return currency ? `${text} ${currency}` : text;
}

/**
 * 格式化占比（0–1 → 百分比）。
 * @param {number|string|null|undefined} v 占比
 * @returns {string} 百分比文本
 */
export function fmtRatio(v) {
  if (v === null || v === undefined || v === '') return EMPTY;
  const n = Number(v);
  if (Number.isNaN(n)) return EMPTY;
  return `${(n * 100).toFixed(1)}%`;
}

/**
 * 上传单个文件（复用既有 POST /api/v1/admin/upload）。
 *
 * @param {File} file 待上传文件
 * @returns {Promise<string>} 可访问 URL（/files/... 由后端静态资源映射提供）
 */
export async function uploadFile(file) {
  const fd = new FormData();
  fd.append('file', file);
  const res = await api.post('/v1/admin/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return res.url;
}

/**
 * 上传前校验：JPG / PNG / PDF 白名单 + 单张 ≤ 10MB（与后端 OnboardingAttachmentService 一致）。
 *
 * @param {File} file 待校验文件
 * @returns {boolean} true 放行；false 时 antd Upload 会中断上传
 */
export function beforeUploadAttachment(file) {
  const allowed = ['image/jpeg', 'image/jpg', 'image/png', 'application/pdf'];
  if (!allowed.includes(file.type)) {
    return false;
  }
  return file.size <= 10 * 1024 * 1024;
}

/** 材料输入控件类型（与后端 MaterialCode.InputType 一致）。 */
export const INPUT_TYPES = [
  { value: 'TEXT', label: '单行文本' },
  { value: 'TEXTAREA', label: '多行文本' },
  { value: 'IMAGE', label: '单图' },
  { value: 'IMAGES', label: '多图' },
  { value: 'FILE', label: '文件' },
  { value: 'LOCATION', label: '定位' },
];

/** 判断输入类型是否为附件类（图片 / 文件）。 */
export const isAttachmentInput = (inputType) =>
  inputType === 'IMAGE' || inputType === 'IMAGES' || inputType === 'FILE';

/** 判断输入类型是否为文本类。 */
export const isTextInput = (inputType) => inputType === 'TEXT' || inputType === 'TEXTAREA';

/** 授权模式（与后端 GrantMode 一致）。 */
export const GRANT_MODES = [
  { value: 'ALL', label: '全部功能' },
  { value: 'PARTIAL', label: '部分功能' },
];
