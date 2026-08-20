package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.SettlementViews;
import com.claw.server.domain.settlement.CrossBorderSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 跨境结算开放接口。
 *
 * <p>POST /settlements/cross-border   两国贸易物资转移的结算报价（各算各的）
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SettlementController {

    private final CrossBorderSettlementService settlementService;

    /** 跨境物资转移结算报价：出口国退税 + 进口国关税/增值税，各自计算。 */
    @PostMapping("/settlements/cross-border")
    public ApiResult<SettlementViews.CrossBorderQuoteResult> crossBorder(
            @RequestBody SettlementViews.CrossBorderQuoteRequest request) {
        return ApiResult.ok(settlementService.quote(request));
    }
}
