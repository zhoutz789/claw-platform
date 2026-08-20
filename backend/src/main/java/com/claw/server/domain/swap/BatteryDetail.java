package com.claw.server.domain.swap;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 电池扩展表只读映射（对应 claw.batteries）。
 * 换电域读取押金（动态残值）与协议版本，不写。
 */
@Entity
@Table(name = "batteries", schema = "claw")
@Getter
@NoArgsConstructor
public class BatteryDetail {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    private String model;

    private BigDecimal capacityKwh;

    private String protocolVer;

    private BigDecimal soh;

    private Integer cycleCount;

    /** 押金 = 动态残值（FIFO 前提）。 */
    private BigDecimal depositValue;
}
