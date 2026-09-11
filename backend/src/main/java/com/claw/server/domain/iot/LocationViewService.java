package com.claw.server.domain.iot;

import com.claw.server.common.dto.LocationDtos.ProductLocationView;
import com.claw.server.common.dto.LocationDtos.TrackPointView;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 位置视图服务：产品聚合位置（派生）+ 历史轨迹回放。
 *
 * <p>复用既有 tracks / telemetry_latest，不自建冗余定位表（技术文档 §1.3② / §7.3）。
 * <ul>
 *   <li>产品聚合位置：该产品下所有资产中"最新上报"的那台的 telemetry_latest 聚合点 + 来源 asset。</li>
 *   <li>轨迹回放：按 assetId + 时间区间查 tracks（本期原样返回，未引入 TrackSimplifier 抽稀）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LocationViewService {

    private final AssetRepository assetRepository;
    private final TelemetryLatestRepository telemetryLatestRepository;
    private final TrackRepository trackRepository;

    /**
     * 产品聚合位置：取该产品下最新上报资产的实时坐标。
     *
     * @param productId 产品 id
     * @return 聚合位置视图（无上报数据时 assetId/lat/lng 为 null）
     */
    @Transactional(readOnly = true)
    public ProductLocationView getProductLocation(Long productId) {
        List<Asset> assets = assetRepository.findByProductId(productId);
        TelemetryLatest best = null;
        for (Asset asset : assets) {
            // 每个资产取其多设备中上报时间最新的一条，再在资产之间比较取最"新上报"的那台。
            TelemetryLatest t = telemetryLatestRepository
                    .findTopByAssetIdOrderByReportedAtDescIdDesc(asset.getId()).orElse(null);
            if (t != null && (best == null || t.getReportedAt().isAfter(best.getReportedAt()))) {
                best = t;
            }
        }
        if (best == null) {
            return new ProductLocationView(null, null, null, null, null);
        }
        final Long bestAssetId = best.getAssetId();
        String assetNo = assets.stream()
                .filter(a -> a.getId().equals(bestAssetId))
                .map(Asset::getAssetNo)
                .findFirst()
                .orElse(null);
        return new ProductLocationView(best.getAssetId(), assetNo,
                best.getLat(), best.getLng(), best.getReportedAt());
    }

    /**
     * 历史轨迹回放（按时间升序）。
     *
     * @param assetId 资产 id
     * @param from    起始时间（含）
     * @param to      结束时间（含）
     * @return 轨迹点列表 {ts, lat, lng, speed, soc}
     */
    @Transactional(readOnly = true)
    public List<TrackPointView> getTrack(Long assetId, Instant from, Instant to) {
        List<Track> tracks = trackRepository.findByAssetIdAndTsBetweenOrderByTsAsc(assetId, from, to);
        return tracks.stream().map(t -> new TrackPointView(
                t.getTs(), t.getLat(), t.getLng(), t.getSpeed(), t.getSoc())).toList();
    }
}
