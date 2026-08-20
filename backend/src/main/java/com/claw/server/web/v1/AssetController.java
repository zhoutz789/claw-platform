package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.AssetRequests;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.asset.AssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 资产域：车辆/电池创建、列表、详情、ACL、状态机流转、开通功能。
 */
@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @PostMapping("/vehicle")
    public ApiResult<ApiViews.AssetView> createVehicle(@Valid @RequestBody AssetRequests.CreateVehicle req) {
        return ApiResult.ok(assetService.createVehicle(req, requireOperator()));
    }

    @PostMapping("/battery")
    public ApiResult<ApiViews.AssetView> createBattery(@Valid @RequestBody AssetRequests.CreateBattery req) {
        return ApiResult.ok(assetService.createBattery(req, requireOperator()));
    }

    @GetMapping
    public ApiResult<List<ApiViews.AssetView>> list(@RequestParam(required = false) AssetType assetType,
                                                    @RequestParam(required = false) AssetStatus status) {
        return ApiResult.ok(assetService.listAssets(assetType, status));
    }

    @GetMapping("/{id}")
    public ApiResult<ApiViews.AssetView> get(@PathVariable Long id) {
        return ApiResult.ok(assetService.getAsset(id));
    }

    @PutMapping("/{id}/acl")
    public ApiResult<Void> setAcl(@PathVariable Long id, @Valid @RequestBody AssetRequests.SetAcl req) {
        assetService.setAcl(id, req, requireOperator());
        return ApiResult.ok();
    }

    @PostMapping("/{id}/status")
    public ApiResult<ApiViews.AssetView> changeStatus(@PathVariable Long id,
                                                      @Valid @RequestBody AssetRequests.ChangeStatus req) {
        return ApiResult.ok(assetService.changeStatus(id, req, requireOperator()));
    }

    @PostMapping("/{id}/functions")
    public ApiResult<String> openFunction(@PathVariable Long id) {
        return ApiResult.ok(assetService.openFunction(id, requireOperator()));
    }

    private Long requireOperator() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new IllegalStateException("unauthenticated");
        }
        return uid;
    }
}
