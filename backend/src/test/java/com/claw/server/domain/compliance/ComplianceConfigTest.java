package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DroneComplianceConfigService} 单元测试（切片 3）：
 * 档位读取的保守缺省、取值校验、配置层放开（不改码）。
 */
@ExtendWith(MockitoExtension.class)
class ComplianceConfigTest {

    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private DroneComplianceConfigService service;

    private static SystemConfig cfg(String key, String value) {
        return SystemConfig.builder().configKey(key).configValue(value).dataType("STRING")
                .editable(true).build();
    }

    // ------------------------------------------------------------------ 缺省回落

    @Test
    void permitGateMode_defaultsToStrict_whenUnconfigured() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(
                DroneComplianceConfigService.KEY_PERMIT_GATE_MODE)).thenReturn(Optional.empty());

        assertEquals("STRICT", service.permitGateMode(), "缺省回落保守侧：不通过即拒");
    }

    @Test
    void regulatoryProfile_defaultsToEarlyOperation_whenUnconfigured() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(
                DroneComplianceConfigService.KEY_REGULATORY_PROFILE)).thenReturn(Optional.empty());

        assertEquals("KH-EARLY-OPERATION", service.regulatoryProfile());
        assertTrue(service.permitRequired());
    }

    @Test
    void permitRequired_falseForGeneralProfile() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(
                DroneComplianceConfigService.KEY_REGULATORY_PROFILE))
                .thenReturn(Optional.of(cfg(DroneComplianceConfigService.KEY_REGULATORY_PROFILE, "KH-GENERAL")));

        assertFalse(service.permitRequired(), "KH-GENERAL 档不强制许可");
    }

    // ------------------------------------------------------------------ 更新

    @Test
    void update_normalizesAndWritesModeAndProfile() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(anyString()))
                .thenReturn(Optional.empty());
        when(systemConfigRepository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update("off", "kh-general");

        // 视图读取自仓储 mock（无状态），故断言落在「写库的归一化取值」上而非视图回显。
        org.mockito.ArgumentCaptor<SystemConfig> captor =
                org.mockito.ArgumentCaptor.forClass(SystemConfig.class);
        verify(systemConfigRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<SystemConfig> saved = captor.getAllValues();
        assertTrue(saved.stream().anyMatch(c ->
                        DroneComplianceConfigService.KEY_PERMIT_GATE_MODE.equals(c.getConfigKey())
                                && "OFF".equals(c.getConfigValue())),
                "permit_gate_mode 须归一化为大写 OFF");
        assertTrue(saved.stream().anyMatch(c ->
                        DroneComplianceConfigService.KEY_REGULATORY_PROFILE.equals(c.getConfigKey())
                                && "KH-GENERAL".equals(c.getConfigValue())),
                "regulatory_profile 须归一化为大写 KH-GENERAL");
    }

    @Test
    void update_rejectsInvalidMode() {
        BizException ex = assertThrows(BizException.class, () -> service.update("YOLO", null));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("error.drone.compliance.config.invalid", ex.getMessageCode());
        verify(systemConfigRepository, never()).save(any());
    }

    @Test
    void update_rejectsInvalidProfile() {
        BizException ex = assertThrows(BizException.class, () -> service.update(null, "KH-UNLIMITED"));

        assertEquals("error.drone.compliance.config.invalid", ex.getMessageCode());
        verify(systemConfigRepository, never()).save(any());
    }

    @Test
    void update_blankMeansNoChangeButStillReturnsView() {
        Map<String, Object> view = service.update("  ", "");

        assertEquals(5, view.size(), "不改任何键，但视图仍返回全部 5 个配置键");
        verify(systemConfigRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ 视图

    @Test
    void view_returnsAllFiveConfigKeys() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(anyString()))
                .thenReturn(Optional.of(cfg("x", "1")));

        Map<String, Object> view = service.view();

        assertEquals(5, view.size());
        assertTrue(view.containsKey(DroneComplianceConfigService.KEY_REGULATORY_PROFILE));
        assertTrue(view.containsKey(DroneComplianceConfigService.KEY_PERMIT_GATE_MODE));
        assertTrue(view.containsKey(DroneComplianceConfigService.KEY_BVLOS_ENABLED));
        assertTrue(view.containsKey(DroneComplianceConfigService.KEY_PILOT_ROC_REQUIRED));
        assertTrue(view.containsKey(DroneComplianceConfigService.KEY_INSURANCE_MANDATORY));
    }
}
