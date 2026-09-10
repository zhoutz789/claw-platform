package com.claw.server.domain.task;

import jakarta.persistence.*;
import lombok.*;

/**
 * 广告任务扩展（对应 claw.task_ad，1:1 挂在 AD 任务上）。
 * 主键复用 task_id，无独立自增列。
 */
@Entity
@Table(name = "task_ad", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskAd {

    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "advertiser", length = 200)
    private String advertiser;

    @Column(name = "media_url", length = 512)
    private String mediaUrl;

    @Column(name = "display_duration", length = 20)
    private String displayDuration;

    @Column(name = "screen_type", length = 20)
    private String screenType;
}
