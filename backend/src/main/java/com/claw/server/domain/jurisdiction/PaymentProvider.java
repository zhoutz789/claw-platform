package com.claw.server.domain.jurisdiction;

import com.claw.server.common.enums.ProviderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 支付提供方注册表（payment_providers）：通道策略按国家挂载。 */
@Entity
@Table(name = "payment_providers", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
public class PaymentProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_code", length = 3, nullable = false)
    private String countryCode;

    @Column(name = "provider_code", length = 32, nullable = false)
    private String providerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", length = 16, nullable = false)
    private ProviderType providerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private String configJson;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
