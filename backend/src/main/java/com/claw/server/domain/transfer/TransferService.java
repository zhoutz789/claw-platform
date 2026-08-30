package com.claw.server.domain.transfer;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CustodyStatus;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.enums.TransferStatus;
import com.claw.server.domain.consignment.ConsignmentCustody;
import com.claw.server.domain.consignment.ConsignmentCustodyRepository;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryRepository;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.lifecycle.LifecycleEvent;
import com.claw.server.domain.lifecycle.LifecycleEventRepository;
import com.claw.server.domain.onboarding.OnboardingCreditBlock;
import com.claw.server.domain.org.OrgWritableGuard;
import com.claw.server.domain.station.StationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 站间调拨服务（增量 B · R5/B7）。
 *
 * <p>厂家发起调拨单，将寄售设备从源服务站调拨到目标服务站；扫码交接时占有权随
 * {@code ConsignmentCustody} 转移（Q2）：源站交接（handover）→ 在途（LOGISTICS 责任）→
 * 目标站收货（receive）建新占有权。库存台账（inventory）同步更新持有服务站与状态。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private final TransferOrderRepository transferRepository;
    private final TransferOrderItemRepository transferItemRepository;
    private final ConsignmentCustodyRepository custodyRepository;
    private final InventoryRepository inventoryRepository;
    private final DeviceRepository deviceRepository;
    private final LifecycleEventRepository lifecycleRepository;
    private final StationRepository stationRepository;
    /** 增量 C：入驻禁用守卫 + 授信额度校验（C4 软预检 / C2 硬阻断）。 */
    private final OrgWritableGuard orgWritableGuard;
    private final CreditLimitService creditLimitService;

    /** 建调拨单的结果：单据本体 + C4 软预检告警（若有）。 */
    public record CreateTransferResult(TransferOrder order, String warning) {
    }

    /**
     * 建调拨单（沿用既有签名，C4 软预检告警只记日志不中断）。
     *
     * <p>需要拿到告警文案的调用方请用 {@link #createTransferWithWarning}。
     */
    @Transactional
    public TransferOrder createTransfer(Long manufacturerId, Long fromStationId, Long toStationId,
                                       List<Long> deviceIds, BigDecimal logisticsFee, Long operatorId) {
        CreateTransferResult r = createTransferWithWarning(manufacturerId, fromStationId, toStationId,
                deviceIds, logisticsFee, operatorId, null);
        return r.order();
    }

    /**
     * 建调拨单（返回 C4 软预检告警）。
     *
     * <p>增量 C 新增两道闸：
     * <ol>
     *   <li>禁用守卫 —— 源站与目标站任一被禁用即拒绝建单（Q7：只切新增）；</li>
     *   <li><b>C4 软预检</b> —— 目标站额度超限只返回告警、<b>不阻断建单</b>
     *       （建单时在途状态未定，硬阻断会误伤正常业务）。
     *       告警同时落 {@code onboarding_credit_blocks}（scene=TRANSFER_CREATE）留痕，
     *       真正的硬校验在目标站收货（C2）时做。</li>
     * </ol>
     *
     * @param transferIdHint 建单前已知的调拨单号占位（可为空，仅用于阻断记录关联）
     * @return 单据 + 告警文案（无超限则 warning 为 null）
     */
    @Transactional
    public CreateTransferResult createTransferWithWarning(Long manufacturerId, Long fromStationId,
                                                          Long toStationId, List<Long> deviceIds,
                                                          BigDecimal logisticsFee, Long operatorId,
                                                          Long transferIdHint) {
        if (stationRepository.findById(fromStationId).isEmpty()) {
            throw BizException.of(40401, "station.not.found");
        }
        if (stationRepository.findById(toStationId).isEmpty()) {
            throw BizException.of(40401, "station.not.found");
        }
        // ① 禁用守卫（源站 + 目标站）
        orgWritableGuard.assertWritable(PrincipalType.STATION, fromStationId);
        orgWritableGuard.assertWritable(PrincipalType.STATION, toStationId);
        // ② C4 软预检：只告警不阻断
        String warning = creditLimitService.softPrecheck(toStationId, deviceIds, transferIdHint).orElse(null);
        if (warning != null) {
            log.warn("调拨单建单软预检告警（不阻断）：from={} to={} —— {}", fromStationId, toStationId, warning);
        }
        TransferOrder o = buildTransfer(manufacturerId, fromStationId, toStationId, deviceIds, logisticsFee, operatorId);
        return new CreateTransferResult(o, warning);
    }

    private TransferOrder buildTransfer(Long manufacturerId, Long fromStationId, Long toStationId,
                                        List<Long> deviceIds, BigDecimal logisticsFee, Long operatorId) {
        TransferOrder o = TransferOrder.builder()
                .transferNo("TR" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 16))
                .manufacturerId(manufacturerId)
                .fromStationId(fromStationId)
                .toStationId(toStationId)
                .status(TransferStatus.DRAFT)
                .logisticsFee(logisticsFee)
                .createdBy(operatorId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        o = transferRepository.save(o);
        for (Long devId : deviceIds) {
            ConsignmentCustody from = custodyRepository.findByDeviceIdAndEndedAtIsNull(devId)
                    .orElseThrow(() -> BizException.of(40401, "custody.not.found"));
            if (!from.getHolderStationId().equals(fromStationId)) {
                throw BizException.of(40912, "custody.not.at.from.station");
            }
            transferItemRepository.save(TransferOrderItem.builder()
                    .transferOrderId(o.getId())
                    .deviceId(devId)
                    .fromCustodyId(from.getId())
                    .build());
        }
        return o;
    }

    /** 源站扫码交接：源占有权转出（TRANSFERRED_OUT），在途责任转 LOGISTICS；库存转 IN_TRANSIT。 */
    @Transactional
    public TransferOrder handover(Long transferId, Long operatorId) {
        TransferOrder o = load(transferId);
        if (o.getStatus() != TransferStatus.DRAFT && o.getStatus() != TransferStatus.CREATED) {
            throw BizException.of(40913, "transfer.not.handoverable");
        }
        for (TransferOrderItem item : transferItemRepository.findByTransferOrderId(transferId)) {
            ConsignmentCustody from = custodyRepository.findById(item.getFromCustodyId())
                    .orElseThrow(() -> BizException.of(40401, "custody.not.found"));
            from.setStatus(CustodyStatus.TRANSFERRED_OUT);
            from.setTransferredAt(Instant.now());
            from.setLiabilityHolder("LOGISTICS");
            from.setTransferOrderId(transferId);
            custodyRepository.save(from);
            updateInventoryStatus(item.getDeviceId(), LifecycleStatus.IN_TRANSIT, null);
        }
        o.setStatus(TransferStatus.IN_TRANSIT);
        o.setHandoverAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return transferRepository.save(o);
    }

    /**
     * 目标站扫码收货：建新占有权（持目标站），源占有权结束；库存转 AT_STATION。
     *
     * <p>增量 C 新增 <b>C2 硬阻断</b>：目标站收货后其寄售占用增加，
     * 故必须校验目标站授信额度（超额则落 {@code onboarding_credit_blocks} 并抛 40941）。
     *
     * <p>⚠️ 此处<b>不挂</b> {@code OrgWritableGuard} 禁用守卫 —— Q7 拍板
     * 「已在途的调拨继续完成」，禁用只切新增不中断在途。
     */
    @Transactional
    public TransferOrder receive(Long transferId, Long operatorId) {
        TransferOrder o = load(transferId);
        if (o.getStatus() != TransferStatus.IN_TRANSIT) {
            throw BizException.of(40914, "transfer.not.in.transit");
        }
        // C2 硬阻断：目标站额度校验（按整批）
        List<Long> inDeviceIds = transferItemRepository.findByTransferOrderId(transferId).stream()
                .map(TransferOrderItem::getDeviceId).toList();
        creditLimitService.assertWithinLimit(o.getToStationId(), inDeviceIds,
                OnboardingCreditBlock.Scene.TRANSFER_IN, "TRANSFER", transferId);
        for (TransferOrderItem item : transferItemRepository.findByTransferOrderId(transferId)) {
            ConsignmentCustody from = custodyRepository.findById(item.getFromCustodyId())
                    .orElseThrow(() -> BizException.of(40401, "custody.not.found"));
            // 先关闭源占有权并立即落库：device_id 上建有「未结束唯一」的部分唯一索引
            // （V63 uq_cc_device_active），必须让旧行 ended_at 先变为非空，新行才插得进去。
            from.setEndedAt(Instant.now());
            from.setEndedReason("TRANSFERRED");
            custodyRepository.saveAndFlush(from);
            ConsignmentCustody to = custodyRepository.save(ConsignmentCustody.builder()
                    .deviceId(item.getDeviceId())
                    .manufacturerId(o.getManufacturerId())
                    .holderStationId(o.getToStationId())
                    .status(CustodyStatus.ACTIVE)
                    .liabilityHolder("STATION")
                    .transferOrderId(transferId)
                    .build());

            inventoryRepository.findByDeviceId(item.getDeviceId()).ifPresent(inv -> {
                inv.setHolderStationId(o.getToStationId());
                inv.setCustodyId(to.getId());
                inv.setCurrentStatus(LifecycleStatus.AT_STATION);
                inv.setUpdatedAt(Instant.now());
                inventoryRepository.save(inv);
            });
            deviceRepository.findById(item.getDeviceId()).ifPresent(d -> {
                d.setLifecycleStatus(LifecycleStatus.AT_STATION.name());
                deviceRepository.save(d);
            });
            lifecycleRepository.save(LifecycleEvent.builder()
                    .deviceId(item.getDeviceId())
                    .fromStatus(LifecycleStatus.IN_TRANSIT.name())
                    .toStatus(LifecycleStatus.AT_STATION.name())
                    .eventType("TRANSFER_IN")
                    .operatorId(operatorId)
                    .stationId(o.getToStationId())
                    .custodyRef(to.getId())
                    .occurredAt(Instant.now())
                    .build());
        }
        o.setStatus(TransferStatus.COMPLETED);
        o.setReceiveAt(Instant.now());
        o.setCompletedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return transferRepository.save(o);
    }

    @Transactional(readOnly = true)
    public List<TransferOrder> listTransfers(Long manufacturerId) {
        return (manufacturerId == null)
                ? transferRepository.findAll()
                : transferRepository.findByManufacturerId(manufacturerId);
    }

    @Transactional(readOnly = true)
    public TransferOrder getTransfer(Long id) {
        return load(id);
    }

    private TransferOrder load(Long id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> BizException.of(40401, "transfer.order.not.found"));
    }

    private void updateInventoryStatus(Long deviceId, LifecycleStatus status, Long stationId) {
        inventoryRepository.findByDeviceId(deviceId).ifPresent(inv -> {
            inv.setCurrentStatus(status);
            inv.setUpdatedAt(Instant.now());
            inventoryRepository.save(inv);
        });
        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setLifecycleStatus(status.name());
            deviceRepository.save(d);
        });
        lifecycleRepository.save(LifecycleEvent.builder()
                .deviceId(deviceId)
                .toStatus(status.name())
                .eventType("TRANSFER_OUT")
                .stationId(stationId)
                .occurredAt(Instant.now())
                .build());
    }
}
