-- =====================================================================
-- Claw 平台 V57 增量（roles.grants 结构归一化：对象 -> 数组）
--
-- 背景（P1 缺陷 · 权限通电的真实缺口）：
--   V11 给 6 个固定角色（CUSTOMER / OPERATOR / ASSET_OWNER / FINANCE_ADMIN /
--   RISK_OFFICER / SUPER_ADMIN）seed 的 grants 是**对象结构**：
--       '{"permissions":["SWAP_BATTERY","RENT_VEHICLE", ...]}'
--   但 PermissionService.parseGrants 只认 **JSON 数组**：
--       objectMapper.readValue(grantsJson, new TypeReference<List<String>>() {})
--   对象结构解析抛异常 -> catch -> 返回 Set.of()，即这些角色的权限**实际为空集**。
--   （V40 的 UPDATE ... SET grants = '["*"]' 只覆盖 grants 为空的角色，CUSTOMER 的
--    grants 非空故未被覆盖；auto_grant=FALSE 的 5 个角色同理，缺陷一直在。）
--
-- 修复：把 roles.grants 里 {"permissions":[...]} 的对象结构**原样**转换为 ["..."] 数组结构，
--       权限内容（取值 + 顺序）保持一致，**不清空任何角色的既有权限**。
--
-- 覆盖范围：不做角色白名单，凡是对象结构一律转换（已核对其他角色：
--   V47 建的 MANUFACTURER / STATION 是 '{}'，V54 又按模板回写成数组，均已是合法形态；
--   V40 处理的平台角色已是 '["*"]' 数组。本脚本对它们会自动跳过，不产生副作用。）
--
-- 注意：roles.grants 在 V18 已从 JSONB 改为 TEXT（Role 实体以 String 映射），
--       故转换结果必须 ::text 后再赋值，否则 PG 报
--       「column "grants" is of type text but expression is of type jsonb」。
--
-- 幂等性：
--   · 转换后 grants 形如 '["A","B"]'（以 '[' 开头），不再命中候选条件 LIKE '{%'，
--     重复执行恒为 no-op，不会把数组再包一层；
--   · 只取 JSON 数组中的字符串标量元素（Set<String> 语义），非字符串元素忽略，
--     避免把对象/数字塞进 List<String> 再次触发解析失败；
--   · 空数组 / 空对象 / 非 JSON 文本一律跳过，不做任何改动（绝不写空串或 '[]' 覆盖既有值）；
--   · 单行转换失败（非法 JSON）由 EXCEPTION 捕获并 CONTINUE，不中断整个迁移；
--   · 不使用 UPDATE ... WHERE 全表扫描写法，逐行 UPDATE by id，重复执行结果一致。
-- =====================================================================

SET search_path = claw;

DO $$
DECLARE
    rec   RECORD;   -- 候选角色行（id / code / grants）
    v_doc jsonb;    -- grants 解析后的 JSON 文档
    v_arr jsonb;    -- doc -> 'permissions'
    v_out text;     -- 归一化后的数组文本
BEGIN
    FOR rec IN
        SELECT id, code, grants
          FROM claw.roles
         WHERE grants IS NOT NULL
           AND btrim(grants) <> ''
           -- 只挑「看起来是 JSON 对象」的行；数组形态（'[...]'）天然被排除 => 幂等
           AND btrim(grants) LIKE '{%'
    LOOP
        v_out := NULL;

        BEGIN
            v_doc := btrim(rec.grants)::jsonb;
        EXCEPTION WHEN OTHERS THEN
            -- 非法 JSON：保持原样，绝不写坏既有配置
            RAISE NOTICE 'V57 跳过角色 %：grants 非合法 JSON，保持原样', rec.code;
            CONTINUE;
        END;

        IF jsonb_typeof(v_doc) <> 'object' THEN
            CONTINUE;
        END IF;

        v_arr := v_doc -> 'permissions';
        IF v_arr IS NULL
           OR jsonb_typeof(v_arr) <> 'array'
           OR jsonb_array_length(v_arr) = 0 THEN
            -- 没有 permissions 键 / 不是数组 / 空数组：跳过（空数组转出来还是空，写不写都无权，
            -- 但不写可以避免把 {"permissions":[]} 这种历史形态抹掉）
            CONTINUE;
        END IF;

        -- 保留原始顺序，仅保留字符串标量；to_jsonb(... )::text 得到 '["A","B"]'
        SELECT to_jsonb(array_agg(elem ORDER BY ord))::text
          INTO v_out
          FROM jsonb_array_elements(v_arr) WITH ORDINALITY AS t(elem, ord)
         WHERE jsonb_typeof(elem) = 'string';

        IF v_out IS NULL OR v_out = '[]' THEN
            CONTINUE;
        END IF;

        UPDATE claw.roles
           SET grants     = v_out,
               updated_at = now()
         WHERE id = rec.id;

        RAISE NOTICE 'V57 角色 % 的 grants 已归一化：% 项权限（内容不变）',
                     rec.code, jsonb_array_length(v_arr);
    END LOOP;
END $$;
