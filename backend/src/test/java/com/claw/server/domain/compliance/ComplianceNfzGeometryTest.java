package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.NfzLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * 禁飞图层几何/时段评估测试（切片 3，{@code Compliance*} 命名以纳入验收测试集）。
 *
 * <p><b>直接复用 V144 种子的真实多边形字符串</b>做断言 —— 这样几何解析与生产种子数据
 * 永远互为校验：种子写坏了这里立刻红，而不是等到真机冒烟才发现禁飞区形同虚设。
 */
@ExtendWith(MockitoExtension.class)
class ComplianceNfzGeometryTest {

    /** 与 V144__nfz_seed.sql 中 A1（吴哥/APSARA，八边形）逐字一致。 */
    private static final String ANGKOR_RING =
            "[[103.9132,13.4125],[103.8997,13.4442],[103.8670,13.4574],[103.8343,13.4442],"
                    + "[103.8208,13.4125],[103.8343,13.3808],[103.8670,13.3676],[103.8997,13.3808]]";

    /** 与 V144__nfz_seed.sql 中 B1（泰柬边境省，矩形）逐字一致。 */
    private static final String BORDER_RING = "[[102.3,12.5],[103.4,12.5],[103.4,14.4],[102.3,14.4]]";

    private static final Instant AT_18_UTC = Instant.parse("2026-09-14T18:00:00Z");
    private static final Instant AT_08_UTC = Instant.parse("2026-09-14T08:00:00Z");
    private static final Instant AT_23_UTC = Instant.parse("2026-09-14T23:00:00Z");

    @Mock
    private NfzLayerRepository repository;

    private NfzService service() {
        return new NfzService(repository, new ObjectMapper());
    }

    private static NfzLayer layer(String name, String source, NfzLevel level,
                                  String polygon, String window, boolean enabled) {
        return NfzLayer.builder().id(1L).name(name).source(source).level(level)
                .polygonJson(polygon).timeWindowJson(window).enabled(enabled).build();
    }

    // ------------------------------------------------------------------ 多边形几何

    @Test
    void angkorRing_containsItsOwnCenter() {
        Optional<Boolean> hit = service().containsPoint(
                layer("angkor", NfzLayer.SOURCE_BAKED_IN, NfzLevel.ABSOLUTE, ANGKOR_RING, null, true),
                103.8670, 13.4125);

        assertEquals(Boolean.TRUE, hit.orElse(null), "种子八边形必须包住吴哥中心点");
    }

    @Test
    void angkorRing_excludesPhnomPenhPalace() {
        Optional<Boolean> hit = service().containsPoint(
                layer("angkor", NfzLayer.SOURCE_BAKED_IN, NfzLevel.ABSOLUTE, ANGKOR_RING, null, true),
                104.9282, 11.5636);

        assertEquals(Boolean.FALSE, hit.orElse(null), "金边王宫坐标不应落入吴哥图层");
    }

    @Test
    void missingPolygon_isNotJudgeable() {
        Optional<Boolean> hit = service().containsPoint(
                layer("empty", NfzLayer.SOURCE_BAKED_IN, NfzLevel.ABSOLUTE, null, null, true),
                103.8670, 13.4125);

        assertTrue(hit.isEmpty(), "多边形缺失 → 无法判定（由调用方按层语义兜底）");
    }

    @Test
    void malformedPolygon_isNotJudgeable() {
        Optional<Boolean> hit = service().containsPoint(
                layer("bad", NfzLayer.SOURCE_BAKED_IN, NfzLevel.ABSOLUTE, "not-json", null, true),
                103.8670, 13.4125);

        assertTrue(hit.isEmpty());
    }

    // ------------------------------------------------------------------ 时段窗口（UTC）

    @Test
    void timeWindow_insideUtcWindow() {
        NfzLayer layer = layer("border", "REGULATION-2025", NfzLevel.TIME_WINDOW, BORDER_RING,
                "{\"tz\":\"UTC\",\"daily\":[{\"start\":\"15:00\",\"end\":\"22:00\"}]}", true);

        assertTrue(service().inTimeWindow(layer, AT_18_UTC), "UTC 18:00 落在 15:00–22:00 窗口内");
    }

    @Test
    void timeWindow_outsideUtcWindow() {
        NfzLayer layer = layer("border", "REGULATION-2025", NfzLevel.TIME_WINDOW, BORDER_RING,
                "{\"tz\":\"UTC\",\"daily\":[{\"start\":\"15:00\",\"end\":\"22:00\"}]}", true);

        assertFalse(service().inTimeWindow(layer, AT_08_UTC), "UTC 08:00 不在窗口内");
    }

    @Test
    void timeWindow_supportsMidnightWrapAround() {
        NfzLayer layer = layer("night", "REGULATION-2025", NfzLevel.TIME_WINDOW, BORDER_RING,
                "{\"daily\":[{\"start\":\"22:00\",\"end\":\"05:00\"}]}", true);

        assertTrue(service().inTimeWindow(layer, AT_23_UTC), "跨零点窗口：23:00 在 22:00–05:00 内");
        assertTrue(service().inTimeWindow(layer, Instant.parse("2026-09-14T03:00:00Z")),
                "跨零点窗口：03:00 也在窗口内");
        assertFalse(service().inTimeWindow(layer, AT_08_UTC), "跨零点窗口：08:00 不在窗口内");
    }

    @Test
    void timeWindow_missingWindowTreatedAsAlwaysInside() {
        NfzLayer layer = layer("no-window", "REGULATION-2025", NfzLevel.TIME_WINDOW,
                BORDER_RING, null, true);

        assertTrue(service().inTimeWindow(layer, AT_08_UTC), "窗口缺失按「恒在窗内」处理（保守）");
    }

    // ------------------------------------------------------------------ 命中评估分层

    @Test
    void firstBakedInHit_ignoresEnabledFlag() {
        NfzLayer baked = layer("airport", NfzLayer.SOURCE_BAKED_IN, NfzLevel.ABSOLUTE,
                ANGKOR_RING, null, false);
        when(repository.findBySource(NfzLayer.SOURCE_BAKED_IN)).thenReturn(List.of(baked));

        assertTrue(service().firstBakedInHit(103.8670, 13.4125, AT_18_UTC).isPresent(),
                "固有安全约束不看 enabled：被误关也必须命中");
    }

    @Test
    void firstOperationalHit_skipsDisabledAndBakedInLayers() {
        NfzLayer disabled = layer("border", "REGULATION-2025", NfzLevel.TIME_WINDOW,
                BORDER_RING, null, false);
        NfzLayer baked = layer("airport", NfzLayer.SOURCE_BAKED_IN, NfzLevel.ABSOLUTE,
                ANGKOR_RING, null, true);
        when(repository.findByEnabledTrue()).thenReturn(List.of(disabled, baked));

        assertTrue(service().firstOperationalHit(102.9, 13.0, AT_18_UTC).isEmpty(),
                "运营层 disabled=true 的不参与；BAKED-IN 归 firstBakedInHit 评估");
    }

    @Test
    void firstOperationalHit_hitsEnabledLayerInsideRingAndWindow() {
        NfzLayer enabled = layer("border", "REGULATION-2025", NfzLevel.TIME_WINDOW,
                BORDER_RING, "{\"daily\":[{\"start\":\"15:00\",\"end\":\"22:00\"}]}", true);
        when(repository.findByEnabledTrue()).thenReturn(List.of(enabled));

        Optional<NfzLayer> hit = service().firstOperationalHit(102.9, 13.0, AT_18_UTC);

        assertTrue(hit.isPresent(), "边境层内 + 窗口内 → 命中");
        assertEquals("border", hit.get().getName());
    }

    // ------------------------------------------------------------------ 启停守卫

    @Test
    void setEnabled_bakedInLayerRejectedWith409() {
        NfzLayer baked = layer("airport", NfzLayer.SOURCE_BAKED_IN, NfzLevel.ABSOLUTE,
                ANGKOR_RING, null, true);
        when(repository.findById(1L)).thenReturn(Optional.of(baked));

        BizException ex = assertThrows(BizException.class, () -> service().setEnabled(1L, false));

        assertEquals(40967, ex.getCode());
        assertEquals("error.drone.nfz.baked.in", ex.getMessageCode());
    }

    @Test
    void setEnabled_operationalLayerToggles() {
        NfzLayer operational = layer("border", "REGULATION-2025", NfzLevel.TIME_WINDOW,
                BORDER_RING, null, true);
        when(repository.findById(1L)).thenReturn(Optional.of(operational));
        when(repository.save(operational)).thenAnswer(inv -> inv.getArgument(0));

        assertFalse(service().setEnabled(1L, false).getEnabled(), "运营限制层可随政策放开收起");
    }

    @Test
    void setEnabled_missingLayerReturns404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service().setEnabled(99L, true));

        assertEquals(40471, ex.getCode());
    }
}
