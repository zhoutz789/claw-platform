package com.claw.server.domain.camera;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.CameraRequests;
import com.claw.server.domain.asset.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摄像头域服务：列表 / 取流地址 / 历史段 / 时间轴 / 事件留证。
 * 视频流本身由边缘媒体节点承载，本服务只管索引与元数据。
 */
@Service
@RequiredArgsConstructor
public class CameraService {

    private final CameraRepository cameraRepository;
    private final VideoSegmentRepository videoSegmentRepository;
    private final EventClipRepository eventClipRepository;
    private final SparseFrameRepository sparseFrameRepository;
    private final AssetRepository assetRepository;

    public List<Camera> listByAsset(Long assetId) {
        return cameraRepository.findByAssetId(assetId);
    }

    /** 按资产编号反查其下摄像头（数据回放页入口）。 */
    public List<Camera> listByAssetNo(String assetNo) {
        Long assetId = assetRepository.findByAssetNo(assetNo)
                .orElseThrow(() -> BizException.notFound("error.asset.not_found", assetNo))
                .getId();
        return cameraRepository.findByAssetId(assetId);
    }

    public Camera get(Long id) {
        return cameraRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.camera.not_found", id));
    }

    /** 返回取流地址（HLS/WebRTC 由边缘媒体节点基于 streamUrl 拼接）。 */
    public Map<String, Object> live(Long id) {
        Camera c = get(id);
        // streamUrl 存「流基址」（不含扩展名）。
        // P0 原型用 SRS 作边缘媒体节点：HLS = base + ".m3u8"（Safari 原生可播）；
        // P1 边缘媒体网关将在此之上统一暴露 /hls 与 /rtc 低延迟端点，届时只改本拼接约定、不动前端。
        String base = c.getStreamUrl() == null ? "" : c.getStreamUrl();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cameraId", id);
        m.put("protocol", c.getProtocol());
        m.put("playUrl", base);
        m.put("hlsUrl", base + ".m3u8");
        m.put("webrtcUrl", base);
        return m;
    }

    public List<VideoSegment> segments(Long id, Instant from, Instant to) {
        if (from == null) from = Instant.now().minus(24, ChronoUnit.HOURS);
        if (to == null) to = Instant.now();
        return videoSegmentRepository.findByStreamIdAndStartTsBetween(id, from, to);
    }

    /** 时间轴：稀疏帧 + 事件标记，供前端长程回溯。 */
    public Map<String, Object> timeline(Long id) {
        Instant from = Instant.now().minus(24, ChronoUnit.HOURS);
        Instant to = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frames", sparseFrameRepository.findByStreamIdAndTsBetween(id, from, to));
        m.put("events", eventClipRepository.findByStreamIdAndStartTsBetween(id, from, to));
        return m;
    }

    /** 边缘 recorder 上报一段视频段元数据。 */
    public VideoSegment recordSegment(Long id, CameraRequests.RecordSegment req) {
        get(id); // 校验摄像头存在
        VideoSegment s = VideoSegment.builder()
                .streamId(id)
                .startTs(req.startTs())
                .endTs(req.endTs())
                .objectKey(req.objectKey())
                .sizeBytes(req.sizeBytes())
                .tier(req.tier() == null ? 1 : req.tier())
                .eventTag(req.eventTag())
                .build();
        return videoSegmentRepository.save(s);
    }
}
