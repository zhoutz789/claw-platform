-- V40：权限通电内核种子——后端权威菜单/按钮权限目录 + 平台固定角色授予全量权限。
--
-- 1) 菜单目录树：严格镜像 web/src/nav.js 的 9 大分组 + 约 45 个叶子，code 统一为 menu:{navKey}，
--    供前端 menuStore 接入「后端权威菜单」与 permStore 做菜单级可见性过滤。
-- 2) 按钮权限点：{资源}:{动作}（create/update/delete/export），与 @RequirePermission 注解码一一对应，
--    供前端 <Perm> / <RequirePermRoute> 做按钮级 / 路由级控制。
-- 3) 平台固定角色（auto_grant=false，即后台管理员）grants 置为 ["*"]，拥有全部权限位，
--    使「权限通电」后管理员仍可全链路操作；普通用户（auto_grant=true，如 CONSUMER）不含 "*"，写/导出接口将被 403。
--
-- 全量幂等：INSERT ... ON CONFLICT (code) DO NOTHING；UPDATE 仅对未配置过 grants 的平台角色生效。
SET search_path = claw;

-- ---------- 1) 菜单目录树（镜像 nav.js） ----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no, icon) VALUES
  -- 顶级 / 分组
  ('menu:workbench','工作台','MENU',NULL,'/workbench',10,'dashboard'),
  ('menu:prod','产品管理','MENU',NULL,NULL,20,'appstore'),
  ('menu:project','项目管理','MENU',NULL,NULL,30,'project'),
  ('menu:goods','商品管理','MENU',NULL,NULL,40,'shopping'),
  ('menu:task','任务发布','MENU',NULL,NULL,50,'rocket'),
  ('menu:ops','运营管理','MENU',NULL,NULL,60,'deployment'),
  ('menu:fin','财务管理','MENU',NULL,NULL,70,'account-book'),
  ('menu:risk-center','风控合规','MENU',NULL,NULL,80,'safety'),
  ('menu:sys','系统设置','MENU',NULL,NULL,90,'setting')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  -- prod 分组
  ('menu:product-center','产品中心','MENU','menu:prod','/product-center',21),
  ('menu:certificate','合格证','MENU','menu:prod','/certificate',22),
  ('menu:bind-ownership','产权绑定','MENU','menu:prod','/bind-ownership',23),
  ('menu:product-template','产品模板','MENU','menu:prod','/product-template',24),
  ('menu:data-binding','设备数据接入','MENU','menu:prod','/device-data-access',25),
  ('menu:authorization','授权管理','MENU','menu:prod','/authorization',26),
  -- project 分组
  ('menu:project-management','项目管理','MENU','menu:project','/project-management',31),
  -- goods 分组
  ('menu:goods-list','商品列表','MENU','menu:goods','/goods-list',41),
  ('menu:product-wizard','商品向导','MENU','menu:goods','/product-wizard',42),
  ('menu:brand-onboarding','品牌入驻','MENU','menu:goods','/brand-onboarding',43),
  ('menu:manufacturer','厂家与商品','MENU','menu:goods','/manufacturer',44),
  ('menu:order-manage','订单管理','MENU','menu:goods','/order-manage',45),
  -- task 分组
  ('menu:task-drone','无人机任务','MENU','menu:task','/task-drone',51),
  ('menu:task-rent','租赁任务','MENU','menu:task','/task-rent',52),
  -- ops 分组
  ('menu:orders','订单管理','MENU','menu:ops','/orders',61),
  ('menu:swap-orders','换电订单','MENU','menu:ops','/swap-orders',62),
  ('menu:stations','站点管理','MENU','menu:ops','/stations',63),
  ('menu:assets','资产管理','MENU','menu:ops','/assets',64),
  ('menu:asset-trace','资产溯源','MENU','menu:ops','/asset-trace',65),
  ('menu:custody','产权链','MENU','menu:ops','/custody',66),
  ('menu:product-iot','产品 IoT','MENU','menu:ops','/product-iot',67),
  ('menu:shared-pool','共享池','MENU','menu:ops','/shared-pool',68),
  ('menu:recovery','回收处置','MENU','menu:ops','/recovery',69),
  -- fin 分组
  ('menu:ledger','账本','MENU','menu:fin','/ledger',71),
  ('menu:payments','支付','MENU','menu:fin','/payments',72),
  ('menu:deposits','押金','MENU','menu:fin','/deposits',73),
  ('menu:settlements','结算','MENU','menu:fin','/settlements',74),
  ('menu:reconciliations','对账','MENU','menu:fin','/reconciliations',75),
  ('menu:profit','分账报告','MENU','menu:fin','/profit',76),
  ('menu:fee','费率配置','MENU','menu:fin','/fee',77),
  ('menu:operator','运营商','MENU','menu:fin','/operator',78),
  -- risk-center 分组
  ('menu:risk','风控监控','MENU','menu:risk-center','/risk',81),
  ('menu:alerts','异常告警','MENU','menu:risk-center','/alerts',82),
  ('menu:insurance','保险','MENU','menu:risk-center','/insurance',83),
  ('menu:arbitration','争议仲裁','MENU','menu:risk-center','/arbitration',84),
  ('menu:complaints','投诉','MENU','menu:risk-center','/complaints',85),
  -- sys 分组
  ('menu:users','平台用户','MENU','menu:sys','/users',91),
  ('menu:app-portal','应用门户','MENU','menu:sys','/app-portal',92),
  ('menu:roles','角色权限','MENU','menu:sys','/roles',93),
  ('menu:permission','权限矩阵','MENU','menu:sys','/permission',94),
  ('menu:menu-permission','菜单权限','MENU','menu:sys','/menu-permission',95),
  ('menu:menu-manager','菜单管理','MENU','menu:sys','/menu-manager',96),
  ('menu:settings','系统配置','MENU','menu:sys','/settings',97),
  ('menu:countries','国家地区','MENU','menu:sys','/countries',98),
  ('menu:asset-params','资产参数','MENU','menu:sys','/asset-params',99)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 按钮权限点：{资源}:{动作}（create/update/delete/export）----------
-- 与后端 @RequirePermission 注解码、前端 <Perm> 码一一对应。parent_code 留空（独立于菜单树存在）。
INSERT INTO permissions (code, name, ptype, parent_code, sort_no)
SELECT r || ':' || a, r || ' ' || a, 'BUTTON', NULL, 1
FROM unnest(ARRAY[
    'asset','customer-order','order','swap-order','station','custody','recovery',
    'manufacturer','product','sku','iot','fee','insurance','operator','risk',
    'settlement','profit','ledger','payment','deposit','reconciliation',
    'user','role','permission','department','setting','shared-pool','project','dashboard'
]) AS t(r)
CROSS JOIN unnest(ARRAY['create','update','delete','export']) AS a(action)
ON CONFLICT (code) DO NOTHING;

-- 少量语义化按钮点（与历史 V25 示例保持一致）
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('order:refund','订单退款','BUTTON','menu:orders',2),
  ('user:freeze','用户冻结','BUTTON','menu:users',2)
ON CONFLICT (code) DO NOTHING;

-- ---------- 3) 平台固定角色授予全量权限（超级管理员通配符）----------
-- 仅对「未配置过 grants」的平台固定角色（auto_grant=false）生效，避免覆盖既有精细配置。
UPDATE roles
SET grants = '["*"]'
WHERE auto_grant = FALSE
  AND (grants IS NULL OR grants = '{}' OR grants = '[]' OR grants = '""' OR grants = '');
