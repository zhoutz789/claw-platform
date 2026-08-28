package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 通用电子围栏仓储（对应 claw.geofences）。 */
public interface GeofenceRepository extends JpaRepository<Geofence, Long> {

    List<Geofence> findByOwnerTypeAndOwnerId(String ownerType, Long ownerId);

    List<Geofence> findByOwnerTypeAndOwnerIdAndStatus(String ownerType, Long ownerId, String status);
}
