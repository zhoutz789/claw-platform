package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.NfzLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 禁飞图层服务（V143/V144）：图层查询 / 启停 / 空间与时段命中评估。
 *
 * <p>多边形为 [[lng,lat], ...] 闭合环（<b>不重复首点</b>，环隐式闭合），判定用标准射线法；
 * 时段窗口 {@code {"daily":[{"start":"HH:mm","end":"HH:mm"}]}} 时间一律 <b>UTC</b>，
 * 支持跨零点（start &gt; end 视为跨午夜窗口）。
 *
 * <p><b>安全语义</b>：{@code source='BAKED-IN'} 的图层（机场/王宫/遗产区）为航空安全固有约束，
 * {@link #setEnabled} 显式拒绝关闭，命中判定也<b>不看</b> {@code enabled} —— 关不掉的安全底线
 * 只能由代码删除（如监管正式调整后再改码），配置层只能放开运营限制层。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NfzService {

    private final NfzLayerRepository nfzLayerRepository;
    private final ObjectMapper objectMapper;

    /** 图层列表（按 id 升序，管理端展示）。 */
    @Transactional(readOnly = true)
    public List<NfzLayer> list() {
        return nfzLayerRepository.findAllByOrderByIdAsc();
    }

    /**
     * 启停图层（仅运营限制层可关；BAKED-IN 恒定拒绝关闭）。
     *
     * @param id      图层 id
     * @param enabled 目标状态
     * @return 更新后的图层
     * @throws BizException 40471 error.drone.nfz.not.found（图层不存在）
     * @throws BizException 40967 error.drone.nfz.baked.in（固有安全图层不可修改）
     */
    @Transactional
    public NfzLayer setEnabled(Long id, boolean enabled) {
        NfzLayer layer = nfzLayerRepository.findById(id)
                .orElseThrow(() -> BizException.of(40471, "error.drone.nfz.not.found", id));
        if (NfzLayer.SOURCE_BAKED_IN.equals(layer.getSource())) {
            throw BizException.of(40967, "error.drone.nfz.baked.in", layer.getName());
        }
        layer.setEnabled(enabled);
        layer.setUpdatedAt(Instant.now());
        NfzLayer saved = nfzLayerRepository.save(layer);
        log.info("禁飞图层 {} enabled={}（配置层放开，不改码）", layer.getName(), enabled);
        return saved;
    }

    /**
     * 评估指定坐标命中的「固有安全约束」图层（{@code BAKED-IN}）。
     *
     * <p><b>不看 {@code enabled}</b>：这是航空安全底线（机场/王宫/遗产区），
     * 任何档位、任何启停操作都不能放行，命中即由 {@link PermitGate} 恒定拒绝。
     *
     * @param lng 经度
     * @param lat 纬度
     * @param at  评估时刻（时段窗口判定基准）
     * @return 命中的图层；无命中则 empty
     */
    @Transactional(readOnly = true)
    public Optional<NfzLayer> firstBakedInHit(double lng, double lat, Instant at) {
        for (NfzLayer layer : nfzLayerRepository.findBySource(NfzLayer.SOURCE_BAKED_IN)) {
            if (hits(layer, lng, lat, at)) {
                return Optional.of(layer);
            }
        }
        return Optional.empty();
    }

    /**
     * 评估指定坐标命中的「运营限制」图层（{@code enabled=true} 且非 BAKED-IN）。
     *
     * <p>受档位影响：STRICT 拒绝、ADVISORY 记告警放行、OFF 由 {@link PermitGate} 直接跳过。
     *
     * @param lng 经度
     * @param lat 纬度
     * @param at  评估时刻（时段窗口判定基准）
     * @return 命中的图层；无命中则 empty
     */
    @Transactional(readOnly = true)
    public Optional<NfzLayer> firstOperationalHit(double lng, double lat, Instant at) {
        for (NfzLayer layer : nfzLayerRepository.findByEnabledTrue()) {
            // 防御性双保险：仓储语义上只回 enabled=true，这里再显式跳过一次，
            // 避免未来仓储条件被改动后运营层「该关没关」；BAKED-IN 归 firstBakedInHit 恒定评估。
            if (!Boolean.TRUE.equals(layer.getEnabled())
                    || NfzLayer.SOURCE_BAKED_IN.equals(layer.getSource())) {
                continue;
            }
            if (hits(layer, lng, lat, at)) {
                return Optional.of(layer);
            }
        }
        return Optional.empty();
    }

    /** 图层是否命中该坐标/时刻（级别 + 多边形 + 时段窗口三重判定）。 */
    private boolean hits(NfzLayer layer, double lng, double lat, Instant at) {
        Optional<Boolean> spatial = containsPoint(layer, lng, lat);
        if (spatial.isEmpty()) {
            // 多边形缺失/不可解析：固有权重层宁可误拦不可漏拦，其余层跳过。
            if (NfzLayer.SOURCE_BAKED_IN.equals(layer.getSource())) {
                log.error("固有安全图层 {} 多边形不可解析，按命中处理（宁可误拦不可漏拦）", layer.getName());
                return true;
            }
            log.warn("图层 {} 多边形不可解析，本次评估跳过", layer.getName());
            return false;
        }
        if (!spatial.get()) {
            return false;
        }
        // TIME_WINDOW：仅窗口内拒绝；窗口数据缺失/不可解析按「恒在窗内」处理（保守）。
        if (layer.getLevel() == NfzLevel.TIME_WINDOW && !inTimeWindow(layer, at)) {
            return false;
        }
        return true;
    }

    /**
     * 点是否在图层多边形内（射线法）。
     *
     * @return true/false = 判定结果；empty = 多边形缺失或不可解析（无法判定）
     */
    public Optional<Boolean> containsPoint(NfzLayer layer, double lng, double lat) {
        List<double[]> ring = parseRing(layer.getPolygonJson());
        if (ring == null) {
            return Optional.empty();
        }
        return Optional.of(contains(ring, lng, lat));
    }

    /**
     * 当前时刻是否落在图层的时段窗口内（UTC）。
     *
     * <p>窗口缺失/不可解析返回 {@code true}（保守：视为恒在窗内）。
     */
    public boolean inTimeWindow(NfzLayer layer, Instant at) {
        if (layer.getTimeWindowJson() == null || layer.getTimeWindowJson().isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(layer.getTimeWindowJson());
            JsonNode daily = root.path("daily");
            if (!daily.isArray() || daily.isEmpty()) {
                return true;
            }
            LocalTime now = LocalTime.ofInstant(at, ZoneOffset.UTC);
            for (JsonNode w : daily) {
                LocalTime start = LocalTime.parse(w.path("start").asText("00:00"));
                LocalTime end = LocalTime.parse(w.path("end").asText("23:59"));
                if (start.isBefore(end) || start.equals(end)) {
                    if (!now.isBefore(start) && !now.isAfter(end)) {
                        return true;
                    }
                } else if (!now.isBefore(start) || now.isBefore(end)) {
                    // 跨零点窗口（如 22:00–05:00）：[start, 24:00) ∪ [00:00, end]
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("图层 {} 时段窗口不可解析，按恒在窗内处理（保守）: {}", layer.getName(), e.getMessage());
            return true;
        }
    }

    /** 解析 [[lng,lat], ...] 闭合环；缺失/结构不合法返回 null。 */
    @SuppressWarnings("unchecked")
    private List<double[]> parseRing(String polygonJson) {
        if (polygonJson == null || polygonJson.isBlank()) {
            return null;
        }
        try {
            List<?> raw = objectMapper.readValue(polygonJson, List.class);
            List<double[]> ring = new ArrayList<>();
            for (Object point : raw) {
                if (!(point instanceof List<?> pair) || pair.size() < 2) {
                    return null;
                }
                ring.add(new double[] {
                        ((Number) pair.get(0)).doubleValue(),
                        ((Number) pair.get(1)).doubleValue()
                });
            }
            return ring.size() >= 3 ? ring : null;
        } catch (Exception e) {
            log.warn("多边形解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 射线法点在多边形内判定（环隐式闭合，边界视为内部）。 */
    private static boolean contains(List<double[]> ring, double lng, double lat) {
        boolean inside = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double lngI = ring.get(i)[0];
            double latI = ring.get(i)[1];
            double lngJ = ring.get(j)[0];
            double latJ = ring.get(j)[1];
            boolean intersects = ((latI > lat) != (latJ > lat))
                    && (lng < (lngJ - lngI) * (lat - latI) / (latJ - latI) + lngI);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }
}
