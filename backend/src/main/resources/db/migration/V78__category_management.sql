-- =====================================================================
-- Claw 平台 V78 增量（② 类别管理：通用多级分类树 + 权限种子）
-- 依据：用户 2026-09-06 拍板「类别 = 一棵通用多级分类树，用来对产品进行定位归类，
--       普通商品和厂家运营的商品都要用到这个分类，类似淘宝的一级/二级/三级/四级分类；
--       发布产品时商品类别选择」。
--
-- 范围：
--   1) categories 表（自引用树，软删除，schema=claw，风格对齐 departments）
--   2) 权限种子（三步法，沿用 V67 / V68 / V77 范式）
--       2.1 菜单码 menu:categories（商品组，发布商品时选用；path=/categories）
--       2.2 资源（BUTTON）权限码 category:create / category:update / category:delete
--       2.3 角色模板挂载（MANUFACTURER / STATION / PLATFORM_ADMIN）
--       2.4 roles.grants 回写（仅覆盖 MANUFACTURER / STATION）
--
-- ⚠️ 陷阱 1：PLATFORM_ADMIN 已靠 V40 种下的 '["*"]' 通配符拥有全部权限位，
--    不在第 2.4 步回写范围内（否则会把超管从「通配」降级为「白名单」）。
-- ⚠️ 陷阱 2：CUSTOMER 的 grants 是 V11 种下的对象结构，parseGrants 只认数组，
--    动了会清空老用户权限 —— 第 2.4 步严格限定 MANUFACTURER / STATION，绝不碰 CUSTOMER。
-- ⚠️ 陷阱 3：roles.grants 在 V18 已从 JSONB 改为 TEXT，回写时必须 ::text。
-- ⚠️ 陷阱 4：permissions 表有 icon 列（可空），沿用 V62 的 6 列写法。
-- 幂等性：CREATE TABLE IF NOT EXISTS / INSERT 带 ON CONFLICT DO NOTHING；
--         UPDATE 按 code 限定；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) categories 表（自引用多级分类树，软删除）----------
CREATE TABLE IF NOT EXISTS categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    parent_id   BIGINT,                                     -- 树根 = NULL；无 DB 外键，层级由后端在内存组装
    level       INT          NOT NULL DEFAULT 0,            -- 层级：根=0，逐级 +1
    sort_no     INT          NOT NULL DEFAULT 0,            -- 同级排序
    code        VARCHAR(64),                                -- 可选层级码（如 'A01' / 'A01B02'）
    tenant_id   BIGINT       NOT NULL DEFAULT 1,
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_categories_parent ON categories (parent_id);
CREATE INDEX IF NOT EXISTS idx_categories_tenant ON categories (tenant_id);
CREATE INDEX IF NOT EXISTS idx_categories_deleted ON categories (deleted);

-- ---------- 2.1) 菜单码 menu:categories（商品组，sort_no=460，接在商品组末）----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:categories', '类别管理', 'MENU', 'menu:goods', '/categories', 460)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2.2) 资源（BUTTON）权限码：端点 @RequirePermission 位 ----------
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('category:create',  '类别创建', 'BUTTON', 'menu:categories', 10),
  ('category:update',  '类别修改', 'BUTTON', 'menu:categories', 11),
  ('category:delete',  '类别删除', 'BUTTON', 'menu:categories', 12)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2.3) 角色模板挂载（厂家 / 服务站 / 平台管理员）----------
-- 菜单码：三类角色均可查看（发布商品时选用）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER',    'menu:categories'),
  ('STATION',         'menu:categories'),
  ('PLATFORM_ADMIN',  'menu:categories')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 资源权限码：类别树由平台管理员与厂家维护（服务站仅查看选用）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER',    'category:create'),
  ('MANUFACTURER',    'category:update'),
  ('MANUFACTURER',    'category:delete'),
  ('PLATFORM_ADMIN',  'category:create'),
  ('PLATFORM_ADMIN',  'category:update'),
  ('PLATFORM_ADMIN',  'category:delete')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 2.4) 回写 roles.grants（权限真源）—— 沿用 V54 / V62 / V67 / V68 / V77 模式 ----------
-- 只覆盖业务角色码；PLATFORM_ADMIN 走通配符（见陷阱 1）；
-- CUSTOMER 是对象结构，禁止纳入（见陷阱 2）。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION');
