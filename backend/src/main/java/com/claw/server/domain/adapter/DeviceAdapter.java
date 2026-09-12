package com.claw.server.domain.adapter;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 设备-协议适配器绑定（对应 claw.device_adapters，V125 表）。
 *
 * <p>记录某台设备经自动协商后绑定到的协议 profile 及协商元数据（握手信息快照）。
 * 唯一约束 (device_id, profile_id) 保证同一协议对同一设备只绑定一次（幂等）。
 */
@Entity
@Table(name = "device_adapters", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceAdapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    /** 协商元数据（握手信息快照，JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "negotiated_meta_json", columnDefinition = "jsonb")
    private String negotiatedMetaJson;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
