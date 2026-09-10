package com.claw.server.domain.camera;

import com.claw.server.common.enums.CameraStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 摄像头（资产子实体，挂在资产下）。
 * 一个资产可有多个摄像头（camera_idx 区分），状态复用 WORKING/CLOSED/PENDING/OFFLINE/FAULT。
 * 像素不出站：本表只存注册信息与取流地址，视频流由边缘媒体节点（edge-media）承载。
 */
@Entity
@Table(name = "camera_stream", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Camera {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long assetId;        // 归属资产
    private Integer cameraIdx;   // 同资产第几路
    private String name;         // 摄像头名称/位置
    private String protocol;     // GB28181 / RTSP / WebRTC / SRT
    private String resolution;   // 720p / 1080p / 4k

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CameraStatus status = CameraStatus.OFFLINE;

    @Column(columnDefinition = "text")
    private String streamUrl;    // 边缘媒体节点取流基址（WebRTC/HLS 在此之上拼接）

    private Instant lastHeartbeat;

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
