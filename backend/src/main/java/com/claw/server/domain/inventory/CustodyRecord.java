package com.claw.server.domain.inventory;

import com.claw.server.common.enums.CustodyStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 寄售占有权记录（对应 V48 claw.custody_records，寄售占有权真源）。
 * 服务站仅持有寄售占有权，货权仍在厂家；源站交接关旧 custody、目标站收货开新 custody。
 */
@Entity
@Table(name = "custody_records", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustodyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    private Long manufacturerId;

    private Long stationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustodyStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Instant since = Instant.now();

    /** 在途责任方（Q2）：SOURCE_STATION / MANUFACTURER。 */
    @Builder.Default
    private String liabilityHolder = "MANUFACTURER";

    private Instant transferredAt;

    private Instant endedAt;

    private String endedReason;

    private Long transferOrderId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
