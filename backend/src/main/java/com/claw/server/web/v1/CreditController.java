package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.CreditViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.credit.CreditScoreService;
import com.claw.server.domain.user.UserRepository;
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
    private final UserRepository userRepository;

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

    /**
     * 取当前登录用户 ID，并校验其在库中真实存在。
     *
     * <p>只判断「上下文里有没有 userId」是不够的：开发态 dev-open-access 会注入
     * 虚拟操作员（id=1），真实库里并不存在该用户，此时若继续往下走，
     * 初始化信用分就会撞上 credit_scores_user_id_fkey 外键约束，返回 500。
     * 因此这里补一次存在性校验，无有效认证上下文或未落库的用户一律 401，不写库。
     */
    private Long requireUser() {
        Long uid = AuthContext.currentUserId();
        if (uid == null || !userRepository.existsById(uid)) {
            throw BizException.unauthorized("error.auth.unauthenticated");
        }
        return uid;
    }
}
