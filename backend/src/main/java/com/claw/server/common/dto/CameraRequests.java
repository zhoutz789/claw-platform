package com.claw.server.common.dto;

import java.time.Instant;

/**
 * 摄像头域请求体。
 */
public class CameraRequests {

    /**
     * 边缘 recorder 上报的一段视频段元数据（实际视频已存分级对象存储）。
     */
    public record RecordSegment(
            Instant startTs,
            Instant endTs,
            String objectKey,
            Long sizeBytes,
            Integer tier,
            String eventTag
    ) {}
}
