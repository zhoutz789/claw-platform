package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.RentalOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RentalOrderRepository extends JpaRepository<RentalOrder, Long> {

    Optional<RentalOrder> findByOrderNo(String orderNo);

    List<RentalOrder> findByRenterUserIdAndDeletedFalse(Long renterUserId);

    List<RentalOrder> findByStationIdAndDeletedFalse(Long stationId);

    List<RentalOrder> findByAssetIdAndDeletedFalse(Long assetId);

    List<RentalOrder> findByStatusAndDeletedFalse(RentalOrderStatus status);
}
