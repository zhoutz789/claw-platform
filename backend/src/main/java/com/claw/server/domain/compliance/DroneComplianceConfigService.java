package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 无人机合规档位配置服务（V139 种下的运营限制配置层的读写面）。
 *
 * <p>设计意图（§3.9）：合规是「档位 + 开关」的<b>配置层</b>能力，政策变化只改配置、不改码：
 * <ul>
 *   <li>{@code regulatory_profile}：KH-EARLY-OPERATION（前期运营限制档，默认）→ KH-GENERAL（政策宽松后）；</li>
 *   <li>{@code permit_gate_mode}：STRICT（不通过即拒发）/ ADVISORY（提示放行）/ OFF（关闭闸门）；</li>
 *   <li>能力开关：bvlos_enabled / pilot_roc_required / insurance_mandatory。</li>
 * </ul>
 *
 * <p>⚠️ 合规档位<b>不改变</b>零容忍区（BAKED-IN 图层）的恒定拒绝语义 —— 那是航空安全底线，
 * 配置层只能放开「许可要求」这类运营限制，不能放开安全底线。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DroneComplianceConfigService {

    /** 合规档位：KH-EARLY-OPERATION（要求有效许可）/ KH-GENERAL（不强制许可，仅 NFZ）。 */
    public static final String KEY_REGULATORY_PROFILE = "regulatory_profile";
    /** 闸门档位：STRICT / ADVISORY / OFF。 */
    public static final String KEY_PERMIT_GATE_MODE = "permit_gate_mode";
    /** 是否允许超视距（BVLOS）作业。 */
    public static final String KEY_BVLOS_ENABLED = "bvlos_enabled";
    /** 是否强制飞手 ROC 执照。 */
    public static final String KEY_PILOT_ROC_REQUIRED = "pilot_roc_required";
    /** 是否强制第三方责任保险。 */
    public static final String KEY_INSURANCE_MANDATORY = "insurance_mandatory";

    public static final String PROFILE_EARLY_OPERATION = "KH-EARLY-OPERATION";
    public static final String PROFILE_GENERAL = "KH-GENERAL";
    public static final String MODE_STRICT = "STRICT";
    public static final String MODE_ADVISORY = "ADVISORY";
    public static final String MODE_OFF = "OFF";

    private static final String CATEGORY = "无人机合规";
    private static final Set<String> VALID_PROFILES = Set.of(PROFILE_EARLY_OPERATION, PROFILE_GENERAL);
    private static final Set<String> VALID_MODES = Set.of(MODE_STRICT, MODE_ADVISORY, MODE_OFF);
    private static final List<String> VIEW_KEYS = List.of(
            KEY_REGULATORY_PROFILE, KEY_PERMIT_GATE_MODE,
            KEY_BVLOS_ENABLED, KEY_PILOT_ROC_REQUIRED, KEY_INSURANCE_MANDATORY);

    private final SystemConfigRepository systemConfigRepository;

    /** 当前合规档位（缺省回落 KH-EARLY-OPERATION：保守侧，要求许可）。 */
    @Transactional(readOnly = true)
    public String regulatoryProfile() {
        return normalized(KEY_REGULATORY_PROFILE, PROFILE_EARLY_OPERATION);
    }

    /** 当前闸门档位（缺省回落 STRICT：保守侧，不通过即拒）。 */
    @Transactional(readOnly = true)
    public String permitGateMode() {
        return normalized(KEY_PERMIT_GATE_MODE, MODE_STRICT);
    }

    /** 是否处于「要求有效许可」档位。 */
    @Transactional(readOnly = true)
    public boolean permitRequired() {
        return PROFILE_EARLY_OPERATION.equals(regulatoryProfile());
    }

    /**
     * 合规配置全量视图（管理端展示）。
     *
     * @return key → {value, dataType, editable}（缺省键以保守默认值补齐展示）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : VIEW_KEYS) {
            Map<String, Object> item = new LinkedHashMap<>();
            SystemConfig cfg = systemConfigRepository.findByConfigKeyAndDeletedFalse(key).orElse(null);
            item.put("value", cfg != null ? cfg.getConfigValue() : defaultValueOf(key));
            item.put("dataType", cfg != null ? cfg.getDataType() : "STRING");
            item.put("editable", cfg == null || Boolean.TRUE.equals(cfg.getEditable()));
            out.put(key, item);
        }
        return out;
    }

    /**
     * 更新合规档位（配置层放开，不改码）。
     *
     * @param permitGateMode   STRICT/ADVISORY/OFF（可空 = 不改）
     * @param regulatoryProfile KH-EARLY-OPERATION/KH-GENERAL（可空 = 不改）
     * @return 更新后的配置视图
     * @throws BizException 10001 error.drone.compliance.config.invalid（取值非法）
     */
    @Transactional
    public Map<String, Object> update(String permitGateMode, String regulatoryProfile) {
        if (permitGateMode != null && !permitGateMode.isBlank()) {
            String mode = permitGateMode.trim().toUpperCase();
            if (!VALID_MODES.contains(mode)) {
                throw BizException.invalidParam("error.drone.compliance.config.invalid", permitGateMode);
            }
            upsert(KEY_PERMIT_GATE_MODE, mode, "闸门档位：STRICT(拒发) / ADVISORY(提示放行) / OFF(关闭)");
            log.warn("合规闸门档位变更为 {}（配置层放开，不改码）", mode);
        }
        if (regulatoryProfile != null && !regulatoryProfile.isBlank()) {
            String profile = regulatoryProfile.trim().toUpperCase();
            if (!VALID_PROFILES.contains(profile)) {
                throw BizException.invalidParam("error.drone.compliance.config.invalid", regulatoryProfile);
            }
            upsert(KEY_REGULATORY_PROFILE, profile,
                    "合规档位：KH-EARLY-OPERATION(前期运营限制档) / KH-GENERAL(政策宽松后)");
            log.warn("合规档位变更为 {}（配置层放开，不改码）", profile);
        }
        return view();
    }

    /** 读取配置值（大小写归一、去空白）；缺省/未配置时回落保守默认。 */
    private String normalized(String key, String defaultValue) {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse(key)
                .map(SystemConfig::getConfigValue)
                .map(v -> v == null || v.isBlank() ? defaultValue : v.trim().toUpperCase())
                .orElse(defaultValue);
    }

    /** 配置键缺省值（仅用于展示兜底，与 V139 种子一致）。 */
    private String defaultValueOf(String key) {
        return switch (key) {
            case KEY_REGULATORY_PROFILE -> PROFILE_EARLY_OPERATION;
            case KEY_PERMIT_GATE_MODE -> MODE_STRICT;
            case KEY_BVLOS_ENABLED, KEY_PILOT_ROC_REQUIRED, KEY_INSURANCE_MANDATORY -> "false";
            default -> null;
        };
    }

    /** 幂等 upsert：键存在则改值，不存在则补建（V139 已种，此处兜手工清库场景）。 */
    private void upsert(String key, String value, String description) {
        SystemConfig cfg = systemConfigRepository.findByConfigKeyAndDeletedFalse(key).orElse(null);
        if (cfg == null) {
            cfg = SystemConfig.builder()
                    .configKey(key)
                    .configValue(value)
                    .category(CATEGORY)
                    .description(description)
                    .dataType("STRING")
                    .editable(true)
                    .build();
        } else {
            cfg.setConfigValue(value);
            cfg.setUpdatedAt(Instant.now());
        }
        systemConfigRepository.save(cfg);
    }
}
