package com.claw.server.domain.transfer;

import com.claw.server.common.enums.TransferStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 站间调拨单（对应 V49 claw.transfer_orders）。
 * 厂家发起，将寄售设备从源服务站调拨到目标服务站；扫码交接时占有权随 {@code ConsignmentCustody} 转移（Q2）。
 */
@Entity
@Table(name = "transfer_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_no", nullable = false, unique = true)
    private String transferNo;

    @Column(name = "manufacturer_id", nullable = false)
    private Long manufacturerId;

    @Column(name = "from_station_id", nullable = false)
    private Long fromStationId;

    @Column(name = "to_station_id", nullable = false)
    private Long toStationId;

    /** DRAFT / CREATED / IN_TRANSIT / COMPLETED / CANCELLED。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TransferStatus status = TransferStatus.DRAFT;

    @Column(name = "logistics_fee", precision = 12, scale = 2)
    private BigDecimal logisticsFee;

    @Column(name = "handover_at")
    private Instant handoverAt;

    @Column(name = "receive_at")
    private Instant receiveAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
