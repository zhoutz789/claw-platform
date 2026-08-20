package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.CountryContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 站点服务：附近站点现货（客户选购入口）+ 投放现货（投资者认购入口）。
 *
 * <p>对接周老板验收口径：
 * <ul>
 *   <li>客户搜车型 → 找附近有该 SKU 现货的站点；或逛附近站点挑现货；</li>
 *   <li>投资者认购车辆后投放站点成为现货（智能分配/指定站点）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StationService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final StationRepository stationRepository;
    private final StationStockRepository stockRepository;
    private final StationBatteryRepository batteryRepository;

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
