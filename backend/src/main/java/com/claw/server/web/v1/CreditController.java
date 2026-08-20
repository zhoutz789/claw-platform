package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.CreditViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.credit.CreditScoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Claw Score 信用分接口（S5）。
 *
 * <p>GET  /users/me/credit-score           信用分查询（不存在则初始化 600）
 * GET  /users/me/credit-events           信用分变更事件历史
 * POST /users/me/credit-events           信用分事件（后台/风控调用，测试用）
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class CreditController {

    private final CreditScoreService creditScoreService;

    @GetMapping("/credit-score")
    public ApiResult<CreditViews.CreditScoreView> creditScore() {
        return ApiResult.ok(creditScoreService.getScore(requireUser()));
    }

    @GetMapping("/credit-events")
    public ApiResult<List<CreditViews.CreditEventView>> events() {
        return ApiResult.ok(creditScoreService.events(requireUser()));
    }

    @PostMapping("/credit-events")
    public ApiResult<CreditViews.CreditScoreView> applyEvent(
            @RequestParam int delta,
            @RequestParam String reason,
            @RequestParam(required = false) String refId) {
        return ApiResult.ok(creditScoreService.applyEvent(requireUser(), delta, reason, refId));
    }

    private Long requireUser() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new IllegalStateException("unauthenticated");
        }
        return uid;
    }
}
