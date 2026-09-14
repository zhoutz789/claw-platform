package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CustodyOwnerType;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.funds.FundsLocation;
import com.claw.server.domain.funds.FundsLocationService;
import com.claw.server.domain.funds.VirtualSubAccount;
import com.claw.server.domain.funds.VirtualSubAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台资金管理（资金路由与清分域 · T10）。
 *
 * <p>覆盖设计 §5 / §7 的后台入口：托管点位（funds_location）总览、虚拟子户（virtual_subaccount）
 * 总览与开户。所有接口均标注 {@link RequirePermission}（权限位挂在 {@code menu:finance} 下，
 * 由 V136 权限种子写入），未授权访问由 {@code web.support.PermissionAspect} 在入口拦截。
 *
 * <p>约定：开户操作人不依赖登录态，权限判定与登录态校验统一交给
 * {@code PermissionAspect} + {@code AuthContext}；开户本身的业务校验在 {@link VirtualSubAccountService} 内完成。
 */
@RestController
@RequestMapping("/api/v1/admin/funds")
@RequiredArgsConstructor
public class AdminFundsController {

    private final FundsLocationService fundsLocationService;
    private final VirtualSubAccountService virtualSubAccountService;

    /** 托管点位列表（全部未删除，按 id 升序）。 */
    @GetMapping("/locations")
    @RequirePermission("finance:funds:view")
    public ApiResult<List<FundsLocation>> listLocations() {
        return ApiResult.ok(fundsLocationService.list());
    }

    /** 虚拟子户列表（全部未删除，按创建时间倒序）。 */
    @GetMapping("/sub-accounts")
    @RequirePermission("finance:funds:view")
    public ApiResult<List<VirtualSubAccount>> listSubAccounts() {
        return ApiResult.ok(virtualSubAccountService.list());
    }

    /**
     * 开设虚拟子户（幂等：命中既有 {@code (ownerType, ownerId, currency, fundsLocationId)} 直接返回）。
     *
     * @param req 开户入参（持有方类型 / 业务 id / 映射账本用户 / 托管点位 / 币种）
     */
    @PostMapping("/sub-accounts/open")
    @RequirePermission("finance:funds:manage")
    public ApiResult<VirtualSubAccount> openSubAccount(@RequestBody OpenSubAccountReq req) {
        CustodyOwnerType ownerType = parseOwnerType(req.ownerType());
        return ApiResult.ok(virtualSubAccountService.open(
                ownerType, req.ownerId(), req.ownerUserId(), req.fundsLocationId(), req.currency()));
    }

    // ===================== 内部 =====================

    private static CustodyOwnerType parseOwnerType(String raw) {
        try {
            return CustodyOwnerType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw BizException.invalidParam("error.funds.subaccount.owner.type.missing");
        }
    }

    /** 开户入参。 */
    public record OpenSubAccountReq(
            String ownerType,
            Long ownerId,
            Long ownerUserId,
            Long fundsLocationId,
            String currency) {
    }
}
