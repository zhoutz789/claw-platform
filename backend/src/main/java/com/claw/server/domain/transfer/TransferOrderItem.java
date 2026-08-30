package com.claw.server.domain.transfer;

import jakarta.persistence.*;
import lombok.*;

/**
 * 调拨明细（对应 V49 claw.transfer_order_items，一资产一次调拨）。
 * 记录该资产调拨前后的占有权（custody_records）行，供扫码交接时推进占有权状态。
 */
@Entity
@Table(name = "transfer_order_items", schema = "claw",
        uniqueConstraints = @UniqueConstraint(columnNames = {"transfer_order_id", "asset_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_order_id", nullable = false)
    private Long transferOrderId;

    @Column(name = "asset_id")
    private Long assetId;

    /** 调拨主体设备（本域业务代码以 device 为粒度）。 */
    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "from_custody_id")
    private Long fromCustodyId;

    @Column(name = "to_custody_id")
    private Long toCustodyId;
}
