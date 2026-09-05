// 权限门组件集（P1-T05 前端权限内核）。
//
// 提供三种粒度的权限控制构件：
//   <Perm>               —— 内联门：满足条件渲染 children，否则渲染 fallback（默认 null）。
//   <RequirePermRoute>   —— 路由门：满足条件渲染页面，否则渲染 <ForbiddenPage/>（403）。
//   <ForbiddenPage/>     —— 403 无权限提示页。
//
// 判定入参（computeAllowed）：
//   - code    单码判定（如 "asset:create"）
//   - any     持有其一即可（OR）
//   - all     必须全部持有（AND）
//   - menuKey 映射为 "menu:{menuKey}" 的菜单可见性判定（与后端菜单权限一致）
// 以上入参可组合；任意一项命中即放行。permStore 处于降级（allGranted）时一律放行。
import { useTranslation } from 'react-i18next';
import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';
import { hasPerm, hasAnyPerm, hasAllPerm, isAllGranted, usePermVersion } from '../permStore';

/**
 * 把入参规范成数组。
 * @param {string|string[]|undefined} v 任意 / 全部码
 * @returns {string[]} 数组
 */
function toArr(v) {
  if (!v) return [];
  return Array.isArray(v) ? v : [v];
}

/**
 * 统一判定是否放行。优先级：降级 > menuKey > any(OR) > all(AND) > code。
 * @param {Object} opts 判定入参
 * @param {string} [opts.code] 单权限码
 * @param {string|string[]} [opts.any] 任意其一
 * @param {string|string[]} [opts.all] 必须全部
 * @param {string} [opts.menuKey] 菜单 key（映射 menu:{menuKey}）
 * @returns {boolean} 是否放行
 */
export function computeAllowed({ code, any, all, menuKey } = {}) {
  if (isAllGranted()) return true;
  if (menuKey) return hasPerm(`menu:${menuKey}`);
  if (any && toArr(any).length) return hasAnyPerm(toArr(any));
  if (all && toArr(all).length) return hasAllPerm(toArr(all));
  return hasPerm(code);
}

/**
 * <Perm>：内联权限门。用于在页面内按权限显隐按钮 / 区块。
 * @param {Object} props
 * @param {string} [props.code] 单权限码
 * @param {string|string[]} [props.any] 任意其一
 * @param {string|string[]} [props.all] 必须全部
 * @param {string} [props.menuKey] 菜单 key
 * @param {React.ReactNode} props.children 满足条件时渲染
 * @param {React.ReactNode} [props.fallback] 不满足时渲染，默认 null
 * @returns {React.ReactNode}
 */
export function Perm({ code, any, all, menuKey, children, fallback = null }) {
  return computeAllowed({ code, any, all, menuKey }) ? children : fallback;
}

/**
 * <RequirePermRoute>：路由级权限门。
 * 用法：<Route path="assets" element={<RequirePermRoute menuKey="assets"><Assets/></RequirePermRoute>} />
 * @param {Object} props
 * @param {string} [props.code] 单权限码
 * @param {string|string[]} [props.any] 任意其一
 * @param {string|string[]} [props.all] 必须全部
 * @param {string} [props.menuKey] 菜单 key
 * @param {React.ReactNode} props.children 满足条件时渲染（通常是页面组件）
 * @returns {React.ReactNode}
 */
export function RequirePermRoute({ code, any, all, menuKey, children }) {
  // 订阅 permStore 变更：首帧权限集通常为空（loadPermissions() 尚未返回），
  // 若不订阅，加载完成后不会重新求值，页面会永久停在 403（与 usePerm 写法保持一致）。
  usePermVersion();
  return computeAllowed({ code, any, all, menuKey })
    ? children
    : <ForbiddenPage menuKey={menuKey} />;
}

/**
 * <ForbiddenPage/>：403 无权限提示页。
 * @param {Object} props
 * @param {string} [props.menuKey] 被拒绝的菜单 key（仅用于日志 / 上下文）
 * @returns {React.ReactNode}
 */
export function ForbiddenPage({ menuKey }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <Result
      status="403"
      title="403"
      subTitle={t('perm.forbiddenDesc')}
      extra={
        <Button type="primary" onClick={() => navigate('/workbench')}>
          {t('perm.backWorkbench')}
        </Button>
      }
    />
  );
}

export default { Perm, RequirePermRoute, ForbiddenPage, computeAllowed };
