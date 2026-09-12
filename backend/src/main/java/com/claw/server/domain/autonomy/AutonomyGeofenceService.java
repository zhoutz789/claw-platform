package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.GeofenceLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.List;

/**
 * 地面围栏约束服务（AU4）：校验车辆是否位于作业区内且未进入禁行区。
 * 围栏 polygonJson 为 JSON 数组，每项为 [lng,lat] 或 [lat,lng]（按绝对值自动识别）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutonomyGeofenceService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GroundGeofenceRepository geofenceRepository;
    private final AutonomySafetyService safetyService;

    /**
     * 校验资产位置：必须落在某个 WORK 围栏内，且不在任何 NO_GO 围栏内。
     */
    @Transactional(readOnly = true)
    public void assertInsideGeofence(Long assetId, double lat, double lng) {
        List<GroundGeofence> all = geofenceRepository.findAll();
        List<GroundGeofence> work = all.stream()
                .filter(g -> g.getLevel() == GeofenceLevel.WORK)
                .toList();
        List<GroundGeofence> noGo = all.stream()
                .filter(g -> g.getLevel() == GeofenceLevel.NO_GO)
                .toList();

        if (work.isEmpty()) {
            throw new IllegalArgumentException("geofence.no.work.zone");
        }
        boolean inNoGo = noGo.stream().anyMatch(g -> pointInPolygon(g.getPolygonJson(), lat, lng));
        if (inNoGo) {
            throw new IllegalArgumentException("geofence.no.go");
        }
        boolean inWork = work.stream().anyMatch(g -> pointInPolygon(g.getPolygonJson(), lat, lng));
        if (!inWork) {
            throw new IllegalArgumentException("geofence.outside.work");
        }
    }

    /**
     * AU4 校验整条路径是否全部落在作业区内且未进入禁行区。
     * 任一点越界即记录 GEOFENCE 安全事件（锁机）并抛出，类比无人机禁飞。
     *
     * @return true 当所有点均合规
     * @throws IllegalArgumentException 当任一路径点越界
     */
    @Transactional(readOnly = true)
    public boolean validatePathInGeofence(Long assetId, String pathJson) {
        List<double[]> points = parseLngLatPath(pathJson);
        for (double[] pt : points) {
            double lng = pt[0];
            double lat = pt[1];
            try {
                assertInsideGeofence(assetId, lat, lng);
            } catch (IllegalArgumentException ex) {
                safetyService.triggerSafetyEvent(assetId, "GEOFENCE", "CRITICAL",
                        String.format("{\"reason\":\"path.out.of.bounds\",\"lng\":%s,\"lat\":%s}", lng, lat));
                throw new IllegalArgumentException("geofence.path.out.of.bounds:" + ex.getMessage());
            }
        }
        return true;
    }

    /** 解析路径 JSON（数组，每项为 [lng,lat] 或 [lat,lng]，按首值绝对值>90 识别经度序）。 */
    private List<double[]> parseLngLatPath(String pathJson) {
        List<double[]> points = new ArrayList<>();
        if (pathJson == null || pathJson.isBlank()) {
            return points;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(pathJson);
            for (JsonNode pt : root) {
                if (pt.size() < 2) {
                    continue;
                }
                double a = pt.get(0).asDouble();
                double b = pt.get(1).asDouble();
                boolean firstIsLng = Math.abs(a) > 90.0;
                points.add(new double[]{firstIsLng ? a : b, firstIsLng ? b : a});
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse autonomy path json: {}", pathJson, e);
        }
        return points;
    }

    public List<GroundGeofence> listGeofences() {
        return geofenceRepository.findAll();
    }

    /**
     * 射线法判断点是否在多边形内。自动识别坐标序：[lng,lat]（首个绝对值>90 视为经度）或 [lat,lng]。
     */
    private boolean pointInPolygon(String polygonJson, double lat, double lng) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(polygonJson);
            List<double[]> ring = new ArrayList<>();
            for (JsonNode pt : root) {
                if (pt.size() < 2) {
                    continue;
                }
                double a = pt.get(0).asDouble();
                double b = pt.get(1).asDouble();
                boolean firstIsLng = Math.abs(a) > 90.0;
                double x = firstIsLng ? a : b; // lng
                double y = firstIsLng ? b : a; // lat
                ring.add(new double[]{x, y});
            }
            return rayCast(ring, lng, lat);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse geofence polygon json: {}", polygonJson, e);
            return false;
        }
    }

    private boolean rayCast(List<double[]> ring, double x, double y) {
        int n = ring.size();
        if (n < 3) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i)[0];
            double yi = ring.get(i)[1];
            double xj = ring.get(j)[0];
            double yj = ring.get(j)[1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }
}
