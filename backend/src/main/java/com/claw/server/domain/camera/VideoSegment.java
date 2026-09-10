package com.claw.server.domain.camera;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 视频段（近期原始窗口内的片段，到期由生命周期策略回收）。
 * 由边缘 recorder 周期写入；object_key 指向分级对象存储中的位置。
 */
@Entity
@Table(name = "video_segment", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long streamId;        // 对应 camera_stream.id
    private Instant startTs;
    private Instant endTs;
    private Integer tier;         // 1热 2温 3冷
    @Column(columnDefinition = "text")
    private String objectKey;
    private Long sizeBytes;
    private String eventTag;      // 关联事件类型（若有）
}
