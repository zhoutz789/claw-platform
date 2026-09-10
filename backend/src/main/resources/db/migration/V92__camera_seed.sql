-- =====================================================================
-- Claw 平台 V92 增量：摄像头子系统示例种子
--
-- 便于 P0 自验证：先插入一路演示相机（挂到资产 id=1，若资产表为空则 seed 不依赖外键）。
-- 真实环境由边缘 media 节点注册时写入，本种子仅用于本地/演示跑通回放链路。
--
-- ⚠️ 幂等：先查后插，二次执行无副作用。
-- ⚠️ 顶部 SET search_path = claw。
-- =====================================================================

SET search_path = claw;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM camera_stream WHERE asset_id = 1 AND camera_idx = 1) THEN
    INSERT INTO camera_stream (asset_id, camera_idx, name, protocol, resolution, status, stream_url)
    VALUES (1, 1, 'Demo Camera #1', 'RTSP', '720p', 'WORKING', 'http://localhost:18080/live/cam1');
  END IF;
END $$;
