package com.claw.server.domain.custody;

import com.claw.server.common.enums.TransferType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "custody_transfers", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustodyTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private String assetType;

    private Long fromUserId;

    @Column(nullable = false)
    private Long toUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferType transferType;

    private Long stationId;
    private Long swapOrderId;

    private Long prevTransferId;

    @Column(nullable = false)
    private String chainHash;

    private BigDecimal assetSoh;
    private BigDecimal assetSoc;
    private Integer assetCycleCount;

    @Column(nullable = false)
    @Builder.Default
    private Instant transferredAt = Instant.now();

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
