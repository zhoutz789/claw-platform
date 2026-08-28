package com.claw.server.domain.order;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.AssetRequests;
import com.claw.server.common.dto.AssetRequests.ProvisionAssetReq;
import com.claw.server.common.dto.OrderDtos.*;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.OrderStatus;
import com.claw.server.common.enums.UsageMode;
import com.claw.server.domain.asset.AssetService;
import com.claw.server.domain.order.event.AssetProvisionedEvent;
import com.claw.server.domain.sharedpool.SharedPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 订单逐台登记编排（V38）。
 *
 * <p>登记守卫：订单须已支付（PAID / CERTIFICATED）且未发货；序号自增不重复；
 * 单批次登记数不得超过订单项 quantity（不允许超登，部分发货由 ship 守卫拦截）。
 *
 * <p>跨域边界（ArchUnit）：本服务只经 {@link AssetService} 服务接口调资产域，
 * 并经 {@link AssetProvisionedEvent} 领域事件解耦后续流转；不直持资产域 Repository。
 * 产权台账（AssetOwnership）经 {@link SharedPoolService#establishOwnership} 建立（人人经济：资产诞生即归属买家）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnitRegistrationService {

    private final UnitRegistrationRepository registrationRepository;
    private final CustomerOrderRepository orderRepository;
    private final CustomerOrderItemRepository orderItemRepository;
    private final AssetService assetService;
    private final SharedPoolService sharedPoolService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 批量登记某订单项的 N 台设备：逐台调 {@link AssetService#provisionFromRegistration} 生成资产
     * + 建产权台账 + 存登记行 + 发 {@link AssetProvisionedEvent}。
     *
     * @return 本次登记的视图列表
     */
    @Transactional
    public List<UnitRegistrationView> registerUnits(Long orderItemId, UnitRegistrationBatchReq batch,
                                                    Long operatorId) {
        if (batch == null || batch.units() == null || batch.units().isEmpty()) {
            throw BizException.invalidParam("error.order.registration.empty");
        }
        CustomerOrderItem item = orderItemRepository.findById(orderItemId)
                .orElseThrow(() -> BizException.notFound("error.order.item.not.found"));
        CustomerOrder order = orderRepository.findById(item.getOrderId())
                .orElseThrow(() -> BizException.notFound("error.order.not.found"));

        // 守卫：已支付且未发货
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.CERTIFICATED) {
            throw BizException.invalidParam("error.order.registration.not.paid");
        }
        if (order.getShippedAt() != null) {
            throw BizException.invalidParam("error.order.shipped");
        }

        List<UnitRegistration> existing = registrationRepository.findByOrderItemId(item.getId());
        int maxSeq = existing.stream().mapToInt(UnitRegistration::getSeq).max().orElse(0);
        if (existing.size() + batch.units().size() > item.getQuantity()) {
            throw BizException.invalidParam("error.order.registration.over",
                    item.getSkuId(), existing.size() + batch.units().size(), item.getQuantity());
        }

        Long ownerId = order.getBuyerUserId();
        List<UnitRegistrationView> views = new ArrayList<>();
        int seq = maxSeq;
        for (UnitRegistrationReq u : batch.units()) {
            seq++;
            if (u.qrCode() == null || u.qrCode().isBlank()) {
                throw BizException.invalidParam("error.asset.qr.required");
            }
            if (registrationRepository.findByQrCode(u.qrCode()).isPresent()) {
                throw BizException.invalidParam("error.asset.no.duplicate");
            }

            ProvisionAssetReq preq = new ProvisionAssetReq(
                    item.getAssetType(),
                    null,                       // assetNo 自动生成
                    u.qrCode(),
                    u.productId() != null ? u.productId() : order.getProductId(),
                    item.getSkuId(),
                    u.manufacturerId(),
                    u.serialNumber(),
                    ownerId,
                    item.getId(),
                    u.vin(),
                    u.frameNo(),
                    u.motorNo(),
                    u.remoteId(),
                    u.model(),
                    u.capacityKwh(),
                    u.componentNosJson());

            ApiViews.AssetView av = assetService.provisionFromRegistration(preq, operatorId);

            // 产权台账：资产诞生即归属买家（人人经济）
            sharedPoolService.establishOwnership(av.id(), ownerId, BigDecimal.ZERO,
                    "ASSET-PROVISION-" + av.id());

            UnitRegistration reg = UnitRegistration.builder()
                    .orderId(order.getId())
                    .orderItemId(item.getId())
                    .seq(seq)
                    .qrCode(u.qrCode())
                    .vin(u.vin())
                    .frameNo(u.frameNo())
                    .motorNo(u.motorNo())
                    .componentNosJson(u.componentNosJson())
                    .assetId(av.id())
                    .status(UnitRegistration.UnitRegistrationStatus.REGISTERED)
                    .tenantId(order.getTenantId() != null ? order.getTenantId() : 1L)
                    .build();
            reg = registrationRepository.save(reg);

            eventPublisher.publishEvent(new AssetProvisionedEvent(
                    order.getId(), item.getId(), av.id(), item.getAssetType(),
                    preq.productId(), preq.skuId(), preq.manufacturerId(), ownerId,
                    order.getUsageMode() != null ? order.getUsageMode().name() : UsageMode.SELF.name(),
                    order.getStationId(), u.qrCode(), u.vin(), u.frameNo(), u.motorNo(),
                    u.componentNosJson()));

            views.add(toView(reg, av.id()));
        }
        log.info("订单项登记完成 orderItemId={} 新增={} 当前已登记={}", item.getId(), batch.units().size(), seq);
        return views;
    }

    /** 登记进度（各 SKU 已登记数 / 需登记数 + 资产列表 + 是否可发货）。 */
    @Transactional(readOnly = true)
    public OrderRegistrationProgressView progress(Long orderId) {
        List<CustomerOrderItem> items = orderItemRepository.findByOrderId(orderId);
        List<UnitRegistration> regs = registrationRepository.findByOrderId(orderId);

        List<ItemRegistrationProgress> itemProgress = items.stream().map(i -> {
            int registered = (int) regs.stream()
                    .filter(r -> r.getOrderItemId().equals(i.getId())
                            && r.getStatus() == UnitRegistration.UnitRegistrationStatus.REGISTERED)
                    .count();
            return new ItemRegistrationProgress(i.getId(), i.getSkuId(), i.getAssetType(),
                    i.getQuantity(), registered);
        }).toList();

        boolean canShip = !items.isEmpty() && items.stream().allMatch(i ->
                regs.stream().filter(r -> r.getOrderItemId().equals(i.getId())
                        && r.getStatus() == UnitRegistration.UnitRegistrationStatus.REGISTERED).count()
                        == i.getQuantity());

        List<UnitRegistrationView> views = regs.stream().map(r -> toView(r, r.getAssetId())).toList();
        return new OrderRegistrationProgressView(itemProgress, views, canShip);
    }

    /** 某订单项已登记（REGISTERED）数量（ship 守卫用）。 */
    @Transactional(readOnly = true)
    public long countRegistered(Long orderItemId) {
        return registrationRepository.countByOrderItemIdAndStatus(
                orderItemId, UnitRegistration.UnitRegistrationStatus.REGISTERED);
    }

    /** 发货前纠错：删除登记行（资产置 RETIRED 回滚，留痕审计）。 */
    @Transactional
    public void deleteRegistration(Long regId, Long operatorId) {
        UnitRegistration reg = registrationRepository.findById(regId)
                .orElseThrow(() -> BizException.notFound("error.order.registration.not.found"));
        if (reg.getStatus() != UnitRegistration.UnitRegistrationStatus.REGISTERED) {
            throw BizException.invalidParam("error.order.registration.status.invalid");
        }
        // 资产回滚为 RETIRED（闭环终点之一，保留审计），登记行删除后该序号可重新登记
        assetService.autoTransition(reg.getAssetId(), AssetStatus.RETIRED, "发货前登记纠错回滚");
        registrationRepository.delete(reg);
        log.info("登记纠错删除 regId={} assetId={}", regId, reg.getAssetId());
    }

    private UnitRegistrationView toView(UnitRegistration r, Long assetId) {
        return new UnitRegistrationView(r.getId(), r.getOrderId(), r.getOrderItemId(), r.getSeq(),
                r.getQrCode(), r.getVin(), r.getFrameNo(), r.getMotorNo(), assetId,
                r.getStatus().name(), r.getComponentNosJson());
    }
}
