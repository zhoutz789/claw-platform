package com.claw.server.domain.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantBoothRepository extends JpaRepository<MerchantBooth, Long> {

    List<MerchantBooth> findByZoneId(Long zoneId);

    Optional<MerchantBooth> findByZoneIdAndBoothCode(Long zoneId, String boothCode);
}
