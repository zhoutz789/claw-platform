package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.PaymentViews;
import com.claw.server.domain.payment.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 日终对账接口（S4）：技术文档 4.1 每日 T+1 双向对账。
 *
 * <p>POST /reconciliations/daily?date=  触发某日对账（缺省昨日 T+1）
 * GET  /reconciliations/latest         最近一次对账结果
 */
@RestController
@RequestMapping("/api/v1/reconciliations")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @PostMapping("/daily")
    public ApiResult<PaymentViews.ReconciliationView> runDaily(
            @RequestParam(required = false) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now().minusDays(1);
        return ApiResult.ok(reconciliationService.runDaily(target));
    }

    @GetMapping("/latest")
    public ApiResult<PaymentViews.ReconciliationView> latest() {
        return ApiResult.ok(reconciliationService.latest());
    }
}
