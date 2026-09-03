package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 项目按 ID 占用库存（模块四 · 项目层）。
 *
 * <p><b>逻辑占用，绝不回写 {@code station_stock.stock_qty}</b>（BC-3）：
 * 可用量 = {@code stock_qty − ΣallocatedQty}，读时计算。
 * 跨层耦合只有 {@code stationStockId} 这一个 ID 引用（BC-2）。
 */
@Entity
@Table(name = "station_project_inventory_alloc", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationProjectInventoryAlloc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stationProjectId;

    /** 仅 ID 引用库存层（station_stock.id），无外键级联写。 */
    @Column(nullable = false)
    private Long stationStockId;

    @Column(nullable = false)
    private String skuCode;

    @Column(nullable = false)
    @Builder.Default
    private Integer allocatedQty = 0;

    private String note;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
