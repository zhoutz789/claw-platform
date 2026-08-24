-- =====================================================================
-- V27：修复残值评估最终价（中位数）触发器 SQL 缺陷
-- 问题：fn_calc_final_price 在计算三方中位数时，将 AVG(val) 套在
--       "ORDER BY val LIMIT 1 OFFSET 1" 子查询外层，导致
--       "column t.val must appear in the GROUP BY clause or be used in an
--        aggregate function"（SQLState 42803），三方估价完成时写入失败（500）。
-- 修复：中位数 = 三个值排序后取中间值，直接 SELECT val ... ORDER BY val LIMIT 1 OFFSET 1，
--       不再包裹 AVG()。
-- =====================================================================

CREATE OR REPLACE FUNCTION claw.fn_calc_final_price()
RETURNS TRIGGER AS $$
DECLARE
    v_system     NUMERIC(18,4);
    v_station    NUMERIC(18,4);
    v_third      NUMERIC(18,4);
    v_count      INTEGER;
BEGIN
    v_system := NEW.system_estimate;
    v_station := NEW.station_estimate;
    v_third := NEW.third_party_estimate;

    SELECT (CASE WHEN v_system IS NOT NULL THEN 1 ELSE 0 END +
            CASE WHEN v_station IS NOT NULL THEN 1 ELSE 0 END +
            CASE WHEN v_third IS NOT NULL THEN 1 ELSE 0 END)
    INTO v_count;

    -- 三方估价全部完成时自动计算中位数（防操纵）
    IF v_count = 3 THEN
        NEW.final_price := (
            SELECT val FROM (
                VALUES (v_system), (v_station), (v_third)
            ) AS t(val)
            ORDER BY val
            LIMIT 1 OFFSET 1
        );
        NEW.status := 'FINALIZED';
        NEW.evaluated_at := now();
    ELSIF v_count = 2 THEN
        -- 两方估价完成：取非 NULL 值的平均
        NEW.final_price := (
            SELECT AVG(val) FROM (
                VALUES (v_system), (v_station), (v_third)
            ) AS t(val) WHERE val IS NOT NULL
        );
    END IF;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 触发器定义与 V13 保持一致（函数已替换，触发器自动指向新函数体）
DROP TRIGGER IF EXISTS trg_valuation_final_price ON claw.residual_valuations;
CREATE TRIGGER trg_valuation_final_price
    BEFORE UPDATE ON claw.residual_valuations
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_final_price();
