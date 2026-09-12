package com.claw.server.domain.pv;

import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.iot.PvGenerationHourlyRepository;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PvReconciliationService 单元测试（Mockito，不连 DB、不用 testcontainers）。
 *
 * <p>重点覆盖：偏差 0%→OK、超阈值→WARN、任一侧缺失→GAP（含 null / 0 / 单侧缺失三种），
 * 以及同一 station+day 重复 reconcileDay 的 upsert 语义（不插重复行）。
 *
 * <p>BigDecimal 一律用 {@code isEqualByComparingTo}（assertEquals 会因 scale 不等假失败）。
 * Mockito 为 strict stubbing：本测试只用到的 stub 才写，不写无用 stub。
 */
@ExtendWith(MockitoExtension.class)
class PvReconciliationServiceTest {

    private static final Long STATION = 42L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    @Mock
    private PvGenerationHourlyRepository pvRepo;
    @Mock
    private PvDailyReconciliationRepository reconRepo;
    @Mock
    private AssetRepository assetRepo;
    @Mock
    private SystemConfigRepository sysRepo;

    @InjectMocks
    private PvReconciliationService service;

    /** day 当天的 UTC 左闭区间起点（与 service 内口径一致）。 */
    private static Instant startOfDay(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** day 当天的 UTC 右开区间终点（次日 00:00）。 */
    private static Instant endOfDay(LocalDate d) {
        return d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private void stubDefaultThreshold() {
        when(sysRepo.findByConfigKeyAndDeletedFalse(PvReconciliationService.CONFIG_KEY_DEVIATION_RATE))
                .thenReturn(Optional.empty());
    }

    private void stubSums(BigDecimal inverter, BigDecimal meter) {
        when(pvRepo.sumEnergyWhByStationAndSourceAndDay(
                eq(STATION), eq(PvReconciliationService.SOURCE_INVERTER), eq(startOfDay(DAY)), eq(endOfDay(DAY))))
                .thenReturn(inverter);
        when(pvRepo.sumEnergyWhByStationAndSourceAndDay(
                eq(STATION), eq(PvReconciliationService.SOURCE_METER), eq(startOfDay(DAY)), eq(endOfDay(DAY))))
                .thenReturn(meter);
    }

    // ------------------------------------------------------------------
    // 用例 1：INVERTER 与 METER 数据齐全、偏差 0% → OK
    // ------------------------------------------------------------------
    @Test
    void reconcileDay_zero_deviation_is_ok() {
        stubDefaultThreshold();
        stubSums(new BigDecimal("100"), new BigDecimal("100"));

        PvReconciliationService.ReconcileResult r = service.reconcileDay(STATION, DAY);

        assertThat(r.status()).isEqualTo(PvReconciliationService.STATUS_OK);
        assertThat(r.deviationWh()).isEqualByComparingTo("0");
        assertThat(r.deviationRate()).isEqualByComparingTo("0");
        assertThat(r.note()).contains("阈值内");
    }

    // ------------------------------------------------------------------
    // 用例 2：偏差 8% 超 5% 阈值 → WARN
    // ------------------------------------------------------------------
    @Test
    void reconcileDay_over_threshold_is_warn() {
        stubDefaultThreshold();
        stubSums(new BigDecimal("108"), new BigDecimal("100")); // 偏差 = 8，rate = 0.08 > 0.05

        PvReconciliationService.ReconcileResult r = service.reconcileDay(STATION, DAY);

        assertThat(r.status()).isEqualTo(PvReconciliationService.STATUS_WARN);
        assertThat(r.deviationWh()).isEqualByComparingTo("8");
        assertThat(r.deviationRate()).isEqualByComparingTo("0.08");
        assertThat(r.note()).contains("超阈值");
    }

    // ------------------------------------------------------------------
    // 用例 3：INVERTER 合计为 null（无数据）→ GAP
    // ------------------------------------------------------------------
    @Test
    void reconcileDay_inverter_null_is_gap() {
        stubDefaultThreshold();
        stubSums(null, new BigDecimal("100"));

        PvReconciliationService.ReconcileResult r = service.reconcileDay(STATION, DAY);

        assertThat(r.status()).isEqualTo(PvReconciliationService.STATUS_GAP);
        assertThat(r.deviationWh()).isNull();
        assertThat(r.deviationRate()).isNull();
        assertThat(r.note()).contains("逆变器侧");
    }

    // ------------------------------------------------------------------
    // 用例 4：METER 合计为 0 → GAP（不除零）
    // ------------------------------------------------------------------
    @Test
    void reconcileDay_meter_zero_is_gap_no_divide_by_zero() {
        stubDefaultThreshold();
        stubSums(new BigDecimal("100"), BigDecimal.ZERO);

        PvReconciliationService.ReconcileResult r = service.reconcileDay(STATION, DAY);

        assertThat(r.status()).isEqualTo(PvReconciliationService.STATUS_GAP);
        assertThat(r.deviationWh()).isNull();
        assertThat(r.deviationRate()).isNull();
        assertThat(r.note()).contains("电表侧");
    }

    // ------------------------------------------------------------------
    // 用例 5：缺 METER 侧但有 INVERTER → GAP，note 指明
    // ------------------------------------------------------------------
    @Test
    void reconcileDay_missing_meter_side_is_gap_with_note() {
        stubDefaultThreshold();
        stubSums(new BigDecimal("100"), null);

        PvReconciliationService.ReconcileResult r = service.reconcileDay(STATION, DAY);

        assertThat(r.status()).isEqualTo(PvReconciliationService.STATUS_GAP);
        assertThat(r.deviationRate()).isNull();
        assertThat(r.note()).contains("电表侧(METER)无有效数据");
    }

    // ------------------------------------------------------------------
    // 用例 6：重复 reconcileDay 同一 station+day 不插重复行（upsert 语义）
    // ------------------------------------------------------------------
    @Test
    void reconcileDay_repeated_same_station_day_upserts_no_duplicate() {
        stubDefaultThreshold();
        stubSums(new BigDecimal("106"), new BigDecimal("100")); // 偏差 = 6，rate = 0.06 > 0.05 阈值

        // 用内存 map 模拟仓储：按 station+day 定位，save 覆盖同键
        Map<String, PvDailyReconciliation> store = new HashMap<>();
        when(reconRepo.findByStationAssetIdAndDay(eq(STATION), eq(DAY)))
                .thenAnswer(inv -> Optional.ofNullable(store.get("k")));
        when(reconRepo.save(any(PvDailyReconciliation.class))).thenAnswer(inv -> {
            PvDailyReconciliation e = inv.getArgument(0);
            store.put("k", e);
            return e;
        });

        service.reconcileDay(STATION, DAY);
        service.reconcileDay(STATION, DAY);

        // 同一 station+day 在 store 中只应有一行
        assertThat(store).hasSize(1);
        verify(reconRepo, times(2)).save(any(PvDailyReconciliation.class));

        PvDailyReconciliation saved = store.get("k");
        assertThat(saved.getStatus()).isEqualTo(PvReconciliationService.STATUS_WARN);
        assertThat(saved.getDeviationRate()).isEqualByComparingTo("0.06");
    }

    // ------------------------------------------------------------------
    // 用例 7：reconcileAll 只收集 WARN 站点
    // ------------------------------------------------------------------
    @Test
    void reconcileAll_collects_only_warn_stations() {
        Asset a = Asset.builder().id(1L).assetType(AssetType.PV_STATION).build();
        Asset b = Asset.builder().id(2L).assetType(AssetType.PV_STATION).build();
        Asset c = Asset.builder().id(3L).assetType(AssetType.PV_STATION).build();
        when(assetRepo.findByAssetType(AssetType.PV_STATION)).thenReturn(List.of(a, b, c));

        // A=OK(100/100), B=WARN(108/100), C=GAP(METER=0)
        when(pvRepo.sumEnergyWhByStationAndSourceAndDay(eq(1L), eq(PvReconciliationService.SOURCE_INVERTER), any(), any()))
                .thenReturn(new BigDecimal("100"));
        when(pvRepo.sumEnergyWhByStationAndSourceAndDay(eq(1L), eq(PvReconciliationService.SOURCE_METER), any(), any()))
                .thenReturn(new BigDecimal("100"));
        when(pvRepo.sumEnergyWhByStationAndSourceAndDay(eq(2L), eq(PvReconciliationService.SOURCE_INVERTER), any(), any()))
                .thenReturn(new BigDecimal("108"));
        when(pvRepo.sumEnergyWhByStationAndSourceAndDay(eq(2L), eq(PvReconciliationService.SOURCE_METER), any(), any()))
                .thenReturn(new BigDecimal("100"));
        when(pvRepo.sumEnergyWhByStationAndSourceAndDay(eq(3L), eq(PvReconciliationService.SOURCE_INVERTER), any(), any()))
                .thenReturn(new BigDecimal("100"));
        when(pvRepo.sumEnergyWhByStationAndSourceAndDay(eq(3L), eq(PvReconciliationService.SOURCE_METER), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        when(sysRepo.findByConfigKeyAndDeletedFalse(any())).thenReturn(Optional.empty());
        when(reconRepo.findByStationAssetIdAndDay(any(), any())).thenReturn(Optional.empty());
        when(reconRepo.save(any(PvDailyReconciliation.class))).thenAnswer(inv -> inv.getArgument(0));

        List<PvReconciliationService.ReconcileResult> warns = service.reconcileAll(DAY);

        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).stationAssetId()).isEqualTo(2L);
        assertThat(warns.get(0).status()).isEqualTo(PvReconciliationService.STATUS_WARN);

        ArgumentCaptor<PvDailyReconciliation> captor = ArgumentCaptor.forClass(PvDailyReconciliation.class);
        verify(reconRepo, times(3)).save(captor.capture());
        // A OK / B WARN / C GAP 三行都落库
        assertThat(captor.getAllValues()).hasSize(3);
    }
}
