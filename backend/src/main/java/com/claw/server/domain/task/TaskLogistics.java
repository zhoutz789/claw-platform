package com.claw.server.domain.task;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 物流任务扩展（对应 claw.task_logistics，1:1 挂在 LOGISTICS 任务上）。
 * 主键复用 task_id，无独立自增列。
 */
@Entity
@Table(name = "task_logistics", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskLogistics {

    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "pickup_addr", length = 255)
    private String pickupAddr;

    @Column(name = "dropoff_addr", length = 255)
    private String dropoffAddr;

    @Column(name = "cargo_type", length = 50)
    private String cargoType;

    @Column(name = "weight_kg", precision = 10, scale = 2)
    private BigDecimal weightKg;
}
