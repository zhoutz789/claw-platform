package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.InteractionDirection;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 语音交互（提醒路人 / 接收指令），IN/OUT 双向。
 * 对应 claw.voice_interactions（V121）。
 */
@Entity
@Table(name = "voice_interactions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoiceInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InteractionDirection direction;

    @Column(nullable = false)
    private String text;

    @Builder.Default
    @Column(nullable = false)
    private String lang = "km";

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
