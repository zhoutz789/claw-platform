package com.claw.server.domain.certificate;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** 合格证（一对一关联 device，生成即写库不可补，Q8）。对应 claw.certificates。 */
@Entity
@Table(name = "certificates", schema = "claw")
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

    @Column(name = "cert_no", nullable = false, unique = true)
    private String certNo;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "issued_by")
    private Long issuedBy;

    @Column(name = "spec_json", columnDefinition = "jsonb")
    private String specJson;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
