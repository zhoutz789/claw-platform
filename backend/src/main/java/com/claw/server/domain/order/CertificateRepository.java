package com.claw.server.domain.order;

import com.claw.server.common.enums.CertificateType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    Optional<Certificate> findByCertNo(String certNo);

    Optional<Certificate> findByOrderIdAndCertTypeAndDeletedFalse(Long orderId, CertificateType certType);
}
