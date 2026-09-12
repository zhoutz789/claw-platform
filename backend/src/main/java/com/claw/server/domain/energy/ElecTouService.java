package com.claw.server.domain.energy;

import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * TOU 峰谷电价服务（VPP 切片第二批）。
 *
 * <p><b>红线：没有价格就返回 null，绝不编一个默认值当真价格用。</b>
 * 取不到有效时段时 {@link #priceAt} / {@link #demandPriceAt} 一律返回 {@code null}，
 * {@link #slotAt} 返回 {@code slotCode = UNKNOWN}；调用方（VppDispatchService）据此
 * 回落保守策略。种子数据里的电价是<b>占位符，待业务确认，勿直接用于结算</b>。
 *
 * <p>时段判定纯函数（{@link #match} / {@link #covers} / {@link #resolveSlotCode}）无 DB 依赖，
 * 照 {@code VppDispatchService.dispatchPlan} 的做法抽出来便于单测。
 *
 * <p>时区：{@code system_config.VPP_TIMEZONE}，缺省 {@code Asia/Phnom_Penh}（UTC+7）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ElecTouService {

    /** 找不到所属时段时的返回码（不抛异常）。 */
    public static final String UNKNOWN = "UNKNOWN";
    public static final String KEY_TIMEZONE = "VPP_TIMEZONE";
    public static final String DEFAULT_TIMEZONE = "Asia/Phnom_Penh";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MINUTES_PER_DAY = 1440;

    // ===================== 模型 =====================

    /** 时段规则（纯数据，与实体解耦便于单测）。 */
    public record SlotSpec(
            String slotCode,
            int startMinute,
            int endMinute,
            BigDecimal energyPrice,
            BigDecimal demandPrice,
            /** NULL = 一直有效。 */
            LocalDate effectiveFrom,
            int priority) {

        public static SlotSpec of(String code, int start, int end, String energyPrice) {
            return new SlotSpec(code, start, end, new BigDecimal(energyPrice), null, null, 0);
        }
    }

    /** 某时刻所属时段及价格。 */
    public record TouSlot(String slotCode, BigDecimal energyPrice, BigDecimal demandPrice) {

        public boolean known() {
            return slotCode != null && !UNKNOWN.equals(slotCode);
        }
    }

    /** 未匹配到任何时段时的返回值。 */
    public static final TouSlot UNKNOWN_SLOT = new TouSlot(UNKNOWN, null, null);

    // ===================== 纯函数 =====================

    /** 时段是否覆盖该分钟（支持跨零点：start &gt; end 时按跨天解释）。 */
    public static boolean covers(SlotSpec s, int minuteOfDay) {
        if (s == null) {
            return false;
        }
        if (s.startMinute() <= s.endMinute()) {
            return minuteOfDay >= s.startMinute() && minuteOfDay < s.endMinute();
        }
        return minuteOfDay >= s.startMinute() || minuteOfDay < s.endMinute();
    }

    /** 时段时长（分钟），跨零点按 (end − start + 1440) % 1440；退化时按整天计。 */
    public static int durationMinutes(SlotSpec s) {
        int raw = (s.endMinute() - s.startMinute() + MINUTES_PER_DAY) % MINUTES_PER_DAY;
        return raw == 0 ? MINUTES_PER_DAY : raw;
    }

    /**
     * 选出该分钟所属的时段；重叠时取 {@code priority} 最小者（同优先级取排序靠前者）。
     *
     * @return 命中的时段；无命中返回 {@code null}
     */
    public static SlotSpec match(List<SlotSpec> slots, int minuteOfDay, LocalDate date) {
        if (slots == null) {
            return null;
        }
        SlotSpec best = null;
        for (SlotSpec s : slots) {
            if (s == null) {
                continue;
            }
            if (s.effectiveFrom() != null && (date == null || date.isBefore(s.effectiveFrom()))) {
                continue;
            }
            if (!covers(s, minuteOfDay)) {
                continue;
            }
            // 入参已按 priority asc 排序，故严格小于才替换 = 同优先级取靠前者
            if (best == null || s.priority() < best.priority()) {
                best = s;
            }
        }
        return best;
    }

    /** 该分钟所属时段代码；无命中返回 {@link #UNKNOWN}。 */
    public static String resolveSlotCode(List<SlotSpec> slots, int minuteOfDay, LocalDate date) {
        SlotSpec s = match(slots, minuteOfDay, date);
        return s == null ? UNKNOWN : s.slotCode();
    }

    /** 按时长加权的平均电度电价（峰/谷判断参考基准）；无有效时段返回 null。 */
    public static BigDecimal weightedAveragePrice(List<SlotSpec> slots) {
        if (slots == null || slots.isEmpty()) {
            return null;
        }
        BigDecimal weighted = ZERO;
        int total = 0;
        for (SlotSpec s : slots) {
            if (s == null || s.energyPrice() == null) {
                continue;
            }
            int minutes = durationMinutes(s);
            weighted = weighted.add(s.energyPrice().multiply(BigDecimal.valueOf(minutes)));
            total += minutes;
        }
        if (total == 0) {
            return null;
        }
        return weighted.divide(BigDecimal.valueOf(total), 8, RoundingMode.HALF_UP);
    }

    // ===================== 应用 =====================

    private final ElecTouSlotRepository slotRepository;
    private final SystemConfigRepository systemConfigRepository;

    /** 该时刻所属时段；找不到返回 slotCode=UNKNOWN（不抛异常）。 */
    public TouSlot slotAt(Instant instant) {
        if (instant == null) {
            return UNKNOWN_SLOT;
        }
        ZonedDateTime zdt = instant.atZone(zone());
        int minuteOfDay = zdt.getHour() * 60 + zdt.getMinute();
        SlotSpec hit = match(specs(), minuteOfDay, zdt.toLocalDate());
        if (hit == null) {
            return UNKNOWN_SLOT;
        }
        return new TouSlot(hit.slotCode(), hit.energyPrice(), hit.demandPrice());
    }

    /** 时段代码（PEAK / FLAT / VALLEY / UNKNOWN）。 */
    public String slotCodeAt(Instant instant) {
        return slotAt(instant).slotCode();
    }

    /** 电度电价 $/kWh；无有效时段返回 {@code null}。 */
    public BigDecimal priceAt(Instant instant) {
        TouSlot s = slotAt(instant);
        return s.known() ? s.energyPrice() : null;
    }

    /** 需量电价 $/kW；无有效时段或未配置返回 {@code null}。 */
    public BigDecimal demandPriceAt(Instant instant) {
        TouSlot s = slotAt(instant);
        return s.known() ? s.demandPrice() : null;
    }

    /** 全时段加权平均电价（峰/谷参考基准）；无有效时段返回 {@code null}。 */
    public BigDecimal referencePrice() {
        return weightedAveragePrice(specs());
    }

    /** 加载启用中的时段规则（按 priority asc、start_minute asc）。 */
    public List<SlotSpec> specs() {
        List<SlotSpec> out = new ArrayList<>();
        for (ElecTouSlot e : slotRepository.findByEnabledTrueOrderByPriorityAscStartMinuteAsc()) {
            out.add(new SlotSpec(
                    e.getSlotCode(),
                    e.getStartMinute() == null ? 0 : e.getStartMinute(),
                    e.getEndMinute() == null ? 0 : e.getEndMinute(),
                    e.getEnergyPrice(),
                    e.getDemandPrice(),
                    e.getEffectiveFrom(),
                    e.getPriority() == null ? 0 : e.getPriority()));
        }
        return out;
    }

    /** TOU 判定时区（system_config.VPP_TIMEZONE，缺省 Asia/Phnom_Penh）。 */
    public ZoneId zone() {
        String name = systemConfigRepository.findByConfigKeyAndDeletedFalse(KEY_TIMEZONE)
                .map(SystemConfig::getConfigValue)
                .map(v -> v == null ? null : v.trim())
                .filter(v -> !v.isEmpty())
                .orElse(DEFAULT_TIMEZONE);
        try {
            return ZoneId.of(name);
        } catch (Exception e) {
            log.warn("[TOU] system_config.{} 非法时区：{}，回退 {}", KEY_TIMEZONE, name, DEFAULT_TIMEZONE);
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
}
