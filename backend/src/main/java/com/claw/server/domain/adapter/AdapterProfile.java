package com.claw.server.domain.adapter;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 协议适配 Profile（对应 claw.adapter_profiles，V125 表）。
 *
 * <p>一行描述一种设备协议的解析/下行配置：field_map_json 把原始报文字段映射到规范遥测视图，
 * downlink_templates_json 提供下行指令模板。新增品牌/协议只需加一行 profile + 一个 adapter 实现，
 * 复用 BMS BmsAdapter 的「多 profile 可插拔」思路。
 */
@Entity
@Table(name = "adapter_profiles", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdapterProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "protocol", nullable = false, unique = true, length = 64)
    private String protocol;

    @Column(name = "parser_ref", length = 255)
    private String parserRef;

    /** 字段映射：规范遥测字段名 → 原始报文 JSON 路径（支持点路径，如 location.lat）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_map_json", columnDefinition = "jsonb")
    private String fieldMapJson;

    /** 下行指令模板：指令类型 → 含 ${param} 占位的模板串。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "downlink_templates_json", columnDefinition = "jsonb")
    private String downlinkTemplatesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private AdapterProfileStatus status = AdapterProfileStatus.ACTIVE;
}
