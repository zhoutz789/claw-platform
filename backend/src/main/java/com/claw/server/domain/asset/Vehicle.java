package com.claw.server.domain.asset;

import com.claw.server.common.enums.ContractType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 车辆扩展（对应 claw.vehicles）。
 * v0.4 D28：contract_type / lessor_id 支持与持牌租赁公司联合放款（融资租赁/RTO）。
 */
@Entity
@Table(name = "vehicles", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    private String vin;
    private String frameNo;
    private String motorNo;

    @Column(nullable = false)
    private String model;

    @Enumerated(EnumType.STRING)
    private ContractType contractType;

    private Long lessorId;     // 联合放款的持牌租赁公司（partners 表，S2 补外键）

    private String protocolVer;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
