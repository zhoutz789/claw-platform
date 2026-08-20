package com.claw.server.domain.jurisdiction;

import com.claw.server.common.enums.OperatorType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 运营主体（tenants）：每国一个，区分直营/特许经营/合资。 */
@Entity
@Table(name = "tenants", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_code", length = 3, nullable = false)
    private String countryCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator_type", length = 16, nullable = false)
    private OperatorType operatorType;

    @Column(name = "status", length = 16, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
