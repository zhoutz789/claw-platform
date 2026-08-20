package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.ComplianceRequests;
import com.claw.server.common.dto.ComplianceViews;
import com.claw.server.domain.compliance.DtiCheckService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 合规接口（S2）：负责任信贷红线。
 * POST /compliance/dti-check  DTI≤50% 强制校验（PASS/REJECT 留痕）
 */
@RestController
@RequestMapping("/api/v1/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final DtiCheckService dtiCheckService;

    @PostMapping("/dti-check")
    public ApiResult<ComplianceViews.DtiCheckView> dtiCheck(@Valid @RequestBody ComplianceRequests.DtiCheck req) {
        return ApiResult.ok(dtiCheckService.check(req));
    }
}
