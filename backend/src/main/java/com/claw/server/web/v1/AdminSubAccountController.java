package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.domain.role.Permission;
import com.claw.server.domain.role.PermissionRepository;
import com.claw.server.domain.role.PrincipalResolver;
import com.claw.server.domain.subaccount.GrantResult;
import com.claw.server.domain.subaccount.SubAccount;
import com.claw.server.domain.subaccount.SubAccountGrant;
import com.claw.server.domain.subaccount.SubAccountGrantService;
import com.claw.server.domain.subaccount.SubAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 子账号管理接口（增量 C · 页面 10 · O28–O30）。
 *
 * <p>主体端（服务站管理者 / 厂家管理员 / 商家管理员）为协作成员开子账号，
 * 授予「全部功能」（跟随角色模板，未来新功能自动继承）或「部分功能」（菜单树勾选，
 * 实际生效集合 = 明细 ∩ 主账号模板，防越权）。
 *
 * <p>权限码：{@code org:subaccount:view} / {@code org:subaccount:manage}。
 */
@RestController
@RequestMapping("/api/v1/org/sub-accounts")
@RequiredArgsConstructor
public class AdminSubAccountController {

    private final SubAccountService subAccountService;
    private final SubAccountGrantService grantService;
    private final PermissionRepository permissionRepository;
    private final PrincipalResolver principalResolver;

    /** 当前登录账号所属主体的子账号列表。 */
    @GetMapping
    public ApiResult<List<SubAccount>> list() {
        return ApiResult.ok(subAccountService.listMine(currentUserId()));
    }

    /** 新建子账号（Q11：子账号不可再开子账号）。 */
    @PostMapping
    public ApiResult<SubAccount> create(@RequestBody CreateReq req) {
        SubAccount sa = req.userId() != null
                ? subAccountService.createByUserId(req.ownerPrincipalType(), req.ownerPrincipalId(),
                        req.userId(), req.displayName(), currentUserId())
                : subAccountService.create(req.ownerPrincipalType(), req.ownerPrincipalId(),
                        req.phone(), req.displayName(), currentUserId());
        return ApiResult.ok(sa);
    }

    /** 停用子账号（操作日志保留）。 */
    @PostMapping("/{id}/disable")
    public ApiResult<SubAccount> disable(@PathVariable Long id) {
        return ApiResult.ok(subAccountService.disable(id, currentUserId()));
    }

    /** 启用子账号（需重新授权才能拿到权限）。 */
    @PostMapping("/{id}/enable")
    public ApiResult<SubAccount> enable(@PathVariable Long id) {
        return ApiResult.ok(subAccountService.enable(id, currentUserId()));
    }

    /** 授权（ALL / PARTIAL，全量覆盖）。 */
    @PostMapping("/{id}/grant")
    public ApiResult<GrantResult> grant(@PathVariable Long id, @RequestBody GrantReq req) {
        return ApiResult.ok(grantService.grant(id, req.grantMode(), req.permissionCodes(), currentUserId()));
    }

    /** 撤销授权。 */
    @PostMapping("/{id}/revoke")
    public ApiResult<Void> revoke(@PathVariable Long id) {
        grantService.revoke(id, currentUserId());
        return ApiResult.ok();
    }

    /** 查看某子账号的当前授权与明细。 */
    @GetMapping("/{id}/grant")
    public ApiResult<GrantView> grantView(@PathVariable Long id) {
        Optional<SubAccountGrant> g = grantService.currentGrant(id);
        Set<String> effective = grantService.effectivePermissions(id);
        return ApiResult.ok(new GrantView(
                g.orElse(null),
                g.map(SubAccountGrant::getId).map(grantService::grantItems).orElse(List.of()),
                effective));
    }

    /** 权限码目录（菜单树 + 按钮码，供 PARTIAL 授权勾选）。 */
    @GetMapping("/permission-catalog")
    public ApiResult<List<Permission>> permissionCatalog() {
        return ApiResult.ok(permissionRepository.findAll());
    }

    /** 当前登录身份（是否子账号 / 所属主体），供前端判断是否允许再开子账号。 */
    @GetMapping("/me")
    public ApiResult<MeView> me() {
        return ApiResult.ok(principalResolver.resolveCurrent()
                .map(ref -> new MeView(ref.type().name(), ref.principalId(), ref.viaSubAccount()))
                .orElse(new MeView(null, null, false)));
    }

    private static Long currentUserId() {
        Long uid = com.claw.server.common.security.AuthContext.currentUserId();
        if (uid == null) {
            throw new com.claw.server.common.api.BizException(40301, "login.required");
        }
        return uid;
    }

    /** 新建子账号请求。 */
    public record CreateReq(String ownerPrincipalType, Long ownerPrincipalId, Long userId,
                            String phone, String displayName) {
    }

    /** 授权请求。 */
    public record GrantReq(String grantMode, List<String> permissionCodes) {
    }

    /** 授权视图。 */
    public record GrantView(SubAccountGrant grant, List<String> items, Set<String> effectivePermissions) {
    }

    /** 当前身份视图。 */
    public record MeView(String principalType, Long principalId, boolean viaSubAccount) {
    }
}
