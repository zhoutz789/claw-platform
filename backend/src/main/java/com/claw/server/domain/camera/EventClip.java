package com.claw.server.domain.camera;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 事件高清片段（永久留存，对应 v1 方案"事件留证"）。
 * 由边缘 AI 检测触发（越界/失联/烟火/入侵/车辆异常等）。
 */
@Entity
@Table(name = "event_clip", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventClip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long streamId;
    private Instant startTs;
    private Instant endTs;
    private String type;          // INTRUSION / LOST / SMOKE / COLLISION ...
    @Column(columnDefinition = "text")
    private String objectKey;

    @Builder.Default
    private Boolean permanent = true;
    @Builder.Default
    private Instant createdAt = Instant.now();
}
