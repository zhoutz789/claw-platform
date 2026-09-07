-- =====================================================================
-- Claw 平台 V79 增量（③ 合格证：可定制 EAV 模板 + 可编辑识别信息 + 权限种子）
-- 依据：用户 2026-09-06 拍板「合格证模板复用 EAV（已有先例、好维护）；
--       打印布局前端 A4 打印 + 布局可编辑，需要存档生成 PDF」。
--
-- 范围：
--   1) device_certificates 增加 data_json（可编辑识别信息；spec_json 保持不可变出证快照）
--   2) certificate_template_fields（全局合格证模板 EAV，复用 product_template_fields 范式）
--   3) 权限种子（两步法，沿用 V47 / V67 / V77 / V78 范式）
--       3.1 资源（BUTTON）权限码 mfg:certificate:edit / mfg:certificate:template
--       3.2 角色模板挂载（MANUFACTURER / PLATFORM_ADMIN）
--       3.3 roles.grants 回写（仅 MANUFACTURER / STATION）
--
-- ⚠️ 陷阱同 V78：PLATFORM_ADMIN 走通配符，不在 3.3 回写范围；CUSTOMER 是对象结构禁止纳入；
--    roles.grants 是 TEXT，回写必须 ::text。
-- 幂等性：ALTER COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS / INSERT 带
--   ON CONFLICT DO NOTHING；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) device_certificates 增加可编辑识别信息列 ----------
ALTER TABLE device_certificates
  ADD COLUMN IF NOT EXISTS data_json jsonb;

-- ---------- 2) 合格证模板字段 EAV（全局单一合格证类型，按 field_key 唯一）----------
-- 范式对齐 product_template_fields（V35）：单表 EAV，type ∈ number/text/select/date/boolean。
-- 区别：合格证模板是全局的（不绑定 product_id），故以 field_key UNIQUE 约束。
CREATE TABLE IF NOT EXISTS certificate_template_fields (
  id           BIGSERIAL PRIMARY KEY,
  field_key    VARCHAR(64) NOT NULL UNIQUE,
  label        VARCHAR(120) NOT NULL,
  type         VARCHAR(16) NOT NULL,   -- number/text/select/date/boolean
  unit         VARCHAR(16),
  options_json TEXT,                   -- select 选项(JSON 数组)
  required     BOOLEAN NOT NULL DEFAULT false,
  sort_no      INT DEFAULT 0,
  tenant_id    BIGINT NOT NULL DEFAULT 1,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cert_tpl_sort ON certificate_template_fields (sort_no);

-- 默认模板字段（出厂检验常用识别项；ON CONFLICT 幂等）
INSERT INTO certificate_template_fields (field_key, label, type, options_json, required, sort_no) VALUES
  ('inspector',    '检验员',   'text',   NULL,                 true,  1),
  ('conclusion',   '检验结论', 'select', '["合格","不合格"]',  true,  2),
  ('factory_no',   '出厂编号', 'text',   NULL,                 false, 3),
  ('factory_date', '出厂日期', 'date',   NULL,                 false, 4),
  ('remark',       '备注',     'text',   NULL,                 false, 5)
ON CONFLICT (field_key) DO NOTHING;

-- ---------- 3.1) 资源（BUTTON）权限码：编辑识别信息 / 管理模板 ----------
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('mfg:certificate:edit',     '合格证编辑',     'BUTTON', 'menu:certificate', 8),
  ('mfg:certificate:template',  '合格证模板管理', 'BUTTON', 'menu:certificate', 9)
ON CONFLICT (code) DO NOTHING;

-- ---------- 3.2) 角色模板挂载（厂家 + 平台管理员可编辑/管模板；服务站仅查看）----------
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER',    'mfg:certificate:edit'),
  ('MANUFACTURER',    'mfg:certificate:template'),
  ('PLATFORM_ADMIN',  'mfg:certificate:edit'),
  ('PLATFORM_ADMIN',  'mfg:certificate:template')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 3.3) 回写 roles.grants（权限真源）—— 仅覆盖 MANUFACTURER / STATION ----------
-- PLATFORM_ADMIN 走 V40 种下的通配符；CUSTOMER 是对象结构，禁止纳入。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION');
