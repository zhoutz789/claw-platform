package com.claw.server.domain.inventory;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CustodyStatus;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.consignment.ConsignmentCustody;
import com.claw.server.domain.consignment.ConsignmentCustodyRepository;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.lifecycle.LifecycleEvent;
import com.claw.server.domain.lifecycle.LifecycleEventRepository;
import com.claw.server.domain.onboarding.OnboardingCreditBlock;
import com.claw.server.domain.org.OrgWritableGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 库存台账服务（增量 B · R3/B4）。
 *
 * <p>运营库存台账（inventory，每设备一行）与寄售占有权（consignment_custodies）1:1。
 * 本服务负责把设备从厂家自有库「发货至服务站」：建立寄售占有权 + 库存转为寄售在站（Q2 占有权转移点）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ConsignmentCustodyRepository custodyRepository;
    private final DeviceRepository deviceRepository;
    private final LifecycleEventRepository lifecycleRepository;
    /** 增量 C：入驻禁用守卫 + 授信额度校验（C1 硬阻断）。 */
    private final OrgWritableGuard orgWritableGuard;
    private final CreditLimitService creditLimitService;

    /**
     * 发货至服务站：建立寄售占有权（consignment_custodies），库存转为寄售在站（Q2 占有权转移）。
     *
     * <p>增量 C 新增两道闸：
     * <ol>
     *   <li>{@code OrgWritableGuard} —— 服务站被平台禁用时禁止新货入站（Q7：只切新增）；</li>
     *   <li>{@code CreditLimitService} C1 —— 本次入站货值 + 已占用货值不得突破授信额度（硬阻断）。</li>
     * </ol>
     */
    @Transactional
    public void shipToStation(Long deviceId, Long stationId, Long manufacturerId, Long operatorId) {
        shipToStationBatch(List.of(deviceId), stationId, manufacturerId, operatorId);
    }

    /**
     * 批量发货至服务站（额度按<b>整批</b>校验，避免逐台放行后总量超标）。
     *
     * @throws BizException 40340 org.disabled.readonly（服务站已禁用）
     * @throws BizException 40941 credit.limit.exceeded（超出授信额度）
     * @throws BizException 40942 inventory.unit_value.required（货值无法解析，失败关闭不按 0 处理）
     */
    @Transactional
    public void shipToStationBatch(List<Long> deviceIds, Long stationId, Long manufacturerId, Long operatorId) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return;
        }
        // ① 禁用守卫：只切新增，不中断在途（Q7）
        orgWritableGuard.assertWritable(PrincipalType.STATION, stationId);
        // ② 入站时点货值快照（缺失则抛 40942，失败关闭）
        for (Long deviceId : deviceIds) {
            Inventory inv = inventoryRepository.findByDeviceId(deviceId)
                    .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
            creditLimitService.stampUnitValue(inv, null, null);
            inventoryRepository.save(inv);
        }
        // ③ C1 硬阻断：超出授信额度则落 onboarding_credit_blocks 并抛 40941
        creditLimitService.assertWithinLimit(stationId, deviceIds, OnboardingCreditBlock.Scene.CONSIGN_SHIP);
        for (Long deviceId : deviceIds) {
            shipOne(deviceId, stationId, manufacturerId, operatorId);
        }
    }

    /** 单台设备的实际发货动作（校验通过后执行）。 */
    private void shipOne(Long deviceId, Long stationId, Long manufacturerId, Long operatorId) {
        Inventory inv = inventoryRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
        // 取「当前」占有权：设备若经历过调拨，历史行已 ended_at 非空，
        // 无条件按 device_id 查会命中多行（部分唯一索引只保证未结束的行唯一）。
        ConsignmentCustody custody = custodyRepository.findByDeviceIdAndEndedAtIsNull(deviceId)
                .orElseGet(() -> custodyRepository.save(ConsignmentCustody.builder()
                        .deviceId(deviceId)
                        .manufacturerId(manufacturerId)
                        .holderStationId(stationId)
                        .status(CustodyStatus.ACTIVE)
                        .liabilityHolder("MANUFACTURER")
                        .build()));
        custody.setHolderStationId(stationId);
        custody.setManufacturerId(manufacturerId);
        custody.setStatus(CustodyStatus.ACTIVE);
        custody.setLiabilityHolder("MANUFACTURER");
        custody.setTransferredAt(null);
        custody.setTransferOrderId(null);
        custodyRepository.save(custody);

        inv.setOwnershipType(OwnershipType.CONSIGNED);
        inv.setHolderStationId(stationId);
        inv.setCustodyId(custody.getId());
        inv.setCurrentStatus(LifecycleStatus.AT_STATION);
        inv.setInboundAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inventoryRepository.save(inv);

        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setLifecycleStatus(LifecycleStatus.AT_STATION.name());
            deviceRepository.save(d);
        });
        lifecycleRepository.save(LifecycleEvent.builder()
                .deviceId(deviceId)
                .fromStatus(LifecycleStatus.PRODUCING.name())
                .toStatus(LifecycleStatus.AT_STATION.name())
                .eventType("RECEIVE")
                .operatorId(operatorId)
                .stationId(stationId)
                .occurredAt(Instant.now())
                .build());
        log.info("设备 {} 发货至服务站 {}（建寄售占有权）", deviceId, stationId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByManufacturer(Long manufacturerId) {
        return inventoryRepository.findByOwnerManufacturerId(manufacturerId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByStation(Long stationId) {
        return inventoryRepository.findByHolderStationId(stationId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByOwnership(OwnershipType ownershipType) {
        return inventoryRepository.findByOwnershipType(ownershipType);
    }

    @Transactional(readOnly = true)
    public Inventory getByDevice(Long deviceId) {
        return inventoryRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
    }
}
