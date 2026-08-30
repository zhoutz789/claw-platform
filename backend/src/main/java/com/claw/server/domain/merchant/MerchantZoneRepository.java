package com.claw.server.domain.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantZoneRepository extends JpaRepository<MerchantZone, Long> {

    List<MerchantZone> findByMerchantId(Long merchantId);

    Optional<MerchantZone> findByMerchantIdAndZoneCode(Long merchantId, String zoneCode);
}
