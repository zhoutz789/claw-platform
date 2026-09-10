package com.claw.server.domain.task;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 出行任务扩展（对应 claw.task_ride，1:1 挂在 HAIL_RIDE / TAXI 任务上）。
 * 主键复用 task_id，无独立自增列。
 */
@Entity
@Table(name = "task_ride", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskRide {

    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "origin_addr", length = 255)
    private String originAddr;

    @Column(name = "dest_addr", length = 255)
    private String destAddr;

    @Column(name = "ride_type", length = 10)
    private String rideType;

    @Column(name = "est_distance_km", precision = 10, scale = 2)
    private BigDecimal estDistanceKm;

    @Column(name = "est_duration_min")
    private Integer estDurationMin;

    @Column(name = "fare_model", length = 20)
    private String fareModel;
}
