-- =====================================================================
-- Claw 平台 V55 增量（菜单目录树「旧 V25 树」归并到「V40 镜像 nav.js 的新树」）
--
-- 背景（V54 菜单过滤真正生效后才暴露的问题）：
--   V25 曾 seed 过一棵菜单树（menu:operations / menu:finance / menu:risk / menu:system /
--   menu:dashboard …），V40 又按 web/src/nav.js 重新 seed 了一棵（menu:ops / menu:fin /
--   menu:risk-center / menu:sys / menu:workbench …）。V40 用的是
--   ON CONFLICT (code) DO NOTHING，因此**V25 的行胜出**：
--     · menu:orders / menu:ledger / menu:roles … 的 parent_code 仍指向旧分组；
--     · menu:manufacturer / menu:users / menu:complaints / menu:countries 仍是根节点；
--     · 新旧分组同时存在（"运营管理"×2、"财务管理"×2、"风控合规"×2、"系统设置"×2）。
--   /permissions/mine 以前直接返回全量目录（没过滤），这个重复没人看得见；
--   V54 之后菜单按 menu:* 过滤并真正下发到前端侧边栏，重复分组就会直接显示出来。
--
-- 修复策略（只做归并，不新增业务权限点）：
--   1) 把 V25 旧分组下的子项重新挂到 V40/nav.js 的分组下（menu:{navKey} 严格镜像 nav.js）；
--   2) 顺手对齐 menu:risk 的 name/path（V25 建成"风控合规"分组，V40 本意是"风控监控"叶子）；
--   3) 删除 nav.js 中不存在的 V25 残留（旧分组行 + 两个孤儿叶子）。
--
-- 幂等：UPDATE 用固定 IN 列表（重复执行结果一致）；DELETE 用固定 IN 列表（已删再删为 no-op）。
-- 安全：本脚本只动 permissions 表里的 MENU 行，不涉及 BUTTON 权限点；
--       role_permissions / role_template_permissions 对 permissions(code) 都是 ON DELETE CASCADE，
--       删除会级联清理关联行（这些码在 nav.js 中不存在，本就不该被授予）。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 运营管理（V25: menu:operations → V40: menu:ops）----------
UPDATE permissions SET parent_code = 'menu:ops'
 WHERE code IN ('menu:orders', 'menu:swap-orders', 'menu:stations', 'menu:assets',
                'menu:asset-trace', 'menu:custody');

-- ---------- 2) 商品管理（V25: menu:manufacturer 是根节点 → 归入 menu:goods）----------
UPDATE permissions SET parent_code = 'menu:goods'
 WHERE code = 'menu:manufacturer';

-- ---------- 3) 财务管理（V25: menu:finance → V40: menu:fin）----------
UPDATE permissions SET parent_code = 'menu:fin'
 WHERE code IN ('menu:ledger', 'menu:settlements', 'menu:payments', 'menu:deposits',
                'menu:reconciliations', 'menu:profit', 'menu:fee');

-- ---------- 4) 风控合规（V25: menu:risk 是分组、menu:risk-monitor 才是叶子 → nav.js 只有 risk）----------
UPDATE permissions SET parent_code = 'menu:risk-center'
 WHERE code IN ('menu:risk', 'menu:alerts', 'menu:arbitration', 'menu:complaints');
-- V25 把 menu:risk 建成了"风控合规"分组；V40 的本意（也是 nav.js 的语义）是"风控监控"叶子。
UPDATE permissions SET name = '风控监控', path = '/risk'
 WHERE code = 'menu:risk';

-- ---------- 5) 系统设置（V25: menu:system → V40: menu:sys）----------
UPDATE permissions SET parent_code = 'menu:sys'
 WHERE code IN ('menu:users', 'menu:roles', 'menu:permission', 'menu:settings', 'menu:countries');

-- ---------- 6) 删除 nav.js 中不存在的 V25 残留 ----------
-- 旧分组行（其子节点已在上面全部改挂到新分组，删除后不会留下孤儿节点）；
-- menu:dashboard / menu:risk-monitor 是 V25 的示例叶子，nav.js 里没有对应 key
-- （工作台是 menu:workbench，风控监控是 menu:risk），留着会变成游离菜单项。
DELETE FROM permissions
 WHERE code IN ('menu:operations', 'menu:finance', 'menu:system',
                'menu:dashboard', 'menu:risk-monitor');
