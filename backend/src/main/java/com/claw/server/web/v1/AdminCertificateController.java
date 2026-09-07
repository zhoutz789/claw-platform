package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.CertificateDtos.CertificateDto;
import com.claw.server.common.dto.CertificateDtos.CertificateTemplateFieldView;
import com.claw.server.common.dto.CertificateDtos.CreateCertificateTemplateFieldReq;
import com.claw.server.common.dto.CertificateDtos.UpdateCertificateDataReq;
import com.claw.server.common.dto.CertificateDtos.UpdateCertificateTemplateFieldReq;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.certificate.CertificateService;
import com.claw.server.domain.certificate.CertificateTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 合格证管理（③ 可定制模板 + 可编辑识别信息 + A4 打印数据源）。
 *
 * <p>端点（前缀 /api/v1/admin/certificates）：
 * <ul>
 *   <li>GET  /device/{deviceId}                      查设备合格证（mfg:certificate:view）</li>
 *   <li>PUT  /device/{deviceId}                      更新识别信息 data_json（mfg:certificate:edit）</li>
 *   <li>GET/POST/PUT/DELETE /template[/{id}]         合格证模板字段 EAV（mfg:certificate:template）</li>
 * </ul>
 * 补打 reprint 仍留在 AdminProductionController（mfg:certificate:print）。
 */
@RestController
@RequestMapping("/api/v1/admin/certificates")
@RequiredArgsConstructor
public class AdminCertificateController {

    private final CertificateService certificateService;
    private final CertificateTemplateService templateService;

    @GetMapping("/device/{deviceId}")
    @RequirePermission("mfg:certificate:view")
    public ApiResult<CertificateDto> getCertificate(@PathVariable Long deviceId) {
        return ApiResult.ok(certificateService.getDto(deviceId));
    }

    @PutMapping("/device/{deviceId}")
    @RequirePermission("mfg:certificate:edit")
    public ApiResult<CertificateDto> updateCertificateData(@PathVariable Long deviceId,
                                                          @RequestBody UpdateCertificateDataReq req) {
        return ApiResult.ok(certificateService.updateData(deviceId, req.dataJson()));
    }

    @GetMapping("/template")
    @RequirePermission("mfg:certificate:template")
    public ApiResult<List<CertificateTemplateFieldView>> listTemplate() {
        return ApiResult.ok(templateService.list());
    }

    @PostMapping("/template")
    @RequirePermission("mfg:certificate:template")
    public ApiResult<CertificateTemplateFieldView> createTemplateField(
            @RequestBody CreateCertificateTemplateFieldReq req) {
        return ApiResult.ok(templateService.create(req));
    }

    @PutMapping("/template/{id}")
    @RequirePermission("mfg:certificate:template")
    public ApiResult<CertificateTemplateFieldView> updateTemplateField(@PathVariable Long id,
                                                                      @RequestBody UpdateCertificateTemplateFieldReq req) {
        return ApiResult.ok(templateService.update(id, req));
    }

    @DeleteMapping("/template/{id}")
    @RequirePermission("mfg:certificate:template")
    public ApiResult<Void> deleteTemplateField(@PathVariable Long id) {
        templateService.delete(id);
        return ApiResult.ok();
    }
}
