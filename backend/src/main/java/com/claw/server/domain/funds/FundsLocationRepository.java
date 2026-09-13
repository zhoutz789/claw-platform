package com.claw.server.domain.funds;

import com.claw.server.common.enums.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 托管点位仓储（claw.funds_location）。
 */
public interface FundsLocationRepository extends JpaRepository<FundsLocation, Long> {

    Optional<FundsLocation> findByLocationCodeAndDeletedFalse(String locationCode);

    boolean existsByLocationCodeAndDeletedFalse(String locationCode);

    List<FundsLocation> findByLocationTypeAndCurrencyAndStatusAndDeletedFalse(
            LocationType locationType, String currency, String status);

    List<FundsLocation> findByStatusAndDeletedFalse(String status);

    List<FundsLocation> findByDeletedFalse();
}
