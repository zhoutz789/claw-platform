package com.claw.server.domain.credit;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CreditScope;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryRepository;
import com.claw.server.domain.manufacturer.ProductSku;
import com.claw.server.domain.manufacturer.ProductSkuRepository;
import com.claw.server.domain.onboarding.OnboardingCreditBlock;
import com.claw.server.domain.onboarding.OnboardingCreditBlockService;
import com.claw.server.domain.onboarding.OnboardingDepositTier;
import com.claw.server.domain.onboarding.OnboardingDepositTierRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.claw.server.domain.station.Station;
import com.claw.server.domain.station.StationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 授信额度校验服务（增量 C · §4，Q2 / Q2b 落地）。
 *
 * <p><b>额度语义</b>：该主体当前持有的寄售设备<b>名义货值总和</b>上限（非现金授信、非贷款）。
 * 占用口径为 {@code inventory} 中 {@code holder_station_id = ? AND ownership_type = 'CONSIGNED'
 * AND current_status IN ('IN_TRANSIT','AT_STATION')} 的 {@code SUM(unit_value)}。
 *
 * <p><b>倍率禁止硬编码</b>：额度计算的唯一真源是 {@link #resolveTierCreditLimit}，
 * 取值顺序为 {@code credit_limit_override} → {@code deposit_amount × credit_multiplier}；
 * 新建档位时的默认倍率来自 {@code system_config.ONBOARDING_CREDIT_MULTIPLIER_DEFAULT}。
 *
 * <p><b>失败关闭原则</b>：入站时若货值无法解析（{@code unit_value} 为 NULL），
 * 抛 {@code inventory.unit_value.required} 拒绝入站，<b>不静默按 0 处理</b>。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditLimitService {

    /** 参与额度计算的库存状态：IN_TRANSIT = 在途；AT_STATION = 在库 + 待取货。 */
    private static final List<LifecycleStatus> CREDIT_STATUSES =
            List.of(LifecycleStatus.IN_TRANSIT, LifecycleStatus.AT_STATION);

    private final InventoryRepository inventoryRepository;
    private final StationRepository stationRepository;
    private final ProductSkuRepository productSkuRepository;
    /** 阻断留痕走独立事务（REQUIRES_NEW），不随业务回滚被抹掉。 */
    private final OnboardingCreditBlockService creditBlockService;
    private final OnboardingDepositTierRepository tierRepository;
    private final SystemConfigRepository systemConfigRepository;

    /* ------------------------------------------------------------------ */
    /* 额度计算（唯一真源）                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * 档位 → 有效授信额度。
     *
     * <pre>
     * effectiveCreditLimit(tier) = tier.credit_limit_override != null
     *                              ? tier.credit_limit_override
     *                              : tier.deposit_amount × tier.credit_multiplier
     * </pre>
     *
     * @param tier 保证金档位
     * @return 有效额度（保留 2 位，HALF_UP）
     */
    public BigDecimal resolveTierCreditLimit(OnboardingDepositTier tier) {
        if (tier == null) {
            return null;
        }
        BigDecimal amount = nz(tier.getDepositAmount());
        BigDecimal multiplier = tier.getCreditMultiplier() == null
                ? defaultMultiplier()
                : tier.getCreditMultiplier();
        BigDecimal raw = tier.getCreditLimitOverride() != null
                ? tier.getCreditLimitOverride()
                : amount.multiply(multiplier);
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 新建档位时倍率的默认值（取自 system_config，不硬编码）。
     *
     * @return ONBOARDING_CREDIT_MULTIPLIER_DEFAULT；未配置或非法时回退 3（仅限兜底，正常由配置提供）
     */
    public BigDecimal defaultMultiplier() {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse("ONBOARDING_CREDIT_MULTIPLIER_DEFAULT")
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> {
                    try {
                        return new BigDecimal(v.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(v -> v != null && v.signum() > 0)
                .orElseGet(() -> {
                    log.warn("system_config.ONBOARDING_CREDIT_MULTIPLIER_DEFAULT 缺失或非法，回退兜底值 4（周老板 2026-09-06 倍率修正）");
                    return new BigDecimal("4");
                });
    }

    /**
     * 取服务站的授信额度（冗余自档位，零 join）。
     *
     * @return 额度；为 NULL 表示历史站点未走入驻流程 —— <b>放行不校验</b>，仅记 WARN 日志
     *         （Q18：不能因存量数据缺失而阻断既有业务）
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveStationCreditLimit(Long stationId) {
        return stationRepository.findById(stationId).map(Station::getCreditLimit).orElse(null);
    }

    /* ------------------------------------------------------------------ */
    /* 占用计算                                                            */
    /* ------------------------------------------------------------------ */

    /** 当前已占用货值（在途 + 在库寄售）。 */
    @Transactional(readOnly = true)
    public BigDecimal usedValue(Long stationId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Inventory inv : inventoryRepository.findByHolderStationIdAndOwnershipType(
                stationId, OwnershipType.CONSIGNED)) {
            if (inv.getCurrentStatus() != null && CREDIT_STATUSES.contains(inv.getCurrentStatus())) {
                sum = sum.add(nz(inv.getUnitValue()));
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** 当前占用中的设备数。 */
    @Transactional(readOnly = true)
    public int usedDeviceCount(Long stationId) {
        int count = 0;
        for (Inventory inv : inventoryRepository.findByHolderStationIdAndOwnershipType(
                stationId, OwnershipType.CONSIGNED)) {
            if (inv.getCurrentStatus() != null && CREDIT_STATUSES.contains(inv.getCurrentStatus())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 解析一批设备的入站货值（入站时点快照）。
     *
     * @param deviceIds 设备 ID 列表
     * @return 合计货值
     * @throws BizException 40942 inventory.unit_value.required —— 任一设备货值无法解析即拒绝，
     *                      不静默按 0 处理（失败关闭原则）
     */
    @Transactional(readOnly = true)
    public BigDecimal sumUnitValue(List<Long> deviceIds) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Long deviceId : deviceIds) {
            Inventory inv = inventoryRepository.findByDeviceId(deviceId)
                    .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
            BigDecimal v = resolveUnitValue(inv);
            if (v == null) {
                throw BizException.of(40942, "inventory.unit_value.required", String.valueOf(deviceId));
            }
            sum = sum.add(v);
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 解析单台设备的入站货值：优先用已落库的 {@code unit_value} 快照，
     * 缺失时按「该 product 下价格最低的在售 SKU 价」现算（与 V60 存量回填同一保守口径），
     * 仍无法解析则返回 null。
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveUnitValue(Inventory inv) {
        if (inv.getUnitValue() != null) {
            return inv.getUnitValue();
        }
        if (inv.getProductId() == null) {
            return null;
        }
        return productSkuRepository.findAll().stream()
                .filter(s -> inv.getProductId().equals(s.getProductId()))
                .filter(s -> "ACTIVE".equals(s.getStatus()))
                .filter(s -> !Boolean.TRUE.equals(s.getDeleted()))
                .map(ProductSku::getPrice)
                .filter(p -> p != null)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    /**
     * 入站时把货值快照写入库存行（之后不再变动）。
     *
     * @param inv       库存行
     * @param manualValue 手工指定的货值；为空时按 SKU 价现算
     * @return 写入的货值
     */
    @Transactional
    public BigDecimal stampUnitValue(Inventory inv, BigDecimal manualValue, String currency) {
        BigDecimal value = manualValue != null ? manualValue : resolveUnitValue(inv);
        if (value == null) {
            throw BizException.of(40942, "inventory.unit_value.required",
                    String.valueOf(inv.getDeviceId()));
        }
        inv.setUnitValue(value.setScale(2, RoundingMode.HALF_UP));
        inv.setValueCurrency(currency == null || currency.isBlank() ? "USD" : currency);
        inv.setUnitValueSource(manualValue != null ? "MANUAL" : "SKU_PRICE");
        return inv.getUnitValue();
    }

    /* ------------------------------------------------------------------ */
    /* 校验写入点                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 硬阻断校验（C1 铺货入站 / C2 调拨入站 / C3 履约发货入站）。
     *
     * <p>超额时先落 {@code onboarding_credit_blocks} 风控留痕，再抛 40941。
     *
     * @param stationId 目标服务站
     * @param deviceIds 本次入站设备
     * @param scene     阻断场景
     * @param bizRefType 业务单据类型（TRANSFER / FULFILLMENT / CONSIGNMENT）
     * @param bizRefId  业务单据 ID
     * @throws BizException 40941 credit.limit.exceeded
     */
    @Transactional
    public void assertWithinLimit(Long stationId, List<Long> deviceIds,
                                  OnboardingCreditBlock.Scene scene,
                                  String bizRefType, Long bizRefId) {
        if (stationId == null || deviceIds == null || deviceIds.isEmpty()) {
            return;
        }
        BigDecimal used = usedValue(stationId);
        BigDecimal incoming = sumUnitValue(deviceIds);
        BigDecimal limit = resolveStationCreditLimit(stationId);
        if (limit == null) {
            // Q18 降级：历史站点未设档位 → 放行不校验，仅记 WARN
            log.warn("服务站 {} 未设授信额度（credit_limit IS NULL，历史站点未走入驻流程），本次入站放行不校验。"
                    + "本次货值 {}，建议由运营补录档位", stationId, incoming);
            return;
        }
        BigDecimal total = used.add(incoming);
        if (total.compareTo(limit) > 0) {
            BigDecimal overflow = total.subtract(limit);
            recordBlock(stationId, scene, bizRefType, bizRefId, deviceIds.size(), incoming, used, limit, overflow);
            throw BizException.of(40941, "credit.limit.exceeded",
                    limit.toPlainString(), used.toPlainString(), incoming.toPlainString(), overflow.toPlainString());
        }
        if (shouldWarn(used.add(incoming), limit)) {
            log.warn("服务站 {} 授信占用已达告警线：已用 {}/{}", stationId, used.add(incoming), limit);
        }
    }

    /** 简化重载（无业务单据上下文）。 */
    @Transactional
    public void assertWithinLimit(Long stationId, List<Long> deviceIds, OnboardingCreditBlock.Scene scene) {
        assertWithinLimit(stationId, deviceIds, scene, null, null);
    }

    /**
     * 软预检（C4 建调拨单）：<b>只返回告警，不阻断建单</b>。
     *
     * <p>理由：建单时在途状态未定，硬阻断会误伤正常业务。告警写入阻断记录留痕，
     * 真正的硬校验在目标站收货（C2）时做。
     *
     * @return 超限提示文案；未超限时返回 empty
     */
    @Transactional
    public Optional<String> softPrecheck(Long stationId, List<Long> deviceIds, Long transferId) {
        if (stationId == null || deviceIds == null || deviceIds.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal limit = resolveStationCreditLimit(stationId);
        if (limit == null) {
            return Optional.empty();
        }
        BigDecimal used = usedValue(stationId);
        BigDecimal incoming;
        try {
            incoming = sumUnitValue(deviceIds);
        } catch (BizException e) {
            // 软预检不因货值缺失阻断建单，只提示
            return Optional.of("部分设备货值未解析，收货时将做硬性校验");
        }
        BigDecimal total = used.add(incoming);
        if (total.compareTo(limit) <= 0) {
            return Optional.empty();
        }
        BigDecimal overflow = total.subtract(limit);
        recordBlock(stationId, OnboardingCreditBlock.Scene.TRANSFER_CREATE, "TRANSFER", transferId,
                deviceIds.size(), incoming, used, limit, overflow);
        return Optional.of(buildOverflowMessage(limit, used, incoming, overflow));
    }

    /** 组织管理页用：额度占用详情（已用 / 上限 / 占用率）。 */
    @Transactional(readOnly = true)
    public CreditUsageView creditUsage(Long stationId) {
        BigDecimal used = usedValue(stationId);
        BigDecimal limit = resolveStationCreditLimit(stationId);
        int count = usedDeviceCount(stationId);
        if (limit == null) {
            return new CreditUsageView(used, null, null, null, null, count, Boolean.FALSE, Boolean.FALSE);
        }
        BigDecimal ratio = limit.signum() == 0 ? BigDecimal.ONE
                : used.divide(limit, 4, RoundingMode.HALF_UP);
        boolean warn = ratio.compareTo(warnRatio()) >= 0;
        return new CreditUsageView(used, limit, ratio, tierCodeOf(stationId), tierNameOf(stationId),
                count, used.compareTo(limit) > 0, warn);
    }

    /** 超限提示文案（含缺口与三条可执行建议，前端 antd Modal.error 展示）。 */
    public String buildOverflowMessage(BigDecimal limit, BigDecimal used, BigDecimal incoming, BigDecimal overflow) {
        return "超出授信额度，无法入站。当前额度上限 " + limit.toPlainString() + " USD；"
                + "已占用 " + used.toPlainString() + " USD；本次入站 " + incoming.toPlainString() + " USD；"
                + "超出 " + overflow.toPlainString() + " USD。"
                + "建议：① 减少本次入站设备数量；② 引导该服务站升档补差价；③ 先发起回收释放占用。";
    }

    /** 额度口径（STATION / MFG_STATION，Q17 推荐默认 STATION）。 */
    @Transactional(readOnly = true)
    public CreditScope creditScope() {
        String v = systemConfigRepository.findByConfigKeyAndDeletedFalse("ONBOARDING_CREDIT_SCOPE")
                .map(SystemConfig::getConfigValue).orElse("STATION");
        try {
            return CreditScope.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CreditScope.STATION;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    private void recordBlock(Long stationId, OnboardingCreditBlock.Scene scene, String bizRefType, Long bizRefId,
                             int deviceCount, BigDecimal incoming, BigDecimal used, BigDecimal limit,
                             BigDecimal overflow) {
        creditBlockService.record("STATION", stationId, scene, bizRefType, bizRefId,
                deviceCount, incoming, used, limit, overflow);
    }

    private boolean shouldWarn(BigDecimal afterUsed, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0) {
            return false;
        }
        return afterUsed.divide(limit, 4, RoundingMode.HALF_UP).compareTo(warnRatio()) >= 0;
    }

    private BigDecimal warnRatio() {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse("ONBOARDING_CREDIT_WARN_RATIO")
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> {
                    try {
                        return new BigDecimal(v.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(v -> v != null && v.signum() > 0)
                .orElse(new BigDecimal("0.8"));
    }

    private String tierCodeOf(Long stationId) {
        return tierRepository(stationId).map(OnboardingDepositTier::getTierCode).orElse(null);
    }

    private String tierNameOf(Long stationId) {
        return tierRepository(stationId).map(OnboardingDepositTier::getTierName).orElse(null);
    }

    private Optional<OnboardingDepositTier> tierRepository(Long stationId) {
        Long tierId = stationRepository.findById(stationId).map(Station::getDepositTierId).orElse(null);
        return tierId == null ? Optional.empty() : tierRepository.findById(tierId);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 批量设备入站前的空值快速体检（供测试与前端预检复用）。 */
    @Transactional(readOnly = true)
    public List<Long> devicesMissingUnitValue(List<Long> deviceIds) {
        List<Long> missing = new ArrayList<>();
        for (Long deviceId : deviceIds) {
            inventoryRepository.findByDeviceId(deviceId).ifPresent(inv -> {
                if (resolveUnitValue(inv) == null) {
                    missing.add(deviceId);
                }
            });
        }
        return missing;
    }
}
