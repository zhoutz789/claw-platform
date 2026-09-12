package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.DriveMode;
import com.claw.server.common.enums.SafetyState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 无人车自主模块服务（AU2 薄层）：随资产建档挂载模块、切换驾驶模式。
 * 复用 VEHICLE 资产类，本服务只管 autonomy_modules 行。
 */
@Service
@RequiredArgsConstructor
public class AutonomyModuleService {

    private final AutonomyModuleRepository moduleRepository;

    @Transactional
    public AutonomyModule createModule(Long assetId, String algoVersion, DriveMode driveMode) {
        AutonomyModule module = AutonomyModule.builder()
                .assetId(assetId)
                .algoVersion(algoVersion)
                .driveMode(driveMode == null ? DriveMode.ASSISTED : driveMode)
                .safetyState(SafetyState.NORMAL)
                .build();
        return moduleRepository.save(module);
    }

    @Transactional
    public AutonomyModule setDriveMode(Long assetId, DriveMode driveMode) {
        AutonomyModule module = moduleRepository.findAll().stream()
                .filter(m -> assetId.equals(m.getAssetId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("autonomy.module.not.found:" + assetId));
        module.setDriveMode(driveMode);
        return moduleRepository.save(module);
    }

    public AutonomyModule getByAssetId(Long assetId) {
        return moduleRepository.findAll().stream()
                .filter(m -> assetId.equals(m.getAssetId()))
                .findFirst()
                .orElse(null);
    }
}
