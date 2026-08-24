-- V20：修正 V19 演示种子用 OVERRIDING SYSTEM VALUE 显式插入 id 后，
-- PostgreSQL IDENTITY 序列未自动推进，导致后台自动生成主键时与种子 id 冲突。
-- 将相关序列重置为当前最大值，保证后续自增不撞键。幂等。
SELECT setval(pg_get_serial_sequence('claw.custody_transfers', 'id'), COALESCE((SELECT MAX(id) FROM claw.custody_transfers), 1));
SELECT setval(pg_get_serial_sequence('claw.custody_transfer_audit', 'id'), COALESCE((SELECT MAX(id) FROM claw.custody_transfer_audit), 1));
SELECT setval(pg_get_serial_sequence('claw.custody_disputes', 'id'), COALESCE((SELECT MAX(id) FROM claw.custody_disputes), 1));
