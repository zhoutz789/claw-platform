-- =====================================================================
-- Claw 平台 V83 增量（容量预定：存量 CUSTOMER 角色补发 capacity:subscribe）
-- 依据：V81 已把 capacity:subscribe 挂进 role_template_permissions 的 CUSTOMER 模板，
--       但**存量** CUSTOMER 账号的权限真源是 claw.roles.grants（V1 建表 / V11 播种），
--       模板新增一行并不会自动回写到已有角色行 —— 于是老客户点「容量预定」时
--       PermissionAspect 在 PermissionService.effectivePermissions 里找不到该权限位，
--       直接 40301，功能对存量账号等于没上线（新注册账号走模板播种才有）。
--
-- 范围：只做一件事 —— 给 claw.roles 里 code = 'CUSTOMER' 的既有角色行，
--       在**保留原有全部权限位**的前提下追加 capacity:subscribe。
--
-- grants 真实结构（已核对，勿凭印象改）：
--   · 列名 grants，类型 TEXT（V1 建为 JSONB，V18 改为 TEXT；Role 实体以 String 映射）；
--   · 内容是**纯字符串数组的 JSON**：'["SWAP_BATTERY","RENT_VEHICLE", ...]'，
--     元素就是权限码本身，没有 {code/permissionCode} 包装，也没有 canRead/canCreate 之类的位；
--   · 解析方 PermissionService.parseGrants 用
--     objectMapper.readValue(grants, new TypeReference<List<String>>(){})，只认数组；
--   · CUSTOMER 在 V11 播的是对象结构 {"permissions":[...]}，V57 已统一归一化为数组，
--     故现状应为数组；本脚本对对象结构仍做防御性兼容（见下）。
--
-- ⚠️ 陷阱 1：表名不带 schema 前缀 —— 本脚本顶部已 SET search_path = claw，
--    与 V25 / V79 / V80 / V81 保持一致；再写 claw.roles 会形成重复限定。
-- ⚠️ 陷阱 2：**严禁整体覆盖 grants**。V54/V62/V67/V68/V77/V81 那条
--    "UPDATE roles SET grants = (SELECT to_jsonb(array_agg(tp.permission_code))::text ...)"
--    只适用于「模板即全量」的角色；CUSTOMER 的 grants 是 V11 播的历史权限，
--    role_template_permissions 里未必有其全量清单，整体回写会**清空老权限**。
--    故本脚本走 JSONB 追加（||），不动任何既有元素。
-- ⚠️ 陷阱 3：grants 是 TEXT，赋值前必须 ::text，否则 PG 报
--    「column "grants" is of type text but expression is of type jsonb」（V54 踩过）。
-- ⚠️ 陷阱 4：不碰 PLATFORM_ADMIN —— 它靠 V40 种下的 '["*"]' 通配符已拥有全部权限位，
--    追加单个权限码无意义；不碰 MANUFACTURER / STATION —— 预定是客户端动作，
--    V81 明确不发给这两类角色。
-- 幂等性：追加前先用 jsonb_exists(doc, 'capacity:subscribe')（即 "?" 运算符的函数形态，
--    避开 JDBC 把裸 ? 当参数占位符的历史坑）判断该权限码是否已存在，存在则 CONTINUE；
--    二次执行恒为 no-op，不会把同一个码塞两遍。
-- =====================================================================

SET search_path = claw;

DO $$
DECLARE
    rec         RECORD;   -- 目标角色行（id / code / grants）
    v_perm      text   := 'capacity:subscribe';
    v_doc       jsonb;    -- grants 解析后的 JSON 文档
    v_arr       jsonb;    -- doc -> 'permissions'（仅对象结构时用）
    v_out       text;     -- 追加后的 grants 文本（NULL = 不改）
BEGIN
    FOR rec IN
        SELECT id, code, grants
          FROM roles
         WHERE code = 'CUSTOMER'
    LOOP
        v_out := NULL;

        -- ---------- 情形 A：空 grants（NULL / '' / '{}' / '[]'）----------
        -- 此时不存在任何可被清掉的存量权限，直接落成单元素数组最安全。
        IF rec.grants IS NULL
           OR btrim(rec.grants) = ''
           OR btrim(rec.grants) IN ('{}', '[]') THEN
            v_out := to_jsonb(ARRAY[v_perm])::text;

        ELSE
            BEGIN
                v_doc := btrim(rec.grants)::jsonb;
            EXCEPTION WHEN OTHERS THEN
                -- 非法 JSON：保持原样，绝不写坏既有配置
                RAISE NOTICE 'V83 跳过角色 %：grants 非合法 JSON，保持原样', rec.code;
                CONTINUE;
            END;

            IF jsonb_typeof(v_doc) = 'array' THEN
                -- ---------- 情形 B：数组结构（现状，PermissionService 可直接解析）----------
                -- jsonb_exists 即 "?" 运算符的函数形态（避开 JDBC 把裸 ? 当占位符解析的历史坑）
                IF jsonb_exists(v_doc, v_perm) THEN
                    -- 已存在：幂等 no-op
                    RAISE NOTICE 'V83 角色 % 已含 %，跳过', rec.code, v_perm;
                    CONTINUE;
                END IF;
                -- jsonb 数组 || jsonb 数组 = 拼接；保留原顺序，新码追加到末尾
                v_out := (v_doc || jsonb_build_array(v_perm))::text;

            ELSIF jsonb_typeof(v_doc) = 'object'
                  AND jsonb_typeof(v_doc -> 'permissions') = 'array' THEN
                -- ---------- 情形 C：历史对象结构 {"permissions":[...]}（V11 形态）----------
                -- PermissionService 解析不了对象结构（会退化成空权限集），
                -- 故这里顺手按 V57 的方式落成数组：内容原样保留 + 追加新码，不丢任何老权限。
                v_arr := v_doc -> 'permissions';
                IF jsonb_exists(v_arr, v_perm) THEN
                    RAISE NOTICE 'V83 角色 % 已含 %，跳过', rec.code, v_perm;
                    CONTINUE;
                END IF;
                v_out := ((v_arr || jsonb_build_array(v_perm)))::text;

            ELSE
                -- 未知形态：不猜、不改
                RAISE NOTICE 'V83 跳过角色 %：grants 形态未知（%），保持原样',
                             rec.code, jsonb_typeof(v_doc);
                CONTINUE;
            END IF;
        END IF;

        UPDATE roles
           SET grants     = v_out,
               updated_at = now()
         WHERE id = rec.id;

        RAISE NOTICE 'V83 角色 % 的 grants 已追加 %', rec.code, v_perm;
    END LOOP;
END $$;
