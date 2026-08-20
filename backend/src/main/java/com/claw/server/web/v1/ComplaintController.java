package com.claw.server.web.v1;

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

    private Long requireUser() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new IllegalStateException("unauthenticated");
        }
        return uid;
    }
}
