package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 设备指令与回执（下行指令 + 上行 cmd_ack 关联）。
 *
 * <p>一次平台下发指令在此落一条 PENDING 记录，设备执行后通过 claw/iot/{deviceNo}/up 的
 * cmd_ack 报文回执，状态更新为 OK / FAIL。供指令全生命周期审计与防重放校验。
 */
@Entity
@Table(name = "device_commands", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deviceId;

    @Column(nullable = false, length = 64)
    private String deviceNo;

    /** relay | lock | config | reboot | ota。 */
    @Column(nullable = false, length = 16)
    private String action;

    /** 下行参数（含 safeCond 安全条件），JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String paramsJson;

    /** 指令唯一标识（防重放 + 回执关联）。 */
    @Column(nullable = false, length = 64)
    private String cmdId;

    @Column(length = 64)
    private String nonce;

    /** HMAC-SHA256(报文, secret)。 */
    @Column(length = 128)
    private String sign;

    /** PENDING | OK | FAIL。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "PENDING";

    @Column(length = 8)
    private String result;

    @Column(length = 255)
    private String detail;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant ackedAt;
}
