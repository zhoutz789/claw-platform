package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.DriveMode;
import com.claw.server.common.enums.InteractionDirection;
import com.claw.server.common.enums.SafetyState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 无人车自主模块服务（AU2/AU6 薄层）：随资产建档挂载模块、切换驾驶模式、进出遥操作、设安全态。
 * 复用 VEHICLE 资产类，本服务只管 autonomy_modules 行。
 */
@Service
@RequiredArgsConstructor
public class AutonomyModuleService {

    private final AutonomyModuleRepository moduleRepository;
    private final VoiceInteractionRepository voiceInteractionRepository;

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

    // ===== AU2: 模块供应 =====

    /**
     * 若已存在模块则直接返回（幂等），否则新建。
     */
    public AutonomyModule createModuleIfAbsent(Long assetId, String algoVersion, DriveMode driveMode) {
        AutonomyModule existing = getByAssetId(assetId);
        if (existing != null) {
            return existing;
        }
        return createModule(assetId, algoVersion, driveMode);
    }

    /**
     * AU2 简化重载：仅传 assetId，使用默认算法版本 v1 与 ASSISTED 默认模式。
     */
    public AutonomyModule createModuleIfAbsent(Long assetId) {
        return createModuleIfAbsent(assetId, "v1", DriveMode.ASSISTED);
    }

    /**
     * 新自动驾驶资产建档时挂载默认模块（算法版本 v1，ASSISTED 默认模式）。
     */
    @Transactional
    public AutonomyModule provisionForNewAutonomousAsset(Long assetId) {
        return createModuleIfAbsent(assetId, "v1", DriveMode.ASSISTED);
    }

    // ===== AU6: 遥操作 / 安全态 =====

    /**
     * 设置模块安全态（异常时锁机）。
     */
    @Transactional
    public AutonomyModule setSafetyState(Long assetId, SafetyState state) {
        AutonomyModule module = moduleRepository.findAll().stream()
                .filter(m -> assetId.equals(m.getAssetId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("autonomy.module.not.found:" + assetId));
        module.setSafetyState(state);
        return moduleRepository.save(module);
    }

    /**
     * 进入遥操作：切到 TELEOP，并记录一条 OUT 语音提示。
     */
    @Transactional
    public AutonomyModule enterTeleop(Long assetId) {
        AutonomyModule module = setDriveMode(assetId, DriveMode.TELEOP);
        voiceInteractionRepository.save(VoiceInteraction.builder()
                .assetId(assetId)
                .direction(InteractionDirection.OUT)
                .text("teleop.enter")
                .lang("zh")
                .build());
        return module;
    }

    /**
     * 退出遥操作：切回 ASSISTED。
     */
    @Transactional
    public AutonomyModule exitTeleop(Long assetId) {
        return setDriveMode(assetId, DriveMode.ASSISTED);
    }

    /**
     * AU6 记录一条语音交互（提醒路人 / 接收指令）。
     *
     * @param direction IN / OUT
     * @param text      语音文本
     * @param lang      语言代码（缺省 km）
     */
    @Transactional
    public VoiceInteraction logVoice(Long assetId, InteractionDirection direction, String text, String lang) {
        return voiceInteractionRepository.save(VoiceInteraction.builder()
                .assetId(assetId)
                .direction(direction)
                .text(text)
                .lang(lang == null ? "km" : lang)
                .build());
    }
}
