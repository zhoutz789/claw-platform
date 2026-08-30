package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.certificate.Certificate;
import com.claw.server.domain.certificate.CertificateService;
import com.claw.server.domain.production.ProductionService;
import com.claw.server.domain.production.ProductionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/** 生产任务 + 合格证管理（增量 B · R3/B1/R4/Q8）。 */
@RestController
@RequestMapping("/api/v1/admin/production")
@RequiredArgsConstructor
public class AdminProductionController {

    private final ProductionService productionService;
    private final CertificateService certificateService;

    @GetMapping("/tasks")
    @RequirePermission("mfg:production:view")
    public ApiResult<List<ProductionTask>> listTasks(@RequestParam(required = false) Long manufacturerId) {
        return ApiResult.ok(productionService.listTasks(manufacturerId));
    }

    @GetMapping("/tasks/{id}")
    @RequirePermission("mfg:production:view")
    public ApiResult<ProductionTask> getTask(@PathVariable Long id) {
        return ApiResult.ok(productionService.getTask(id));
    }

    @PostMapping("/tasks")
    @RequirePermission("mfg:production:create")
    public ApiResult<ProductionTask> createTask(@RequestBody CreateTask req) {
        Long op = AuthContext.currentUserId();
        return ApiResult.ok(productionService.createTask(req.manufacturerId(), req.productId(),
                req.planQuantity(), req.specJson(), op));
    }

    @PostMapping("/tasks/{id}/complete")
    @RequirePermission("mfg:production:create")
    public ApiResult<ProductionTask> completeTask(@PathVariable Long id, @RequestBody(required = false) CompleteTask req) {
        Long op = AuthContext.currentUserId();
        Integer qty = req == null ? null : req.producedQuantity();
        return ApiResult.ok(productionService.completeTask(id, qty, op));
    }

    @GetMapping("/certificates/device/{deviceId}")
    @RequirePermission("mfg:certificate:view")
    public ApiResult<Certificate> getCertificate(@PathVariable Long deviceId) {
        Optional<Certificate> c = certificateService.getByDevice(deviceId);
        return c.<ApiResult<Certificate>>map(ApiResult::ok)
                .orElseGet(() -> ApiResult.error(40401, "certificate.not.found"));
    }

    /** 补打（Q8：仅重新输出已存在的合格证，不生成新 cert_no）。 */
    @GetMapping("/certificates/device/{deviceId}/reprint")
    @RequirePermission("mfg:certificate:print")
    public ApiResult<Certificate> reprint(@PathVariable Long deviceId) {
        return ApiResult.ok(certificateService.reprint(deviceId));
    }

    public record CreateTask(Long manufacturerId, Long productId, Integer planQuantity, String specJson) {
    }

    public record CompleteTask(Integer producedQuantity) {
    }
}
