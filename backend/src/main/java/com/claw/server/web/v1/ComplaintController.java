package com.claw.server.web.v1;

import com.claw.server.common.api.BizException;
import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.ComplaintRequests;
import com.claw.server.common.dto.ComplaintViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.compliance.ComplaintService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 金融消费者投诉接口（S5）。
 *
 * <p>POST /complaints                        提交投诉（受理）
 * GET  /complaints/mine                     我的投诉
 * POST /admin/complaints/{no}/resolve       后台解决投诉
 * GET  /admin/complaints?status=            后台按状态查询
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;

    @PostMapping("/complaints")
    public ApiResult<ComplaintViews.ComplaintView> submit(@Valid @RequestBody ComplaintRequests.Submit req) {
        return ApiResult.ok(complaintService.submit(requireUser(), req));
    }

    @GetMapping("/complaints/mine")
    public ApiResult<List<ComplaintViews.ComplaintView>> mine() {
        return ApiResult.ok(complaintService.listByUser(requireUser()));
    }

    @PostMapping("/admin/complaints/{no}/resolve")
    public ApiResult<ComplaintViews.ComplaintView> resolve(@PathVariable String no,
                                                           @Valid @RequestBody ComplaintRequests.Resolve req) {
        return ApiResult.ok(complaintService.resolve(no, req.resolution(), requireUser()));
    }

    @GetMapping("/admin/complaints")
    public ApiResult<List<ComplaintViews.ComplaintView>> listByStatus(@RequestParam(required = false) String status) {
        return ApiResult.ok(complaintService.listByStatus(status != null ? status : "RECEIVED"));
    }

    /**
     * 取当前登录用户 ID；无认证上下文时返回 401（不是 500）。
     *
     * <p>此前抛 IllegalStateException("unauthenticated")，而全局异常处理器没有对应
     * handler —— 会兜成 500 + "internal error"。未登录是客户端问题，正确语义是 401：
     * 客户端据此跳登录页，而不是展示「服务异常」。
     *
     * <p><b>为什么 E2E 抓不到</b>：dev-open-access=true 会注入虚拟操作员 id=1，
     * uid == null 这条路径走不到，所以开着 dev 开关冒烟永远是绿的。验证必须关掉该开关，
     * 见 scripts/e2e-smoke.sh 的未认证探测轮。
     */
    private Long requireUser() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw BizException.unauthorized("error.auth.unauthenticated");
        }
        return uid;
    }
}
