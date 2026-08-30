package com.claw.server.domain.certificate;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {
    Optional<Certificate> findByDeviceId(Long deviceId);

    Optional<Certificate> findByCertNo(String certNo);
}
