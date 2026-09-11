package com.claw.server.domain.vpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VppResourceService 单元测试（Mockito，不连 DB）。
 *
 * <p>重点：注册去重返回既有记录、资产不存在抛 BizException、OFFLINE 资源不计入容量。
 * BigDecimal 一律用 {@code isEqualByComparingTo}（assertEquals 会因 scale 不等假失败）。
 */
@ExtendWith(MockitoExtension.class)
class VppResourceServiceTest {

    private static final Long PORTFOLIO_ID = 1L;
    private static final Long ASSET_ID = 100L;
    private static final Long RESOURCE_ID = 1000L;

    @Mock
    private VppResourceRepository vppResourceRepository;
    @Mock
    private com.claw.server.domain.asset.AssetRepository assetRepository;
    @Mock
    private com.claw.server.domain.iot.TelemetryLatestRepository telemetryLatestRepository;
    @Mock
    private com.claw.server.domain.station.ChargeSessionRepository chargeSessionRepository;
    @Mock
    private com.claw.server.domain.iot.DeviceRepository deviceRepository;
    @Mock
    private com.claw.server.domain.settings.SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private VppResourceService service;

    private VppResource resource(Long id, Long assetId, String type, String status) {
        return VppResource.builder()
                .id(id).portfolioId(PORTFOLIO_ID).assetId(assetId)
                .resourceType(type).status(status)
                .ratedPowerW(new BigDecimal("5000.00"))
                .build();
    }

    private com.claw.server.domain.iot.TelemetryLatest essTelemetry(BigDecimal packVoltage,
                                                                   BigDecimal ccl, BigDecimal dcl) {
        return com.claw.server.domain.iot.TelemetryLatest.builder()
                .assetId(ASSET_ID)
                .soc(new BigDecimal("60"))
                .packVoltage(packVoltage)
                .ccl(ccl)
                .dcl(dcl)
                .build();
    }

    @Test
    void register_returns_existing_row_when_asset_already_registered() {
        VppResource existing = resource(RESOURCE_ID, ASSET_ID, VppDispatchService.ESS, "ONLINE");
        when(vppResourceRepository.findByAssetId(ASSET_ID)).thenReturn(java.util.Optional.of(existing));

        VppResource result = service.register(ASSET_ID, PORTFOLIO_ID, VppDispatchService.ESS);

        assertThat(result.getId()).isEqualTo(RESOURCE_ID);
        verify(vppResourceRepository, never()).save(any(VppResource.class));
    }

    @Test
    void register_throws_not_found_when_asset_missing() {
        when(vppResourceRepository.findByAssetId(ASSET_ID)).thenReturn(java.util.Optional.empty());
        when(assetRepository.existsById(ASSET_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.register(ASSET_ID, PORTFOLIO_ID, VppDispatchService.PV))
                .isInstanceOf(com.claw.server.common.api.BizException.class)
                .hasMessageContaining("error.vpp.asset.not.found");
        verify(vppResourceRepository, never()).save(any(VppResource.class));
    }

    @Test
    void register_rejects_unknown_resource_type() {
        assertThrows(com.claw.server.common.api.BizException.class,
                () -> service.register(ASSET_ID, PORTFOLIO_ID, "WIND_TURBINE"));
    }

    @Test
    void capacity_ignores_offline_resources() {
        // 只有 ONLINE 的资源会进入聚合（OFFLINE 由查询条件直接排除，本例断言离线资源不出现在明细里）
        VppResource online = resource(10L, ASSET_ID, VppDispatchService.ESS, "ONLINE");
        when(vppResourceRepository.findByPortfolioIdAndStatus(PORTFOLIO_ID, "ONLINE"))
                .thenReturn(List.of(online));
        // 组电压 50V、DCL 60A → 3000W；CCL 30A → 1500W；额定 5000W 不构成上限
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(ASSET_ID))
                .thenReturn(java.util.Optional.of(essTelemetry(
                        new BigDecimal("50"), new BigDecimal("30"), new BigDecimal("60"))));

        VppResourceService.CapacityView view = service.capacity(PORTFOLIO_ID);

        assertThat(view.onlineCount()).isEqualTo(1);
        assertThat(view.unavailableCount()).isZero();
        assertThat(view.adjustableUpW()).isEqualByComparingTo("3000");
        assertThat(view.adjustableDownW()).isEqualByComparingTo("1500");
        assertThat(view.resources()).hasSize(1);
        assertThat(view.resources().get(0).available()).isTrue();
    }

    @Test
    void capacity_marks_ess_without_telemetry_unavailable_and_excludes_it() {
        VppResource blindEss = resource(20L, ASSET_ID, VppDispatchService.ESS, "ONLINE");
        when(vppResourceRepository.findByPortfolioIdAndStatus(PORTFOLIO_ID, "ONLINE"))
                .thenReturn(List.of(blindEss));
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(ASSET_ID))
                .thenReturn(java.util.Optional.empty());

        VppResourceService.CapacityView view = service.capacity(PORTFOLIO_ID);

        assertThat(view.unavailableCount()).isEqualTo(1);
        assertThat(view.adjustableUpW()).isEqualByComparingTo("0");
        assertThat(view.adjustableDownW()).isEqualByComparingTo("0");
        assertThat(view.resources().get(0).available()).isFalse();
        // 关键：遥测缺失时不回退到额定功率 5000W（powerLimit 的缺省行为在此被拦住）
        assertThat(view.resources().get(0).upW()).isEqualByComparingTo("0");
    }

    @Test
    void capacity_pv_at_night_is_zero_and_available() {
        VppResource pv = resource(30L, ASSET_ID, VppDispatchService.PV, "ONLINE");
        pv.setRatedPowerW(new BigDecimal("10000.00"));
        when(vppResourceRepository.findByPortfolioIdAndStatus(PORTFOLIO_ID, "ONLINE"))
                .thenReturn(List.of(pv));
        com.claw.server.domain.iot.TelemetryLatest night =
                com.claw.server.domain.iot.TelemetryLatest.builder()
                        .assetId(ASSET_ID)
                        .irradiance(BigDecimal.ZERO)
                        .acActivePowerW(new BigDecimal("0"))
                        .build();
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(ASSET_ID))
                .thenReturn(java.util.Optional.of(night));

        VppResourceService.CapacityView view = service.capacity(PORTFOLIO_ID);

        assertThat(view.resources().get(0).upW()).isEqualByComparingTo("0");
        assertThat(view.resources().get(0).downW()).isEqualByComparingTo("0");
        assertThat(view.resources().get(0).available()).isTrue();
    }

    @Test
    void capacity_diesel_gated_by_soc_threshold() {
        VppResource ess = resource(40L, 200L, VppDispatchService.ESS, "ONLINE");
        VppResource gen = resource(41L, 201L, VppDispatchService.DIESEL_GEN, "ONLINE");
        gen.setRatedPowerW(new BigDecimal("20000.00"));
        when(vppResourceRepository.findByPortfolioIdAndStatus(PORTFOLIO_ID, "ONLINE"))
                .thenReturn(List.of(ess, gen));
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(200L))
                .thenReturn(java.util.Optional.of(essTelemetry(
                        new BigDecimal("50"), new BigDecimal("30"), new BigDecimal("60"))));
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(201L))
                .thenReturn(java.util.Optional.empty());
        // SOC 60% ≥ 阈值 20% → 柴机不投入
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(VppResourceService.KEY_DIESEL_START_SOC))
                .thenReturn(java.util.Optional.empty());

        VppResourceService.CapacityView view = service.capacity(PORTFOLIO_ID);

        VppResourceService.ResourceCapacity genCap = view.resources().stream()
                .filter(c -> c.resourceId().equals(41L)).findFirst().orElseThrow();
        assertThat(genCap.available()).isFalse();
        assertThat(view.adjustableUpW()).isEqualByComparingTo("3000");
    }
}
