package com.claw.server.domain.jurisdiction;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 身份提供方注册表（identity_providers）：每国一份 IdP 适配。 */
@Entity
@Table(name = "identity_providers", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
public class IdentityProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_code", length = 3, nullable = false)
    private String countryCode;

    @Column(name = "provider_code", length = 32, nullable = false)
    private String providerCode;

    @Column(name = "protocol", length = 16, nullable = false)
    private String protocol = "OAUTH2";

    @Column(name = "display_name")
    private String displayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private String configJson;

    @Column(name = "priority", nullable = false)
    private int priority = 1;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
