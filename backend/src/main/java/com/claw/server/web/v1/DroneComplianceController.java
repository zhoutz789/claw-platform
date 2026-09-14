package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.compliance.DroneAirspacePermit;
import com.claw.server.domain.compliance.DroneComplianceConfigService;
import com.claw.server.domain.compliance.NfzLayer;
import com.claw.server.domain.compliance.NfzService;
import com.claw.server.domain.compliance.PermitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 无人机合规与运营限制（切片 3）：许可签发/吊销、禁飞图层维护、合规档位配置。
 *
 * <p>端点（前缀 /api/v1/admin/drone）：
 * <ul>
 *   <li>GET  /compliance/config              合规档位现值（读）</li>
 *   <li>PUT  /compliance/config              改档位（写，配置层放开不改码）</li>
 *   <li>GET  /permits?assetId=               许可列表（读）</li>
 *   <li>POST /permits                        签发许可（写）</li>
 *   <li>POST /permits/{id}/revoke            吊销许可（写）</li>
 *   <li>GET  /nfz-layers                     禁飞图层列表（读）</li>
 *   <li>PUT  /nfz-layers/{id}/enabled        图层启停（写；BAKED-IN 恒定拒绝关闭）</li>
 * </ul>
 *
 * <p><b>权限约定</b>：只写接口（POST/PUT）带 {@link RequirePermission}；GET 读接口一律不带
 * （项目约定）。远控指令的闸门判定在 {@link DroneOpsController#command} 下发前执行
 * （{@code PermitGate}），本控制器只管「规则与凭证的维护」。
 */
@RestController
@RequestMapping("/api/v1/admin/drone")
@RequiredArgsConstructor
public class DroneComplianceController {

    private final PermitService permitService;
    private final NfzService nfzService;
    private final DroneComplianceConfigService configService;

    // ------------------------------------------------------------- 合规档位配置

    /** 合规档位现值（regulatory_profile / permit_gate_mode / 能力开关）。读接口按约定不加权限注解。 */
    @GetMapping("/compliance/config")
    public ApiResult<Map<String, Object>> config() {
        return ApiResult.ok(configService.view());
    }

    /**
     * 更新合规档位（配置层放开，不改码）。body: {"permitGateMode":"OFF","regulatoryProfile":"KH-GENERAL"}，
     * 两者均可缺省（缺省 = 不改该项）。
     */
    @RequirePermission("drone:compliance:config")
    @PutMapping("/compliance/config")
    public ApiResult<Map<String, Object>> updateConfig(@RequestBody UpdateConfigReq req) {
        return ApiResult.ok(configService.update(req.permitGateMode(), req.regulatoryProfile()));
    }

    // ------------------------------------------------------------------- 许可

    /** 许可列表（可按资产过滤）。读接口按约定不加权限注解。 */
    @GetMapping("/permits")
    public ApiResult<List<DroneAirspacePermit>> permits(@RequestParam(required = false) Long assetId) {
        return ApiResult.ok(permitService.list(assetId));
    }

    /**
     * 签发空域许可（落库即 ACTIVE）。
     * body: {"assetId":44,"permitNo":"SSCA-UAV-2026-0001","issuer":"SSCA",
     *        "scopeProvince":"SIEM_REAP","validFrom":"...","validTo":"...","docRef":"..."}
     */
    @RequirePermission("drone:permit:manage")
    @PostMapping("/permits")
    public ApiResult<DroneAirspacePermit> issuePermit(@RequestBody IssuePermitReq req) {
        return ApiResult.ok(permitService.issue(req.assetId(), req.permitNo(), req.issuer(),
                req.scopeProvince(), req.validFrom(), req.validTo(), req.docRef()));
    }

    /** 吊销许可（ACTIVE → REVOKED，保留可审计历史）。 */
    @RequirePermission("drone:permit:manage")
    @PostMapping("/permits/{id}/revoke")
    public ApiResult<DroneAirspacePermit> revokePermit(@PathVariable Long id) {
        return ApiResult.ok(permitService.revoke(id));
    }

    // ---------------------------------------------------------------- 禁飞图层

    /** 禁飞图层列表（含固有安全约束与运营限制层）。读接口按约定不加权限注解。 */
    @GetMapping("/nfz-layers")
    public ApiResult<List<NfzLayer>> nfzLayers() {
        return ApiResult.ok(nfzService.list());
    }

    /**
     * 启停禁飞图层。body: {"enabled":false}。
     * {@code source=BAKED-IN} 的固有安全图层不可关闭（409），运营限制层可随政策放开收起。
     */
    @RequirePermission("drone:nfz:manage")
    @PutMapping("/nfz-layers/{id}/enabled")
    public ApiResult<NfzLayer> setNfzEnabled(@PathVariable Long id, @RequestBody NfzEnabledReq req) {
        boolean enabled = req.enabled() != null && req.enabled();
        return ApiResult.ok(nfzService.setEnabled(id, enabled));
    }

    // ------------------------------------------------------------------ 请求体

    /** 合规档位更新请求体。 */
    public record UpdateConfigReq(String permitGateMode, String regulatoryProfile) {
    }

    /** 许可签发请求体。 */
    public record IssuePermitReq(Long assetId, String permitNo, String issuer, String scopeProvince,
                                 Instant validFrom, Instant validTo, String docRef) {
    }

    /** 图层启停请求体。 */
    public record NfzEnabledReq(Boolean enabled) {
    }
}
