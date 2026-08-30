package com.claw.server.domain.certificate;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 设备合格证（一对一关联 device，生成即写库不可补，Q8）。对应 claw.device_certificates（V56）。
 *
 * <p><b>为什么不是 claw.certificates（V34）</b>：V34 的 certificates 表是<b>订单合格证</b>
 * （cert_type NOT NULL / issued_by VARCHAR / data_json / order_id），与设备合格证所需列
 * （device_id BIGINT NOT NULL UNIQUE / issued_by BIGINT / spec_json）不兼容，且该表正被
 * {@code domain/order/Certificate} 使用。V56 因此为设备合格证独立建表 device_certificates，
 * 两张表各司其职，互不干扰。
 *
 * <p><b>为什么显式指定 JPA 实体名</b>：{@code domain/order} 下另有一个同名
 * {@code Certificate}（订单合格证）。JPA 默认以类简名作为实体名，两个类会撞名
 * {@code Certificate}，Hibernate 启动即报「entity names must be distinct」。
 * 故这里显式命名为 {@code DeviceCertificate}（项目内无 JPQL 按实体名引用该类型，
 * 仓储方法全部为派生查询，改名无副作用）。
 */
@Entity(name = "DeviceCertificate")
@Table(name = "device_certificates", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true)
    private Long deviceId;

    @Column(name = "cert_no", nullable = false, unique = true, length = 64)
    private String certNo;

    /** 出证厂家（冗余，便于按厂家检索合格证）。 */
    @Column(name = "manufacturer_id")
    private Long manufacturerId;

    /** 实例化来源产品（冗余，便于按型号检索合格证）。 */
    @Column(name = "product_id")
    private Long productId;

    /**
     * 出证时的规格快照。
     *
     * <p>列类型为 JSONB，必须以 {@link SqlTypes#JSON} 绑定：否则 Hibernate 会按 varchar 绑定，
     * PostgreSQL 直接报「column spec_json is of type jsonb but expression is of type character varying」
     * （与 V18 记录的 grants / share_json 同类问题）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_json", columnDefinition = "jsonb")
    private String specJson;

    /** 出证人（登录用户 id）。 */
    @Column(name = "issued_by")
    private Long issuedBy;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
