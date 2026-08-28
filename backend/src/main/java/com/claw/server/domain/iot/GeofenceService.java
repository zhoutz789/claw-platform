package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.GeofenceDtos.CreateGeofenceReq;
import com.claw.server.common.dto.GeofenceDtos.GeofenceBreachView;
import com.claw.server.common.dto.GeofenceDtos.GeofenceView;
import com.claw.server.common.dto.GeofenceDtos.UpdateGeofenceReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 通用电子围栏服务：围栏 CRUD + 越界判定。
 *
 * <p>越界语义：围栏定义为"受限区域"，点位于区域内即视为越界（breached=true）。
 * <ul>
 *   <li>RADIUS：点距中心 ≤ radius_m → 越界（Haversine 米制距离）。</li>
 *   <li>POLYGON：解析 WKT POLYGON((lng lat, ...))，射线法点在多边形内判定；
 *       解析失败/不支持时优雅返回 breached=false 且 reason=POLYGON_NOT_SUPPORTED，不阻塞。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeofenceService {

    private static final Set<String> ALLOWED_FENCE_TYPES = Set.of("RADIUS", "POLYGON");
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private final GeofenceRepository geofenceRepository;

    @Transactional
    public GeofenceView create(CreateGeofenceReq req) {
        if (req.ownerType() == null || req.ownerId() == null || req.fenceType() == null) {
            throw BizException.invalidParam("error.geofence.required");
        }
        if (!ALLOWED_FENCE_TYPES.contains(req.fenceType())) {
            throw BizException.invalidParam("error.geofence.type.invalid", req.fenceType());
        }
        if ("RADIUS".equals(req.fenceType())
                && (req.centerLat() == null || req.centerLng() == null || req.radiusM() == null)) {
            throw BizException.invalidParam("error.geofence.radius.incomplete");
        }
        if ("POLYGON".equals(req.fenceType()) && (req.polygonWkt() == null || req.polygonWkt().isBlank())) {
            throw BizException.invalidParam("error.geofence.polygon.incomplete");
        }
        Geofence entity = Geofence.builder()
                .ownerType(req.ownerType())
                .ownerId(req.ownerId())
                .fenceType(req.fenceType())
                .centerLat(req.centerLat())
                .centerLng(req.centerLng())
                .radiusM(req.radiusM())
                .polygonWkt(req.polygonWkt())
                .triggerAction(req.triggerAction() != null ? req.triggerAction() : "ALERT")
                .status("ENABLED")
                .build();
        return toView(geofenceRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<GeofenceView> listByOwner(String ownerType, Long ownerId) {
        return geofenceRepository.findByOwnerTypeAndOwnerId(ownerType, ownerId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public GeofenceView get(Long id) {
        return toView(load(id));
    }

    @Transactional
    public GeofenceView update(Long id, UpdateGeofenceReq req) {
        Geofence entity = load(id);
        if (req.ownerType() != null) {
            entity.setOwnerType(req.ownerType());
        }
        if (req.ownerId() != null) {
            entity.setOwnerId(req.ownerId());
        }
        if (req.fenceType() != null) {
            if (!ALLOWED_FENCE_TYPES.contains(req.fenceType())) {
                throw BizException.invalidParam("error.geofence.type.invalid", req.fenceType());
            }
            entity.setFenceType(req.fenceType());
        }
        if (req.centerLat() != null) {
            entity.setCenterLat(req.centerLat());
        }
        if (req.centerLng() != null) {
            entity.setCenterLng(req.centerLng());
        }
        if (req.radiusM() != null) {
            entity.setRadiusM(req.radiusM());
        }
        if (req.polygonWkt() != null) {
            entity.setPolygonWkt(req.polygonWkt());
        }
        if (req.triggerAction() != null) {
            entity.setTriggerAction(req.triggerAction());
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        return toView(geofenceRepository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        if (!geofenceRepository.existsById(id)) {
            throw BizException.notFound("error.geofence.not.found");
        }
        geofenceRepository.deleteById(id);
    }

    /**
     * 越界判定：对属主的全部围栏逐一判定。
     *
     * @param ownerType 属主类型（PRODUCT/DEVICE/ASSET/PROJECT）
     * @param ownerId   属主 id
     * @param lat       待判定点纬度
     * @param lng       待判定点经度
     * @return 每个围栏的越界结果（仅含 ENABLE 状态围栏，DISABLED 跳过）
     */
    @Transactional(readOnly = true)
    public List<GeofenceBreachView> checkBreach(String ownerType, Long ownerId, BigDecimal lat, BigDecimal lng) {
        List<Geofence> fences = geofenceRepository.findByOwnerTypeAndOwnerIdAndStatus(ownerType, ownerId, "ENABLED");
        List<GeofenceBreachView> result = new ArrayList<>();
        for (Geofence f : fences) {
            result.add(evaluate(f, lat, lng));
        }
        return result;
    }

    private GeofenceBreachView evaluate(Geofence f, BigDecimal lat, BigDecimal lng) {
        boolean breached;
        String reason;
        Double distanceMeters = null;
        if ("RADIUS".equals(f.getFenceType())) {
            double d = haversineMeters(f.getCenterLat(), f.getCenterLng(), lat, lng);
            distanceMeters = d;
            breached = f.getRadiusM() != null && d <= f.getRadiusM();
            reason = breached ? "INSIDE_RADIUS" : "OUTSIDE_RADIUS";
        } else if ("POLYGON".equals(f.getFenceType())) {
            try {
                boolean inside = pointInPolygon(f.getPolygonWkt(), lng, lat);
                breached = inside;
                reason = inside ? "INSIDE_POLYGON" : "OUTSIDE_POLYGON";
            } catch (RuntimeException ex) {
                log.debug("围栏 {} POLYGON 解析失败，跳过越界判定: {}", f.getId(), ex.getMessage());
                breached = false;
                reason = "POLYGON_NOT_SUPPORTED";
            }
        } else {
            breached = false;
            reason = "UNSUPPORTED_FENCE_TYPE";
        }
        return new GeofenceBreachView(f.getId(), f.getOwnerType(), f.getOwnerId(),
                breached, reason, lat, lng, distanceMeters);
    }

    /** Haversine 大圆距离（米）。 */
    private double haversineMeters(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return Double.MAX_VALUE;
        }
        double la1 = Math.toRadians(lat1.doubleValue());
        double la2 = Math.toRadians(lat2.doubleValue());
        double dLa = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLo = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(la1) * Math.cos(la2) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    /** 解析 WKT POLYGON((lng lat, ...))，射线法判定点(lng,lat)是否在多边形内。 */
    private boolean pointInPolygon(String wkt, BigDecimal lng, BigDecimal lat) {
        if (wkt == null || lng == null || lat == null) {
            throw new IllegalArgumentException("empty wkt or point");
        }
        String body = wkt.trim();
        int firstParen = body.indexOf('(');
        int lastParen = body.lastIndexOf(')');
        if (firstParen < 0 || lastParen < 0 || lastParen <= firstParen) {
            throw new IllegalArgumentException("invalid WKT: " + wkt);
        }
        String inner = body.substring(firstParen + 1, lastParen);
        // 支持 POLYGON((...)) 或 POLYGON(()) 嵌套
        inner = inner.replaceAll("^\\(", "").replaceAll("\\)$", "");
        String[] pairs = inner.split(",");
        List<double[]> ring = new ArrayList<>();
        for (String pair : pairs) {
            String[] xy = pair.trim().split("\\s+");
            if (xy.length < 2) {
                throw new IllegalArgumentException("invalid ring point: " + pair);
            }
            double x = Double.parseDouble(xy[0].trim()); // lng
            double y = Double.parseDouble(xy[1].trim()); // lat
            ring.add(new double[]{x, y});
        }
        if (ring.size() < 3) {
            throw new IllegalArgumentException("polygon needs >= 3 points");
        }
        double px = lng.doubleValue();
        double py = lat.doubleValue();
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i)[0], yi = ring.get(i)[1];
            double xj = ring.get(j)[0], yj = ring.get(j)[1];
            boolean intersect = ((yi > py) != (yj > py))
                    && (px < (xj - xi) * (py - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    private Geofence load(Long id) {
        return geofenceRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.geofence.not.found"));
    }

    private GeofenceView toView(Geofence e) {
        return new GeofenceView(e.getId(), e.getOwnerType(), e.getOwnerId(), e.getFenceType(),
                e.getCenterLat(), e.getCenterLng(), e.getRadiusM(), e.getPolygonWkt(),
                e.getTriggerAction(), e.getStatus(), e.getCreatedAt());
    }
}
