package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.ComplianceRequests;
import com.claw.server.common.dto.ComplianceViews;
import com.claw.server.domain.compliance.ComplianceTextService;
import com.claw.server.domain.compliance.DtiCheckService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 合规接口（S2）：负责任信贷红线 + 低成本文案护栏。
 * POST /compliance/dti-check   DTI≤50% 强制校验（PASS/REJECT 留痕）
 * POST /compliance/text-check  文案护栏：营销/承诺类文本禁止表述扫描（PASS/REJECT）
 */
@RestController
@RequestMapping("/api/v1/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final DtiCheckService dtiCheckService;
    private final ComplianceTextService complianceTextService;

    @PostMapping("/dti-check")
    public ApiResult<ComplianceViews.DtiCheckView> dtiCheck(@Valid @RequestBody ComplianceRequests.DtiCheck req) {
        return ApiResult.ok(dtiCheckService.check(req));
    }

    @PostMapping("/text-check")
    public ApiResult<ComplianceViews.TextCheckView> textCheck(@Valid @RequestBody ComplianceRequests.TextCheck req) {
        ComplianceTextService.TextCheckResult r = complianceTextService.scan(req.text());
        List<ComplianceViews.TextHitView> hits = r.hits().stream()
                .map(h -> new ComplianceViews.TextHitView(h.pattern(), h.severity(), h.category(), h.excerpt()))
                .toList();
        return ApiResult.ok(new ComplianceViews.TextCheckView(r.allowed(), r.allowed() ? "PASS" : "REJECT", hits));
    }
}
