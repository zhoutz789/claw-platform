package com.claw.server.domain.energy;

import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ElecTouService 单元测试（Mockito，不连 DB）。
 *
 * <p>重点：纯函数（{@code covers / match / weightedAveragePrice / resolveSlotCode}）无 DB 依赖、
 * 可直接断言；应用方法（{@code slotAt / priceAt / referencePrice / zone}）用 Mockito 替掉仓储。
 * 红线：无有效时段时 {@code priceAt} 必须返回 {@code null}，绝不能编默认值。
 */
@ExtendWith(MockitoExtension.class)
class ElecTouServiceTest {

    /** 20:00 UTC+7（=13:00Z）落在 PEAK 区段（1080–1380 分钟）。 */
    private static final Instant PEAK_INSTANT = Instant.parse("2026-06-01T13:00:00Z");
    /** 17:00 UTC+7（=10:00Z）落在 FLAT 区段，但种子里没有 FLAT 规则时用于验证"无命中返回空"。 */
    private static final Instant FLAT_INSTANT = Instant.parse("2026-06-01T10:00:00Z");

    @Mock
    private ElecTouSlotRepository slotRepository;
    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private ElecTouService service;

    private ElecTouSlot slot(String code, int start, int end, String price) {
        return ElecTouSlot.builder()
                .slotCode(code).startMinute(start).endMinute(end)
                .energyPrice(new BigDecimal(price)).enabled(true).priority(0)
                .build();
    }

    // ===================== 纯函数 =====================

    @Test
    void covers_normal_range_inclusive_start_exclusive_end() {
        var s = ElecTouService.SlotSpec.of("FLAT", 420, 1080, "0.15");
        assertThat(ElecTouService.covers(s, 420)).isTrue();
        assertThat(ElecTouService.covers(s, 1079)).isTrue();
        assertThat(ElecTouService.covers(s, 1080)).isFalse();
        assertThat(ElecTouService.covers(s, 100)).isFalse();
    }

    @Test
    void covers_cross_midnight() {
        var s = new ElecTouService.SlotSpec("NIGHT", 1380, 60, new BigDecimal("0.10"), null, null, 0);
        assertThat(ElecTouService.covers(s, 1400)).isTrue();
        assertThat(ElecTouService.covers(s, 30)).isTrue();
        assertThat(ElecTouService.covers(s, 300)).isFalse();
    }

    @Test
    void match_picks_min_priority_on_overlap() {
        var slots = List.of(
                new ElecTouService.SlotSpec("PEAK", 1080, 1380, new BigDecimal("0.22"), null, null, 1),
                new ElecTouService.SlotSpec("FLAT", 0, 1440, new BigDecimal("0.15"), null, null, 0));
        var hit = ElecTouService.match(slots, 1200, null);
        // 两时段在 1200 分钟重叠，取 priority 更小者（FLAT=0 < PEAK=1）
        assertThat(hit.slotCode()).isEqualTo("FLAT");
    }

    @Test
    void match_respects_effective_from() {
        var future = new ElecTouService.SlotSpec("PEAK", 1080, 1380, new BigDecimal("0.22"), null,
                LocalDate.of(2030, 1, 1), 0);
        var hit = ElecTouService.match(List.of(future), 1200, LocalDate.of(2026, 6, 1));
        assertThat(hit).isNull();
    }

    @Test
    void weighted_average_price_weights_by_duration() {
        var slots = List.of(
                new ElecTouService.SlotSpec("VALLEY", 0, 420, new BigDecimal("0.08"), null, null, 0),
                new ElecTouService.SlotSpec("FLAT", 420, 1080, new BigDecimal("0.15"), null, null, 0),
                new ElecTouService.SlotSpec("PEAK", 1080, 1380, new BigDecimal("0.22"), null, null, 0));
        BigDecimal expected = new BigDecimal("0.08").multiply(BigDecimal.valueOf(420))
                .add(new BigDecimal("0.15").multiply(BigDecimal.valueOf(660)))
                .add(new BigDecimal("0.22").multiply(BigDecimal.valueOf(300)))
                .divide(BigDecimal.valueOf(1380), 8, RoundingMode.HALF_UP);
        assertThat(ElecTouService.weightedAveragePrice(slots)).isEqualByComparingTo(expected);
    }

    @Test
    void weighted_average_price_returns_null_without_slots() {
        assertThat(ElecTouService.weightedAveragePrice(List.of())).isNull();
        assertThat(ElecTouService.weightedAveragePrice(null)).isNull();
    }

    // ===================== 应用方法 =====================

    @Test
    void slotAt_returns_matched_slot_with_price() {
        when(slotRepository.findByEnabledTrueOrderByPriorityAscStartMinuteAsc())
                .thenReturn(List.of(
                        slot("VALLEY", 0, 420, "0.08"),
                        slot("FLAT", 420, 1080, "0.15"),
                        slot("PEAK", 1080, 1380, "0.22"),
                        slot("FLAT2", 1380, 1440, "0.15")));
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(ElecTouService.KEY_TIMEZONE))
                .thenReturn(Optional.empty());

        ElecTouService.TouSlot result = service.slotAt(PEAK_INSTANT);
        assertThat(result.slotCode()).isEqualTo("PEAK");
        assertThat(result.energyPrice()).isEqualByComparingTo("0.22");
        assertThat(result.known()).isTrue();
    }

    @Test
    void price_at_returns_null_when_no_slot_matched() {
        // 只配了 PEAK 规则，FLAT 时刻（17:00 UTC+7）不命中 → 红线：返回 null，不编默认值
        when(slotRepository.findByEnabledTrueOrderByPriorityAscStartMinuteAsc())
                .thenReturn(List.of(slot("PEAK", 1080, 1380, "0.22")));
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(ElecTouService.KEY_TIMEZONE))
                .thenReturn(Optional.empty());

        assertThat(service.priceAt(FLAT_INSTANT)).isNull();
        assertThat(service.slotCodeAt(FLAT_INSTANT)).isEqualTo(ElecTouService.UNKNOWN);
    }

    @Test
    void reference_price_uses_weighted_average_of_all_slots() {
        when(slotRepository.findByEnabledTrueOrderByPriorityAscStartMinuteAsc())
                .thenReturn(List.of(
                        slot("VALLEY", 0, 420, "0.08"),
                        slot("FLAT", 420, 1080, "0.15"),
                        slot("PEAK", 1080, 1380, "0.22")));
        BigDecimal expected = new BigDecimal("0.08").multiply(BigDecimal.valueOf(420))
                .add(new BigDecimal("0.15").multiply(BigDecimal.valueOf(660)))
                .add(new BigDecimal("0.22").multiply(BigDecimal.valueOf(300)))
                .divide(BigDecimal.valueOf(1380), 8, RoundingMode.HALF_UP);
        assertThat(service.referencePrice()).isEqualByComparingTo(expected);
    }

    @Test
    void zone_defaults_to_phnom_penh() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(ElecTouService.KEY_TIMEZONE))
                .thenReturn(Optional.empty());
        assertThat(service.zone().getId()).isEqualTo("Asia/Phnom_Penh");
    }

    @Test
    void zone_reads_config_when_present() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(ElecTouService.KEY_TIMEZONE))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey(ElecTouService.KEY_TIMEZONE).configValue("UTC").build()));
        assertThat(service.zone().getId()).isEqualTo("UTC");
    }
}
