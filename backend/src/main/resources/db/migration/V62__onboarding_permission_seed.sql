-- =====================================================================
-- Claw 平台 V62 增量（入驻管理 · 增量 C 第四批：权限码种子与 MERCHANT 模板）
-- 依据：增量设计-入驻管理.md §2.4，做法对齐 V54
--
-- 三步走：
--   1) 新增 8 个 menu:* 权限码（前端 permStore 靠 hasPerm('menu:{navKey}') 判菜单可见性，
--      只建页面不建 menu 码 → 真实后端下菜单不显示，V54 修复过的同类缺陷）
--   2) 新增 9 个 action 权限码（ptype='BUTTON'，parent_code 挂对应 menu）
--   3) MERCHANT 角色模板 + 模板挂载 + roles.grants 回写
--
-- ⚠️ 陷阱 1：MERCHANT 角色码 V1 已种下，且 V11 把 9 个旧角色全部置 status='INACTIVE'。
--    若按常规写 ON CONFLICT (code) DO NOTHING，插入会被静默跳过 → 新商家仍为 INACTIVE，
--    商家绑定后拿不到任何权限，整条商家入驻链路静默失效且极难排查。
--    故此处必须 DO UPDATE 显式复活，并在 T01 验收点强制验证 status='ACTIVE'。
-- ⚠️ 陷阱 2：roles 表的列是 name_i18n，不是 name（role_templates 才是 name）。
-- ⚠️ 陷阱 3：roles.grants 在 V18 已从 JSONB 改为 TEXT，回写时必须 ::text。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 入驻管理分组 + 7 个页面菜单码 ----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:onboarding',                '入驻管理',         'MENU', NULL,              NULL,                        48),
  ('menu:onboarding-apply',          '我的入驻',         'MENU', 'menu:onboarding', '/onboarding-apply',         481),
  ('menu:onboarding-review',         '入驻申请管理',     'MENU', 'menu:onboarding', '/onboarding-review',        482),
  ('menu:onboarding-content',        '入驻说明与合同',   'MENU', 'menu:onboarding', '/onboarding-content',       483),
  ('menu:onboarding-deposit-tiers',  '保证金档位',       'MENU', 'menu:onboarding', '/onboarding-deposit-tiers', 484),
  ('menu:onboarding-deposit-confirm','保证金缴纳确认',   'MENU', 'menu:onboarding', '/onboarding-deposit-confirm',485),
  ('menu:org-manage',                '组织管理',         'MENU', 'menu:onboarding', '/org-manage',               486),
  ('menu:sub-accounts',              '子账号管理',       'MENU', 'menu:onboarding', '/sub-accounts',             487)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 9 个按钮级权限码 ----------
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('onboarding:apply:self',      '申请入驻',         'BUTTON', 'menu:onboarding-apply',           1),
  ('onboarding:review:manage',   '入驻审核',         'BUTTON', 'menu:onboarding-review',          1),
  ('onboarding:content:manage',  '说明与合同配置',   'BUTTON', 'menu:onboarding-content',         1),
  ('onboarding:deposit:manage',  '档位配置',         'BUTTON', 'menu:onboarding-deposit-tiers',   1),
  ('onboarding:deposit:confirm', '保证金到账确认',   'BUTTON', 'menu:onboarding-deposit-confirm', 1),
  ('org:status:manage',          '组织禁用/启用',    'BUTTON', 'menu:org-manage',                 1),
  ('org:subaccount:manage',      '子账号管理',       'BUTTON', 'menu:sub-accounts',               1),
  ('org:subaccount:view',        '子账号查看',       'BUTTON', 'menu:sub-accounts',               2),
  ('org:credit:view',            '授信额度查看',     'BUTTON', 'menu:org-manage',                 2)
ON CONFLICT (code) DO NOTHING;

-- ---------- 3.1) 启用 MERCHANT 角色（权限真源）—— 注意是 name_i18n 不是 name ----------
-- V11 已将其置 INACTIVE，此处必须 DO UPDATE 复活；data_scope 对齐 V47 的 MANUFACTURER / STATION。
INSERT INTO roles (code, name_i18n, grants, auto_grant, status, data_scope, data_scope_types, data_rule_ids)
VALUES ('MERCHANT', 'role.merchant.name', '[]', FALSE, 'ACTIVE', 'CUSTOM', '[]', '')
ON CONFLICT (code) DO UPDATE SET
    status           = 'ACTIVE',
    data_scope       = 'CUSTOM',
    data_scope_types = '[]',
    data_rule_ids    = '',
    updated_at       = now();

-- ---------- 3.2) 新增第 5 个角色模板（商家） ----------
INSERT INTO role_templates (code, name, principal_type, description)
VALUES ('MERCHANT', '商家', 'MERCHANT', '商家主体模板（入驻管理增量 C 新增）')
ON CONFLICT (code) DO NOTHING;

-- ---------- 3.3) 模板 ↔ 权限码打包 ----------

-- 平台管理员：全部 8 个 menu + 全部 9 个 action（+ V54 已有的 menu:merchants）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('PLATFORM_ADMIN', 'menu:onboarding'),
  ('PLATFORM_ADMIN', 'menu:onboarding-apply'),
  ('PLATFORM_ADMIN', 'menu:onboarding-review'),
  ('PLATFORM_ADMIN', 'menu:onboarding-content'),
  ('PLATFORM_ADMIN', 'menu:onboarding-deposit-tiers'),
  ('PLATFORM_ADMIN', 'menu:onboarding-deposit-confirm'),
  ('PLATFORM_ADMIN', 'menu:org-manage'),
  ('PLATFORM_ADMIN', 'menu:sub-accounts'),
  ('PLATFORM_ADMIN', 'onboarding:apply:self'),
  ('PLATFORM_ADMIN', 'onboarding:review:manage'),
  ('PLATFORM_ADMIN', 'onboarding:content:manage'),
  ('PLATFORM_ADMIN', 'onboarding:deposit:manage'),
  ('PLATFORM_ADMIN', 'onboarding:deposit:confirm'),
  ('PLATFORM_ADMIN', 'org:status:manage'),
  ('PLATFORM_ADMIN', 'org:subaccount:manage'),
  ('PLATFORM_ADMIN', 'org:subaccount:view'),
  ('PLATFORM_ADMIN', 'org:credit:view')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 服务站：入驻申请入口 + 子账号管理 + 额度查看
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('STATION', 'menu:onboarding'),
  ('STATION', 'menu:onboarding-apply'),
  ('STATION', 'menu:sub-accounts'),
  ('STATION', 'onboarding:apply:self'),
  ('STATION', 'org:subaccount:view'),
  ('STATION', 'org:subaccount:manage'),
  ('STATION', 'org:credit:view')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 厂家：同服务站
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER', 'menu:onboarding'),
  ('MANUFACTURER', 'menu:onboarding-apply'),
  ('MANUFACTURER', 'menu:sub-accounts'),
  ('MANUFACTURER', 'onboarding:apply:self'),
  ('MANUFACTURER', 'org:subaccount:view'),
  ('MANUFACTURER', 'org:subaccount:manage'),
  ('MANUFACTURER', 'org:credit:view')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 商家：同服务站
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MERCHANT', 'menu:onboarding'),
  ('MERCHANT', 'menu:onboarding-apply'),
  ('MERCHANT', 'menu:sub-accounts'),
  ('MERCHANT', 'onboarding:apply:self'),
  ('MERCHANT', 'org:subaccount:view'),
  ('MERCHANT', 'org:subaccount:manage'),
  ('MERCHANT', 'org:credit:view')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 用户：任何用户都可申请入驻
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('CUSTOMER', 'menu:onboarding'),
  ('CUSTOMER', 'menu:onboarding-apply'),
  ('CUSTOMER', 'onboarding:apply:self')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 3.4) 回写 roles.grants（权限真源）—— 严格沿用 V54 §6 模式 ----------
-- 只覆盖业务角色码；CUSTOMER 的 grants 是 V11 种下的 {"permissions":[...]} 对象结构，
-- parseGrants 只认数组，动了会清空老用户权限 —— 禁止纳入。
-- 该 UPDATE 会覆盖 MANUFACTURER/STATION 的现有 grants —— 这是预期行为
-- （把新增的 menu:sub-accounts 等码追加进已绑定账号的权限集），与 V54 一致。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION', 'MERCHANT');
