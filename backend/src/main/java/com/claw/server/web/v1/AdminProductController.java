package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ProductDtos.*;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.manufacturer.ProductService;
import com.claw.server.domain.manufacturer.ProductTemplateFieldService;
import com.claw.server.domain.role.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台产品目录（Increment 3 A 期）。
 *
 * <p>端点（前缀 /api/v1/admin/products）：
 * <ul>
 *   <li>GET  /                      产品列表（读，已登录即可）</li>
 *   <li>POST /                      创建产品（写，限平台管理员）</li>
 *   <li>GET  /{id}                  产品详情（产品 + 设备列表 + 共同属性）</li>
 *   <li>PUT  /{id}                  更新产品（写，限管理员）</li>
 *   <li>DELETE /{id}                删除产品（写，限管理员）</li>
 *   <li>GET/POST/PUT/DELETE /{id}/fields[/{fieldId}]  模板字段 EAV（写限管理员）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;
    private final ProductTemplateFieldService fieldService;
    private final PermissionService permissionService;

    private void requireAdmin() {
        Long uid = AuthContext.currentUserId();
        if (uid == null || !permissionService.isPlatformAdmin(uid)) {
            throw BizException.of(40300, "error.forbidden");
        }
    }

    @GetMapping
    public ApiResult<List<ProductView>> list() {
        return ApiResult.ok(productService.list());
    }

    @PostMapping
    public ApiResult<ProductView> create(@RequestBody CreateProductReq req) {
        requireAdmin();
        return ApiResult.ok(productService.create(req));
    }

    @GetMapping("/{id}")
    public ApiResult<ProductDetailView> detail(@PathVariable Long id) {
        return ApiResult.ok(productService.detail(id));
    }

    @PutMapping("/{id}")
    @RequirePermission("product:update")
    public ApiResult<ProductView> update(@PathVariable Long id, @RequestBody UpdateProductReq req) {
        requireAdmin();
        return ApiResult.ok(productService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("product:delete")
    public ApiResult<Void> delete(@PathVariable Long id) {
        requireAdmin();
        productService.delete(id);
        return ApiResult.ok();
    }

    @GetMapping("/{id}/fields")
    public ApiResult<List<ProductTemplateFieldView>> fields(@PathVariable Long id) {
        return ApiResult.ok(fieldService.list(id));
    }

    @PostMapping("/{id}/fields")
    @RequirePermission("product:create")
    public ApiResult<ProductTemplateFieldView> createField(@PathVariable Long id,
                                                           @RequestBody CreateProductTemplateFieldReq req) {
        requireAdmin();
        CreateProductTemplateFieldReq withProduct = new CreateProductTemplateFieldReq(
                id, req.fieldKey(), req.label(), req.type(), req.unit(),
                req.optionsJson(), req.required(), req.sortNo());
        return ApiResult.ok(fieldService.create(withProduct));
    }

    @PutMapping("/{id}/fields/{fieldId}")
    @RequirePermission("product:update")
    public ApiResult<ProductTemplateFieldView> updateField(@PathVariable Long id, @PathVariable Long fieldId,
                                                           @RequestBody UpdateProductTemplateFieldReq req) {
        requireAdmin();
        return ApiResult.ok(fieldService.update(fieldId, req));
    }

    @DeleteMapping("/{id}/fields/{fieldId}")
    @RequirePermission("product:delete")
    public ApiResult<Void> deleteField(@PathVariable Long id, @PathVariable Long fieldId) {
        requireAdmin();
        fieldService.delete(fieldId);
        return ApiResult.ok();
    }
}
