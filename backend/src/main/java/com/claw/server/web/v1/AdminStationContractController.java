package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.contract.ContractService;
import com.claw.server.domain.contract.StationContract;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 后台服务站合约管理（缺口②）：列表 / 申请退出 / 保证金清算 / 退款看板。
 */
@RestController
@RequestMapping("/api/v1/admin/station-contracts")
@RequiredArgsConstructor
public class AdminStationContractController {

    private final ContractService contractService;

    /** 某服务站的全部合约（按生效时间倒序）。 */
    @GetMapping
    public ApiResult<List<StationContract>> listByStation(@RequestParam Long stationId) {
        return ApiResult.ok(contractService.listByStation(stationId));
    }

    /** 申请退出：置 EXIT_REQUESTED，开启 3 月保证金退款窗口。 */
    @PostMapping("/{contractId}/exit")
    @RequirePermission("station-contract:manage")
    public ApiResult<StationContract> requestExit(@PathVariable Long contractId,
                                                  @RequestBody(required = false) ExitReq req) {
        String remark = req != null ? req.remark() : null;
        return ApiResult.ok(contractService.requestExit(contractId, AuthContext.currentUserId(), remark));
    }

    /** 保证金清算完成：全额/扣减后退还，置 EXITED。 */
    @PostMapping("/{contractId}/refund")
    @RequirePermission("station-contract:manage")
    public ApiResult<StationContract> markRefunded(@PathVariable Long contractId,
                                                   @RequestParam boolean refunded,
                                                   @RequestParam BigDecimal refundAmount) {
        return ApiResult.ok(contractService.markRefunded(contractId, refunded, refundAmount,
                AuthContext.currentUserId()));
    }

    /** 退款看板：所有已申请退出的合约。 */
    @GetMapping("/pending-refunds")
    public ApiResult<List<StationContract>> pendingRefunds() {
        return ApiResult.ok(contractService.listExitRequested());
    }

    public record ExitReq(String remark) {
    }
}
