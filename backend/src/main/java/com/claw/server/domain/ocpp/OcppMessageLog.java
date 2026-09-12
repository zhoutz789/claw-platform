package com.claw.server.domain.ocpp;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * OCPP 报文日志（对应 claw.ocpp_message_log，V118 表）。
 *
 * <p>全量报文落库（排障 + 合规审计）。生产建议异步写 + 设保留期（见
 * {@code OcppMessageLogRepository} 注释），避免无限膨胀。
 */
@Entity
@Table(name = "ocpp_message_log", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OcppMessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false, length = 64)
    private String stationId;

    /** IN / OUT。 */
    @Column(nullable = false, length = 8)
    private String direction;

    /** CALL / CALLRESULT / CALLERROR。 */
    @Column(nullable = false, length = 16)
    private String msgType;

    @Column(name = "msg_id", length = 36)
    private String msgId;

    @Column(name = "payload_json", columnDefinition = "text")
    private String payloadJson;

    @Column(nullable = false)
    @Builder.Default
    private Instant at = Instant.now();
}
