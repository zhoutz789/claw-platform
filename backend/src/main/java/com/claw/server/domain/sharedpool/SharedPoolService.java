package com.claw.server.domain.sharedpool;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.OwnershipStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.enums.PoolEntryStatus;
import com.claw.server.common.enums.RevenueShareBasis;
import com.claw.server.common.enums.RentalOrderStatus;
import com.claw.server.common.enums.RentalType;
import com.claw.server.common.enums.SettlementStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 共享池服务（V12, Phase 2 核心）。
 *
 * <p>职责：
 * <ul>
 *   <li>资产入池 / 出池管理</li>
 *   <li>租赁订单创建与完成</li>
 *   <li>分账规则查询与校验</li>
 *   <li>使用计费（基础费 + 使用费 + 占用费）</li>
 * </ul>
 *
 * <p>对应 PRD 4.16：共享车辆/电池池运营 + D39/D40 灵活换电。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SharedPoolService {

    private final SharedPoolEntryRepository poolEntryRepository;
    private final RentalOrderRepository rentalOrderRepository;
    private final RentalUsageSessionRepository usageSessionRepository;
    private final RevenueSplitRuleRepository splitRuleRepository;
    private final AssetOwnershipRepository ownershipRepository;
    private final RevenueSettlementRepository settlementRepository;
    private final com.claw.server.domain.capacity.CapacityBookingService capacityBookingService;

    /**
     * 资产入池：将资产放入共享池，指定站点和分成比例。
     *
     * @param assetId          资产 ID
     * @param ownerUserId       所有人 ID
     * @param stationId         投放站点
     * @param ownerSplitRate   所有人分成比例（≥50%）
     * @param stationSplitRate 站点分成比例（≥15%）
     * @param dailyUsageFee    日租金（车辆，可 0）
     * @param perSwapFee       单次换电费（电池，可 0）
     * @return 入池记录
     */
    @Transactional
    public SharedPoolEntry poolAsset(Long assetId, Long ownerUserId, Long stationId,
                                    BigDecimal ownerSplitRate, BigDecimal stationSplitRate,
                                    BigDecimal dailyUsageFee, BigDecimal perSwapFee) {
        // 校验分成比例下限（未传则使用平台默认：业主 70% / 站点 15%）
        BigDecimal ownerRate = ownerSplitRate != null ? ownerSplitRate : BigDecimal.valueOf(0.70);
        BigDecimal stationRate = stationSplitRate != null ? stationSplitRate : BigDecimal.valueOf(0.15);
        if (ownerRate.compareTo(BigDecimal.valueOf(0.50)) < 0) {
            throw BizException.invalidParam("error.split.owner.min");
        }
        if (stationRate.compareTo(BigDecimal.valueOf(0.15)) < 0) {
            throw BizException.invalidParam("error.split.station.min");
        }

        // 校验产权
        AssetOwnership ownership = ownershipRepository.findActiveOwnership(assetId)
                .orElseThrow(() -> BizException.notFound("error.ownership.not.found"));

        SharedPoolEntry entry = SharedPoolEntry.builder()
                .assetId(assetId)
                .ownerUserId(ownerUserId)
                .ownershipId(ownership.getId())
                .currentStationId(stationId)
                .status(PoolEntryStatus.IN_POOL)
                .ownerSplitRate(ownerRate)
                .stationSplitRate(stationRate)
                .dailyUsageFee(dailyUsageFee != null ? dailyUsageFee : BigDecimal.ZERO)
                .perSwapFee(perSwapFee != null ? perSwapFee : BigDecimal.ZERO)
                .pooledAt(Instant.now())
                .build();

        entry = poolEntryRepository.save(entry);
        log.info("资产入池 assetId={} stationId={} owner={} entryId={}",
                assetId, stationId, ownerUserId, entry.getId());
        return entry;
    }

    /**
     * 创建租赁订单。
     */
    @Transactional
    public RentalOrder createRental(Long assetId, Long renterUserId, Long stationId,
                                    String rentalType, Long poolEntryId) {
        String orderNo = "RNT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        RentalOrder order = RentalOrder.builder()
                .orderNo(orderNo)
                .assetId(assetId)
                .renterUserId(renterUserId)
                .stationId(stationId)
                .rentalType(rentalType != null ? RentalType.valueOf(rentalType) : RentalType.VEHICLE_RENTAL)
                .poolEntryId(poolEntryId)
                .status(RentalOrderStatus.CREATED)
                .build();

        // 标记池内资产为 IN_USE
        if (poolEntryId != null) {
            poolEntryRepository.findById(poolEntryId).ifPresent(e -> {
                e.setStatus(PoolEntryStatus.IN_USE);
                poolEntryRepository.save(e);
            });
        }

        order = rentalOrderRepository.save(order);
        log.info("创建租赁订单 orderNo={} assetId={} renter={}", orderNo, assetId, renterUserId);
        return order;
    }

    /**
     * 完成租赁：计算费用 + 分账 + 更新状态。
     */
    @Transactional
    public RentalOrder completeRental(Long rentalOrderId, BigDecimal totalFee) {
        RentalOrder order = rentalOrderRepository.findById(rentalOrderId)
                .orElseThrow(() -> BizException.notFound("error.rental.not.found"));

        if (order.getStatus() != RentalOrderStatus.CREATED &&
            order.getStatus() != RentalOrderStatus.ACTIVE) {
            throw BizException.of(40960, "error.rental.status.invalid");
        }

        // 查找分成规则（提取 final 变量供 lambda 捕获，避免 order 后续重新赋值破坏 effectively final）
        final Long assetId = order.getAssetId();
        RevenueSplitRule splitRule = splitRuleRepository
                .findFirstByAssetIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(
                        assetId, "ACTIVE")
                .orElseGet(() -> {
                    // 使用默认分成规则：所有人 70% / 站点 15% / 平台 10% / 保险 5%
                    return RevenueSplitRule.builder()
                            .assetId(assetId)
                            .ownerRate(BigDecimal.valueOf(0.70))
                            .stationRate(BigDecimal.valueOf(0.15))
                            .platformRate(BigDecimal.valueOf(0.10))
                            .insuranceRate(BigDecimal.valueOf(0.05))
                            .build();
                });

        // 计算分账
        order.setTotalFee(totalFee);
        order.setOwnerShare(totalFee.multiply(splitRule.getOwnerRate()));
        order.setStationShare(totalFee.multiply(splitRule.getStationRate()));
        order.setPlatformShare(totalFee.multiply(splitRule.getPlatformRate()));
        order.setInsuranceShare(totalFee.multiply(splitRule.getInsuranceRate()));
        order.setStatus(RentalOrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        // 恢复池内资产状态
        if (order.getPoolEntryId() != null) {
            poolEntryRepository.findById(order.getPoolEntryId()).ifPresent(e -> {
                e.setStatus(PoolEntryStatus.IN_POOL);
                poolEntryRepository.save(e);
            });
        }

        order = rentalOrderRepository.save(order);
        log.info("完成租赁订单 orderNo={} totalFee={} ownerShare={} stationShare={}",
                order.getOrderNo(), totalFee, order.getOwnerShare(), order.getStationShare());

        // 容量预订回佣：从厂家 owner_share 计提并按定购单位比例自动分成。
        // 异常不阻断租赁完成（回佣失败可后续补偿），故就地捕获。
        try {
            int rebateRows = capacityBookingService.applyRebateForRental(order.getId());
            if (rebateRows > 0) {
                log.info("容量回佣已触发 rentalOrderId={} rows={}", order.getId(), rebateRows);
            }
        } catch (Exception e) {
            log.error("容量回佣失败(不影响租赁完成) rentalOrderId={} : {}", order.getId(), e.getMessage());
        }
        return order;
    }

    /**
     * 资产出池：所有人取回资产或回收。
     */
    @Transactional
    public SharedPoolEntry removeFromPool(Long poolEntryId) {
        SharedPoolEntry entry = poolEntryRepository.findById(poolEntryId)
                .orElseThrow(() -> BizException.notFound("error.pool.entry.not.found"));

        if (entry.getStatus() == PoolEntryStatus.IN_USE) {
            throw BizException.of(40961, "error.pool.entry.in.use");
        }

        entry.setStatus(PoolEntryStatus.REMOVED);
        entry.setRemovedAt(Instant.now());
        entry.setUpdatedAt(Instant.now());
        entry = poolEntryRepository.save(entry);

        log.info("资产出池 entryId={} assetId={}", poolEntryId, entry.getAssetId());
        return entry;
    }

    /**
     * 查询站点可租用资产。
     */
    @Transactional(readOnly = true)
    public List<SharedPoolEntry> listAvailableAtStation(Long stationId) {
        return poolEntryRepository.findByCurrentStationIdAndStatusAndDeletedFalse(
                stationId, PoolEntryStatus.IN_POOL);
    }

    /**
     * 查询用户所有入池资产。
     */
    @Transactional(readOnly = true)
    public List<SharedPoolEntry> listOwnerEntries(Long ownerUserId) {
        return poolEntryRepository.findByOwnerUserIdAndDeletedFalse(ownerUserId);
    }

    /**
     * 查询用户租赁记录。
     */
    @Transactional(readOnly = true)
    public List<RentalOrder> listRenterOrders(Long renterUserId) {
        return rentalOrderRepository.findByRenterUserIdAndDeletedFalse(renterUserId);
    }

    /**
     * 建立资产产权（全款购买）。order 域经此方法建产权，避免直持 AssetOwnershipRepository。
     *
     * @param assetId          资产 ID
     * @param ownerUserId       所有人 ID
     * @param purchasePrice     购买总价
     * @param purchaseOrderNo   购买订单号（客户订单支付单号）
     * @return 产权记录
     */
    @Transactional
    public AssetOwnership establishOwnership(Long assetId, Long ownerUserId,
                                            BigDecimal purchasePrice, String purchaseOrderNo) {
        AssetOwnership ownership = AssetOwnership.builder()
                .assetId(assetId)
                .ownerUserId(ownerUserId)
                .ownershipType(OwnershipType.FULL)
                .purchasePrice(purchasePrice)
                .purchaseDate(LocalDate.now())
                .purchaseOrderNo(purchaseOrderNo)
                .status(OwnershipStatus.ACTIVE)
                .tenantId(1L)
                .deleted(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        ownership = ownershipRepository.save(ownership);
        log.info("建立产权 assetId={} owner={} ownershipId={}", assetId, ownerUserId, ownership.getId());
        return ownership;
    }

    /**
     * 创建分成规则（资产入池时调用）。平台/保险比例固定，owner/station 由入池参数给定。
     *
     * @param assetId       资产 ID
     * @param poolEntryId   入池记录 ID
     * @param ownerRate     所有人分成比例
     * @param stationRate   站点分成比例
     * @return 分成规则 ID
     */
    @Transactional
    public Long createSplitRule(Long assetId, Long poolEntryId,
                                BigDecimal ownerRate, BigDecimal stationRate) {
        RevenueSplitRule rule = RevenueSplitRule.builder()
                .assetId(assetId)
                .poolEntryId(poolEntryId)
                .ownerRate(ownerRate)
                .stationRate(stationRate)
                .platformRate(BigDecimal.valueOf(0.10))
                .insuranceRate(BigDecimal.valueOf(0.05))
                .shareBasis(RevenueShareBasis.PER_SWAP)
                .status("ACTIVE")
                .tenantId(1L)
                .deleted(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        rule = splitRuleRepository.save(rule);
        log.info("创建分成规则 assetId={} poolEntryId={} ruleId={}", assetId, poolEntryId, rule.getId());
        return rule.getId();
    }

    /**
     * 创建分账结算（订单完成时调用）。首笔结算为对账起点，totalRevenue 默认 0，各份额 = 0 × rate。
     *
     * @param poolEntryId 入池记录 ID
     * @param periodStart 结算周期起
     * @param periodEnd   结算周期止
     * @return 结算记录（PENDING）
     */
    @Transactional
    public RevenueSettlement createSettlement(Long poolEntryId, Instant periodStart, Instant periodEnd) {
        String settlementNo = "SET-" + LocalDate.now().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        RevenueSettlement settlement = RevenueSettlement.builder()
                .settlementNo(settlementNo)
                .settlementDate(LocalDate.now())
                .poolEntryId(poolEntryId)
                .totalRevenue(totalRevenue)
                .ownerShare(totalRevenue.multiply(BigDecimal.valueOf(0.70)))
                .stationShare(totalRevenue.multiply(BigDecimal.valueOf(0.15)))
                .platformShare(totalRevenue.multiply(BigDecimal.valueOf(0.10)))
                .insuranceShare(totalRevenue.multiply(BigDecimal.valueOf(0.05)))
                .status(SettlementStatus.PENDING)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .tenantId(1L)
                .deleted(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        settlement = settlementRepository.save(settlement);
        log.info("创建分账结算 poolEntryId={} settlementNo={} id={}", poolEntryId, settlementNo, settlement.getId());
        return settlement;
    }
}
