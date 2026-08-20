package com.claw.server.domain.swap;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 电价快照只读映射（对应 claw.elec_price_snapshots，V1 表）。
 * 换电/充电时刻锁定快照结算（EDC 牌价季度更新，锁版价 0.12/0.18）。
 */
@Entity
@Table(name = "elec_price_snapshots", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
public class ElecPriceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 光伏供电 $/kWh（基准）。 */
    private BigDecimal pvPrice;

    /** 市电 $/kWh（EDC 牌价原价转付）。 */
    private BigDecimal gridPrice;

    private LocalDate effectiveDate;
}
