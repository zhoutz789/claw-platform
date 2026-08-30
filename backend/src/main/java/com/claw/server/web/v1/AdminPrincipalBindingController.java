package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.role.PrincipalBinding;
import com.claw.server.domain.role.PrincipalBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 主体绑定管理（增量 A · Q5 严格 1:1）。
 * 账号 ↔ 厂家/服务站主体绑定；绑定即授予对应业务角色包（MANUFACTURER/STATION）。
 */
@RestController
@RequestMapping("/api/v1/admin/principal-bindings")
@RequiredArgsConstructor
public class AdminPrincipalBindingController {

    private final PrincipalBindingService bindingService;

    @GetMapping
    public ApiResult<List<PrincipalBinding>> listByUser(@RequestParam Long userId) {
        return ApiResult.ok(bindingService.listByUser(userId));
    }

    @GetMapping("/principal")
    public ApiResult<List<PrincipalBinding>> listByPrincipal(@RequestParam String principalType,
                                                            @RequestParam Long principalId) {
        return ApiResult.ok(bindingService.listByPrincipal(principalType, principalId));
    }

    @PostMapping
    @RequirePermission("mfg:bind:manage")
    public ApiResult<PrincipalBinding> bind(@RequestBody BindReq req) {
        return ApiResult.ok(bindingService.bind(req.userId(), req.principalType(), req.principalId()));
    }

    @DeleteMapping
    @RequirePermission("mfg:bind:manage")
    public ApiResult<Void> unbind(@RequestParam Long userId, @RequestParam String principalType) {
        bindingService.unbind(userId, principalType);
        return ApiResult.ok();
    }

    public record BindReq(Long userId, String principalType, Long principalId) {
    }
}
