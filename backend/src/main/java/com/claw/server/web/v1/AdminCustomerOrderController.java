package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.OrderDtos.*;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.order.CustomerOrderService;
import com.claw.server.domain.order.OrderCertificateService;
import com.claw.server.domain.order.UnitRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后台客户订单闭环（Increment 2 / V38 扩展）。
 *
 * <p>POST   /                             创建订单（CREATED，支持多 SKU 下单）
 * POST   /{id}/pay                      支付（建产权[历史单资产] + 冻押金 + 发 OrderPaidEvent → PAID）
 * POST   /{id}/choose-mode              选择使用模式（SHARED 必带 stationId）
 * GET    /{id}/items                    订单项列表
 * GET    /{id}/certificate              支付后出/查合格证（QUALIFICATION）
 * POST   /{id}/ship                     发货（SHIPPED，V38 登记守卫）
 * POST   /{id}/complete                 完成（发 OrderCompletedEvent → COMPLETED）
 * GET    /                              订单列表（?buyerUserId=&status=）
 * <p>V38 逐台登记端点：
 * POST   /{id}/items/{itemId}/registrations  批量登记（生成资产 + 入闭环）
 * GET    /{id}/registrations                 登记进度（各 SKU N/total + 资产列表）
 * DELETE /{id}/registrations/{regId}        发货前纠错（资产回滚 RETIRED）
 */
@RestController
@RequestMapping("/api/v1/admin/customer-orders")
@RequiredArgsConstructor
public class AdminCustomerOrderController {

    private final CustomerOrderService orderService;
    private final OrderCertificateService certificateService;
    private final UnitRegistrationService unitRegistrationService;

    @PostMapping
    public ApiResult<CustomerOrderView> create(@RequestBody CreateCustomerOrderReq req) {
        return ApiResult.ok(orderService.create(req));
    }

    @PostMapping("/{id}/pay")
    public ApiResult<CustomerOrderView> pay(@PathVariable Long id, @RequestBody PayReq req) {
        return ApiResult.ok(orderService.pay(id, req));
    }

    @PostMapping("/{id}/choose-mode")
    public ApiResult<CustomerOrderView> chooseMode(@PathVariable Long id, @RequestBody ChooseModeReq req) {
        return ApiResult.ok(orderService.chooseMode(id, req));
    }

    @GetMapping("/{id}/items")
    public ApiResult<List<CustomerOrderItemView>> items(@PathVariable Long id) {
        return ApiResult.ok(orderService.listItems(id));
    }

    @GetMapping("/{id}/certificate")
    public ApiResult<CertificateView> certificate(@PathVariable Long id) {
        return ApiResult.ok(certificateService.issueOrGet(id));
    }

    @PostMapping("/{id}/ship")
    public ApiResult<CustomerOrderView> ship(@PathVariable Long id) {
        return ApiResult.ok(orderService.ship(id));
    }

    @PostMapping("/{id}/complete")
    public ApiResult<CustomerOrderView> complete(@PathVariable Long id) {
        return ApiResult.ok(orderService.complete(id));
    }

    @GetMapping
    public ApiResult<List<CustomerOrderView>> list(@RequestParam(required = false) Long buyerUserId,
                                                   @RequestParam(required = false) String status) {
        return ApiResult.ok(orderService.list(buyerUserId, status));
    }

    // ===== V38：发货前逐台登记 =====

    /** 批量登记某订单项的 N 台设备（生成资产 + 入运营闭环）。 */
    @PostMapping("/{id}/items/{itemId}/registrations")
    public ApiResult<List<UnitRegistrationView>> registerUnits(@PathVariable Long id,
                                                               @PathVariable Long itemId,
                                                               @RequestBody UnitRegistrationBatchReq batch) {
        return ApiResult.ok(unitRegistrationService.registerUnits(itemId, batch, AuthContext.currentUserId()));
    }

    /** 订单登记进度（各 SKU 已登记数 / 需登记数 + 资产列表 + 是否可发货）。 */
    @GetMapping("/{id}/registrations")
    public ApiResult<OrderRegistrationProgressView> registrationProgress(@PathVariable Long id) {
        return ApiResult.ok(unitRegistrationService.progress(id));
    }

    /** 发货前纠错：删除登记行（资产回滚 RETIRED）。 */
    @DeleteMapping("/{id}/registrations/{regId}")
    public ApiResult<Void> deleteRegistration(@PathVariable Long id, @PathVariable Long regId) {
        unitRegistrationService.deleteRegistration(regId, AuthContext.currentUserId());
        return ApiResult.ok();
    }
}
