package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 服务站库存出入库流水（模块四 · 库存层）。
 *
 * <p>角色定位：库存层的"当前库存"主表复用 {@link StationStock}，本实体是
 * 出入库 / 盘盈盘亏 / 消耗 / 回收的流水与审计来源，同时是结算层的"消耗数据源"（只读引用）。
 *
 * <p>解耦要点（BC-2）：本表仅通过 {@code stationProjectId}（可空）按 ID 归因到项目层，
 * 不存在指向项目/结算表的强一致外键写入。
 */
@Entity
@Table(name = "station_inventory_movements", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationInventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stationId;

    @Column(nullable = false)
    private String skuCode;

    /** + 入站/盘盈；- 消耗/盘亏/回收。 */
    @Column(nullable = false)
    private Integer deltaQty;

    /** INBOUND / ADJUST / CONSUME / RECOVER。 */
    @Column(nullable = false)
    private String reason;

    /** 可空：归因到某站下项目（结算按项目活动拆分）。 */
    private Long stationProjectId;

    /** 可选业务来源类型。 */
    private String refType;

    /** 可选业务来源 ID（仅 ID 引用，无强一致外键写入）。 */
    private Long refId;

    /** 操作人。 */
    private Long operatorId;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
