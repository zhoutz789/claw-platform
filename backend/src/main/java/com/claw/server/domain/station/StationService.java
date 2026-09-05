package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.CountryContext;
import com.claw.server.domain.contract.ContractService;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.onboarding.OnboardingDeposit;
import com.claw.server.domain.onboarding.OnboardingDepositRepository;
import com.claw.server.domain.onboarding.OnboardingDepositTier;
import com.claw.server.domain.onboarding.OnboardingDepositTierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 站点服务：附近站点现货（客户选购入口）+ 投放现货（投资者认购入口）。
 *
 * <p>对接周老板验收口径：
 * <ul>
 *   <li>客户搜车型 → 找附近有该 SKU 现货的站点；或逛附近站点挑现货；</li>
 *   <li>资产投放至站点成为现货（智能分配/指定站点）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StationService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final StationRepository stationRepository;
    private final StationStockRepository stockRepository;
    private final StationBatteryRepository batteryRepository;
    private final OnboardingDepositTierRepository tierRepository;
    private final OnboardingDepositRepository depositRepository;
    private final CreditLimitService creditLimitService;
    private final ContractService contractService;

    /**
     * 后台站点全量列表（管理端下拉选项数据源）。
     *
     * <p>与 {@link #nearby} 的区别：nearby 按 CountryContext 过滤当前国家且只返回 ACTIVE 站点，
     * 管理端需要跨国家、含停用站点地全量列出，否则「调拨/履约」等页面选不到目标站。
     * 不传坐标，distKm 为 null（管理端不需要距离）。
     */
    @Transactional(readOnly = true)
    public List<StationViews.StationView> listAll() {
        return stationRepository.findAll().stream()
                .filter(s -> !Boolean.TRUE.equals(s.getDeleted()))
                .map(s -> toView(s, null, null))
                .sorted(Comparator.comparingLong(v -> v.id() == null ? Long.MAX_VALUE : v.id()))
                .toList();
    }

    /** 附近站点（按距离由近到远，最多 limit 个），含现货摘要。 */
    @Transactional(readOnly = true)
    public List<StationViews.StationView> nearby(String countryCode, BigDecimal lat, BigDecimal lng, Integer limit) {
        String cc = countryCode != null ? countryCode : CountryContext.countryCode();
        List<Station> stations = stationRepository
                .findByCountryCodeAndStatusAndDeletedFalse(cc, "ACTIVE");
        int n = limit != null ? Math.min(limit, stations.size()) : stations.size();

        return stations.stream()
                .map(s -> toView(s, lat, lng))
                .sorted(Comparator.comparing(v -> v.distKm() == null ? Double.MAX_VALUE : v.distKm().doubleValue()))
                .limit(n)
                .toList();
    }

    /** 搜车型：返回附近站点中该 SKU 有现货的站点列表（含可用量）。 */
    @Transactional(readOnly = true)
    public List<StationViews.SkuHitView> findStationsWithSku(String countryCode, String skuCode,
                                                             BigDecimal lat, BigDecimal lng, Integer limit) {
        List<Station> stations = stationRepository
                .findByCountryCodeAndStatusAndDeletedFalse(
                        countryCode != null ? countryCode : CountryContext.countryCode(), "ACTIVE");
        List<StationViews.SkuHitView> hits = new ArrayList<>();
        for (Station s : stations) {
            stockRepository.findByStationIdAndSkuCode(s.getId(), skuCode)
                    .filter(st -> st.getStockQty() > 0)
                    .ifPresent(st -> hits.add(new StationViews.SkuHitView(
                            toView(s, lat, lng), skuCode, st.getStockQty())));
        }
        hits.sort(Comparator.comparing(h -> h.station().distKm() == null
                ? Double.MAX_VALUE : h.station().distKm().doubleValue()));
        int n = limit != null ? Math.min(limit, hits.size()) : hits.size();
        return hits.subList(0, n);
    }

    /** 站内现货列表。 */
    @Transactional(readOnly = true)
    public List<StationViews.StockView> stockOf(Long stationId) {
        return stockRepository.findByStationIdOrderBySkuCode(stationId).stream()
                .map(st -> new StationViews.StockView(st.getId(), st.getStationId(),
                        st.getSkuCode(), st.getStockQty(), st.getUpdatedAt()))
                .toList();
    }

    /** 投放现货（投资者认购入站）：已存在则累加库存。 */
    @Transactional
    public StationViews.StockView stockIn(Long stationId, String skuCode, int qty) {
        if (!stationRepository.existsById(stationId)) {
            throw BizException.notFound("error.station.not.found");
        }
        StationStock stock = stockRepository.findByStationIdAndSkuCode(stationId, skuCode)
                .orElseGet(() -> StationStock.builder()
                        .stationId(stationId).skuCode(skuCode).stockQty(0).build());
        stock.setStockQty(stock.getStockQty() + qty);
        stock.setUpdatedAt(Instant.now());
        StationStock saved = stockRepository.save(stock);
        return new StationViews.StockView(saved.getId(), saved.getStationId(),
                saved.getSkuCode(), saved.getStockQty(), saved.getUpdatedAt());
    }

    /** 地图适配层：附近换电站 + 电池供给（满电/充电中），S3 换电入口数据源。 */
    @Transactional(readOnly = true)
    public List<StationViews.MapView> mapView(String countryCode, BigDecimal lat, BigDecimal lng, Integer limit) {
        String cc = countryCode != null ? countryCode : CountryContext.countryCode();
        List<Station> stations = stationRepository
                .findByCountryCodeAndStatusAndDeletedFalse(cc, "ACTIVE");
        int n = limit != null ? Math.min(limit, stations.size()) : stations.size();

        return stations.stream()
                .map(s -> new StationViews.MapView(
                        toView(s, lat, lng),
                        batteryRepository.countByStationIdAndStatus(s.getId(), "READY"),
                        batteryRepository.countByStationIdAndStatus(s.getId(), "CHARGING")))
                .sorted(Comparator.comparing(v -> v.station().distKm() == null
                        ? Double.MAX_VALUE : v.station().distKm().doubleValue()))
                .limit(n)
                .toList();
    }

    /**
     * 追加保证金升档（缺口① · 周老板 2026-09-06 拍板）。
     *
     * <p>服务站追加保证金 → 选择更高档位 → 授信额度按「新档位保证金 × 4」放大（项目随之扩大、可承载寄售上限增加）；
     * 旧合约续签（RENEWED 旧约 + 新 ACTIVE 3 年期），并把追加的保证金记入 {@code onboarding_deposits}（CONFIRMED）。
     *
     * <p>校验：① 新档位必须启用；② 新档位保证金必须严格大于当前档位（升档语义）。降档/平档一律拒绝。
     *
     * @param stationId 服务站 ID
     * @param newTierId 目标更高档位 ID
     * @param operatorId 操作人（平台运营，追加保证金由其确认收讫后触发升档）
     * @return 升档结果（新档位 / 追加金额 / 新授信 / 新合约号）
     */
    @Transactional
    public StationUpgradeResult upgradeTier(Long stationId, Long newTierId, Long operatorId) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> BizException.of(40401, "station.not.found"));
        OnboardingDepositTier curTier = tierRepository.findById(station.getDepositTierId())
                .orElseThrow(() -> BizException.of(40401, "onboarding.deposit.tier.not.found"));
        OnboardingDepositTier newTier = tierRepository.findById(newTierId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.deposit.tier.not.found"));
        if (!Boolean.TRUE.equals(newTier.getEnabled())) {
            throw BizException.of(40940, "onboarding.deposit.tier.disabled");
        }
        if (newTier.getDepositAmount().compareTo(curTier.getDepositAmount()) <= 0) {
            throw BizException.of(40940, "onboarding.upgrade.tier.not.higher");
        }
        BigDecimal newCredit = creditLimitService.resolveTierCreditLimit(newTier);
        BigDecimal additional = newTier.getDepositAmount().subtract(curTier.getDepositAmount());

        Instant now = Instant.now();
        // 记录追加保证金（运营已确认收讫，状态置 CONFIRMED 便于审计）
        Long appId = station.getOnboardingApplicationId() != null ? station.getOnboardingApplicationId() : 0L;
        if (station.getOnboardingApplicationId() == null) {
            log.warn("服务站 {} 升档：onboarding_application_id 为空，追加保证金台账 application_id 暂填 0，建议补录", stationId);
        }
        depositRepository.save(OnboardingDeposit.builder()
                .depositNo(genDepositNo())
                .applicationId(appId)
                .principalType("STATION")
                .principalId(stationId)
                .tierId(newTierId)
                .amount(additional)
                .currency("USD")
                .payMethod("OFFLINE_TRANSFER")
                .status(OnboardingDeposit.Status.CONFIRMED.name())
                .confirmedBy(operatorId)
                .confirmedAt(now)
                .createdAt(now).updatedAt(now)
                .build());

        // 升档：写回档位与授信额度（项目扩大）
        station.setDepositTierId(newTierId);
        station.setCreditLimit(newCredit);
        station.setUpdatedAt(now);
        stationRepository.save(station);

        // 合约续签：旧约 RENEWED，新约 ACTIVE 3 年
        String newContractNo = contractService.renewOnUpgrade(
                stationId, newTierId, newTier.getDepositAmount(), newCredit, operatorId).getContractNo();

        log.info("服务站 {} 升档成功：新档位 {} 追加 {} 新授信 {} 新合约 {}",
                stationId, newTierId, additional, newCredit, newContractNo);
        return new StationUpgradeResult(stationId, newTierId, additional, newCredit, newContractNo);
    }

    /** 升档结果（内部 DTO）。 */
    public record StationUpgradeResult(Long stationId, Long newTierId, BigDecimal additionalDeposit,
                                       BigDecimal newCreditLimit, String newContractNo) {}

    /** 生成追加保证金台账号 DEP{yyyyMMdd}{4位序号}。 */
    private String genDepositNo() {
        return "DEP" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private StationViews.StationView toView(Station s, BigDecimal lat, BigDecimal lng) {
        List<StationStock> stocks = stockRepository.findByStationIdOrderBySkuCode(s.getId());
        int total = stocks.stream().mapToInt(StationStock::getStockQty).sum();
        List<String> cats = stocks.stream()
                .filter(st -> st.getStockQty() > 0)
                .map(StationStock::getSkuCode)
                .toList();
        return new StationViews.StationView(s.getId(), s.getCode(), s.getName(), s.getArea(),
                s.getProvince(), s.getCity(), s.getDistrict(), s.getCountryCode(),
                s.getOpenHours(), distKm(s, lat, lng), total, cats);
    }

    /** Haversine 近似距离（km，一位小数）。未提供客户坐标返回 null。 */
    private BigDecimal distKm(Station s, BigDecimal lat, BigDecimal lng) {
        if (lat == null || lng == null || s.getLat() == null || s.getLng() == null) {
            return null;
        }
        double dLat = Math.toRadians(s.getLat().doubleValue() - lat.doubleValue());
        double dLng = Math.toRadians(s.getLng().doubleValue() - lng.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat.doubleValue())) * Math.cos(Math.toRadians(s.getLat().doubleValue()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(EARTH_RADIUS_KM * c).setScale(1, RoundingMode.HALF_UP);
    }
}
