package com.claw.server.domain.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findByCode(String code);

    List<Merchant> findByStatus(String status);

    List<Merchant> findByDeletedFalse();
}
