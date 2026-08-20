package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.DepositRequests;
import com.claw.server.common.dto.DepositViews;
import com.claw.server.domain.deposit.DepositService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 押金接口（S2）。
 *
 * <p>POST /deposits                    支付押金冻结
 * POST /deposits/{no}/release         归还押金
 * POST /deposits/{no}/forfeit         违约扣收（转残值准备金专户）
 * GET  /deposits?userId=              押金单列表
 */
@RestController
@RequestMapping("/api/v1/deposits")
@RequiredArgsConstructor
public class DepositController {

    private final DepositService depositService;

    @PostMapping
    public ApiResult<DepositViews.DepositView> hold(@Valid @RequestBody DepositRequests.Hold req) {
        return ApiResult.ok(depositService.hold(req));
    }

    @PostMapping("/{no}/release")
    public ApiResult<DepositViews.DepositView> release(@PathVariable String no) {
        return ApiResult.ok(depositService.release(new DepositRequests.Release(no)));
    }

    @PostMapping("/{no}/forfeit")
    public ApiResult<DepositViews.DepositView> forfeit(@PathVariable String no,
                                                       @Valid @RequestBody DepositRequests.Forfeit req) {
        return ApiResult.ok(depositService.forfeit(no, req.reason()));
    }

    @GetMapping
    public ApiResult<List<DepositViews.DepositView>> list(@RequestParam Long userId) {
        return ApiResult.ok(depositService.listByUser(userId));
    }
}
