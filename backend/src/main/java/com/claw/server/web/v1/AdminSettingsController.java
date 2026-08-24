package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.AdminDtos.*;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 后台系统配置模块（S5 补齐）：平台级键值参数维护（system_config）。
 *
 * <p>GET    /settings/config           配置项列表
 * POST   /settings/config           新增配置项（configKey 唯一）
 * PUT    /settings/config/{key}     修改配置项
 * DELETE /settings/config/{key}     软删除配置项
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final SystemConfigRepository configRepository;

    @GetMapping("/config")
    public ApiResult<List<SystemConfigView>> listConfig() {
        return ApiResult.ok(configRepository.findAll().stream()
                .filter(c -> !Boolean.TRUE.equals(c.getDeleted()))
                .map(this::toView).toList());
    }

    @PostMapping("/config")
    public ApiResult<SystemConfigView> createConfig(@RequestBody SystemConfigReq req) {
        if (req.configKey() == null || req.configKey().isBlank()) {
            throw new BizException(40001, "config.key.required");
        }
        if (configRepository.findByConfigKeyAndDeletedFalse(req.configKey()).isPresent()) {
            throw new BizException(40901, "config.key.exists");
        }
        SystemConfig c = SystemConfig.builder()
                .configKey(req.configKey())
                .configValue(req.configValue())
                .category(req.category())
                .description(req.description())
                .dataType(req.dataType() == null ? "STRING" : req.dataType())
                .editable(req.editable() == null || req.editable())
                .tenantId(1L)
                .deleted(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return ApiResult.ok(toView(configRepository.save(c)));
    }

    @PutMapping("/config/{key}")
    public ApiResult<SystemConfigView> updateConfig(@PathVariable String key,
                                                    @RequestBody SystemConfigReq req) {
        SystemConfig c = configRepository.findByConfigKeyAndDeletedFalse(key)
                .orElseThrow(() -> new BizException(40401, "config.not.found"));
        if (req.configValue() != null) c.setConfigValue(req.configValue());
        if (req.category() != null) c.setCategory(req.category());
        if (req.description() != null) c.setDescription(req.description());
        if (req.dataType() != null) c.setDataType(req.dataType());
        if (req.editable() != null) c.setEditable(req.editable());
        c.setUpdatedAt(Instant.now());
        return ApiResult.ok(toView(configRepository.save(c)));
    }

    @DeleteMapping("/config/{key}")
    public ApiResult<Void> deleteConfig(@PathVariable String key) {
        SystemConfig c = configRepository.findByConfigKeyAndDeletedFalse(key)
                .orElseThrow(() -> new BizException(40401, "config.not.found"));
        c.setDeleted(true);
        c.setUpdatedAt(Instant.now());
        configRepository.save(c);
        return ApiResult.ok();
    }

    private SystemConfigView toView(SystemConfig c) {
        return new SystemConfigView(c.getId(), c.getConfigKey(), c.getConfigValue(), c.getCategory(),
                c.getDescription(), c.getDataType(), c.getEditable());
    }
}
