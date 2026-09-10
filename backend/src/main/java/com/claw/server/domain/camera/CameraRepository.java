package com.claw.server.domain.camera;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CameraRepository extends JpaRepository<Camera, Long> {
    List<Camera> findByAssetId(Long assetId);

    List<Camera> findByDeletedFalse();
}
