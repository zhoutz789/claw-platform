-- ============================================================================
-- V139 无人机切片 1 种子（schema=claw）—— 机型产品类 + 权限 + 运营限制配置
-- 幂等：全部 INSERT 带 ON CONFLICT DO NOTHING / WHERE NOT EXISTS，二次执行无副作用。
-- 不建 drone_command_log：远控复用既有 device_commands（与 VehicleCommandService 一致）。
-- 不动 V1–V136；本迁移接在 V138 之后。
-- ============================================================================

SET search_path = claw;

-- ---------- 1) 8 个机型产品类种子（7 场景 + 1 通用） ----------
INSERT INTO claw.drone_product_classes
    (code, name_zh, name_en, name_km, scenario, capability_tags, default_device_types,
     required_certs, ops_template, dock_supported, enabled)
VALUES
  ('AGRI_SPRAY_UAV', '农业植保无人机', 'Agricultural Spraying UAV', 'ដ្រូនបាញ់ថ្នាំកសិកម្ម', 'AGRICULTURE',
   'AGRICULTURE,SPRAY', 'DRONE_FCU,CAMERA', 'SSCA适航证',
   '{"template":"SPRAY","sprayRateLPerHa":1.5,"altM":3.0,"rowSpacingM":5.0,"billingUnit":"PER_HECTARE"}'::jsonb, true, true),
  ('POWERLINE_INSPECT_UAV', '电力巡检无人机', 'Powerline Inspection UAV', 'ដ្រូនត្រួតពិនិត្យអគ្គិសនី', 'INSPECTION',
   'INSPECTION', 'DRONE_FCU,CAMERA', 'SSCA适航证',
   '{"template":"INSPECTION","hoverPoints":12,"sensor":"THERMAL","billingUnit":"PER_KM"}'::jsonb, true, true),
  ('LOGISTICS_UAV', '低空物流无人机', 'Low-altitude Logistics UAV', 'ដ្រូនដឹកជញ្ជូនតាមអាកាស', 'LOGISTICS',
   'LOGISTICS', 'DRONE_FCU,CAMERA', 'SSCA适航证',
   '{"template":"CARGO","maxPayloadKg":9.0,"returnPolicy":"AUTO","billingUnit":"PER_TRIP"}'::jsonb, true, true),
  ('RESCUE_UAV', '应急救援无人机', 'Emergency Rescue UAV', 'ដ្រូនសង្គ្រោះបន្ទាន់', 'RESCUE',
   'RESCUE', 'DRONE_FCU,CAMERA', 'SSCA适航证',
   '{"template":"RESCUE","searchGridM":50.0,"altLayersM":[30,60,90],"billingUnit":"PER_HOUR"}'::jsonb, false, true),
  ('SURVEY_UAV', '测绘勘察无人机', 'Survey & Mapping UAV', 'ដ្រូនវាស់វែងភូមិសាស្ត្រ', 'SURVEY',
   'SURVEY', 'DRONE_FCU,CAMERA', 'SSCA适航证',
   '{"template":"SURVEY","overlapPct":80,"posMode":"RTK/PPK","billingUnit":"PER_HECTARE"}'::jsonb, true, true),
  ('PATROL_UAV', '巡逻安保无人机', 'Patrol & Security UAV', 'ដ្រូនល្បាតសន្តិសុខ', 'PATROL',
   'PATROL', 'DRONE_FCU,CAMERA', 'SSCA适航证',
   '{"template":"PATROL","routeWaypoints":20,"billingUnit":"PER_HOUR"}'::jsonb, true, true),
  ('MONITOR_UAV', '遥感监测无人机', 'Remote Sensing UAV', 'ដ្រូនត្រួតពិនិត្យពីចម្ងាយ', 'MONITOR',
   'MONITOR', 'DRONE_FCU,CAMERA', 'SSCA适航证',
   '{"template":"MONITOR","spectralBands":"MULTI","billingUnit":"PER_HECTARE"}'::jsonb, true, true),
  ('GENERAL_UAV', '通用无人机', 'General-purpose UAV', 'ដ្រូនទូទៅ', 'GENERAL',
   'GENERAL', 'DRONE_FCU,CAMERA', 'SSCA适航证',
   '{"template":"GENERAL","billingUnit":"PER_HOUR"}'::jsonb, false, true)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 机型场景属性（幂等：按 product_class_id + attr_key 去重） ----------
INSERT INTO claw.drone_scenario_attrs
    (product_class_id, attr_key, attr_type, unit, is_required, label_zh, label_en, label_km, sort_order)
SELECT pc.id, v.attr_key, v.attr_type, v.unit,
       CAST(v.is_required AS BOOLEAN), v.label_zh, v.label_en, v.label_km, CAST(v.sort_order AS INT)
  FROM (VALUES
      ('AGRI_SPRAY_UAV',       'tankVolumeL',      'NUMBER', 'L',   true,  '药箱容积',     'Tank Volume',        'មាឌធុងថ្នាំ',        1),
      ('AGRI_SPRAY_UAV',       'sprayWidthM',      'NUMBER', 'm',   false, '喷幅',         'Spray Width',        'ទទឹងបាញ់',           2),
      ('POWERLINE_INSPECT_UAV','sensorType',       'STRING', NULL,  true,  '传感器类型',   'Sensor Type',        'ប្រភេទសេនស័រ',       1),
      ('POWERLINE_INSPECT_UAV','maxRangeKm',       'NUMBER', 'km',  false, '最大巡检里程', 'Max Inspection Range','ចម្ងាយត្រួតពិនិត្យអតិបរមា', 2),
      ('LOGISTICS_UAV',        'maxPayloadKg',     'NUMBER', 'kg',  true,  '最大载重',     'Max Payload',        'ទម្ងន់ផ្ទុកអតិបរមា', 1),
      ('LOGISTICS_UAV',        'rangeKm',          'NUMBER', 'km',  false, '航程',         'Range',              'ជួរហោះ',             2),
      ('RESCUE_UAV',           'searchAltitudeM',  'NUMBER', 'm',   true,  '搜索高度层',   'Search Altitude',    'កម្ពស់ស្វែងរក',      1),
      ('RESCUE_UAV',           'enduranceMin',     'NUMBER', 'min', false, '续航时长',     'Endurance',          'រយៈពេលហោះ',         2),
      ('SURVEY_UAV',           'overlapPct',       'NUMBER', '%',   true,  '航向重叠率',   'Forward Overlap',    'អត្រាជាន់គ្នា',      1),
      ('SURVEY_UAV',           'posMode',          'STRING', NULL,  true,  '定位模式',     'Positioning Mode',   'របៀបកំណត់ទីតាំង',  2),
      ('PATROL_UAV',           'routeWaypoints',   'NUMBER', NULL,  false, '航点数量',     'Route Waypoints',    'ចំនួនចំណុចផ្លូវ',   1),
      ('MONITOR_UAV',          'spectralBands',    'NUMBER', NULL,  false, '光谱波段数',   'Spectral Bands',     'ចំនួនរលកពន្លឺ',      1),
      ('GENERAL_UAV',          'maxFlightTimeMin', 'NUMBER', 'min', false, '最大续航',     'Max Flight Time',    'រយៈពេលហោះអតិបរមា', 1)
  ) AS v(code, attr_key, attr_type, unit, is_required, label_zh, label_en, label_km, sort_order)
  JOIN claw.drone_product_classes pc ON pc.code = v.code
 WHERE NOT EXISTS (
       SELECT 1 FROM claw.drone_scenario_attrs a
        WHERE a.product_class_id = pc.id AND a.attr_key = v.attr_key
 );

-- ---------- 3) 权限种子（三步法，照 V66/V116） ----------
-- 3.1) 菜单码（menu:drone-ops 由 V66 已建；此处幂等重申，无则补建）
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:drone-ops', '作业与安全管控', 'MENU', 'menu:drone', '/drone-ops', 474)
ON CONFLICT (code) DO NOTHING;

-- 3.2) 按钮级权限码
-- 仅写接口（远控下行）设权限点；航迹/机型为读接口（GET），按项目约定不设权限点，故无 view 码。
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('drone:command:issue', '下发远控指令', 'BUTTON', 'menu:drone-ops', 1)
ON CONFLICT (code) DO NOTHING;

-- 3.3) 角色模板挂载
-- PLATFORM_ADMIN 挂模板以保持模板完整（其真实权限走 V40 的 ["*"] 通配，第 4 步不回写）。
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('PLATFORM_ADMIN', 'menu:drone-ops'),
  ('PLATFORM_ADMIN', 'drone:command:issue'),
  -- 服务站：日常作业远控
  ('STATION',        'drone:command:issue')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 3.4) roles.grants 回写（权限真源）—— 沿用 V66/V116 模式
-- 本迁移仅给 STATION 新增 drone:command:issue，故只回写 STATION；
-- PLATFORM_ADMIN 走 ["*"] 通配不在此列（绝不降级）；CUSTOMER 是对象结构 grants，禁止纳入。
-- roles.grants 为 TEXT，回写须 ::text。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code = 'STATION';

-- ---------- 4) 前期运营限制配置层（可配置，不改码） ----------
INSERT INTO claw.system_config (config_key, config_value, category, description, data_type, editable) VALUES
  ('regulatory_profile', 'KH-EARLY-OPERATION', '无人机合规',
   '合规档位：KH-EARLY-OPERATION(前期运营限制档，默认) → KH-GENERAL(政策宽松后)；仅配置层切换', 'STRING', true),
  ('permit_gate_mode', 'STRICT', '无人机合规',
   '许可闸门档位：STRICT(拒发) / ADVISORY(提示放行) / OFF(关闭)', 'STRING', true),
  ('bvlos_enabled', 'false', '无人机合规',
   '是否允许超视距(BVLOS)作业（默认关闭，政策确认后开启）', 'BOOLEAN', true),
  ('pilot_roc_required', 'false', '无人机合规',
   '是否强制飞手 ROC 执照（默认关闭）', 'BOOLEAN', true),
  ('insurance_mandatory', 'false', '无人机合规',
   '是否强制第三方责任保险（默认关闭）', 'BOOLEAN', true)
ON CONFLICT (config_key) DO NOTHING;
