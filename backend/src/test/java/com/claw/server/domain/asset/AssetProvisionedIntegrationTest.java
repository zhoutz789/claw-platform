package com.claw.server.domain.asset;

import com.claw.server.common.dto.AssetRequests.BindDevice;
import com.claw.server.domain.order.event.AssetProvisionedEvent;
import com.claw.server.domain.sharedpool.SharedPoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AssetProvisionedIntegration 单测（Mockito，无真实数据库）。
 * 覆盖 V38 资产闭环接线：① SHARED+站点 → 入池；② 自营+站点 → 绑定上线；
 * ③ 自营无站点 → 留库待激活（不入池不绑定）；④ assetId 缺失 → 早退。
 */
@ExtendWith(MockitoExtension.class)
class AssetProvisionedIntegrationTest {

    @Mock private AssetService assetService;
    @Mock private SharedPoolService sharedPoolService;

    @InjectMocks private AssetProvisionedIntegration integration;

    private AssetProvisionedEvent baseEvent(String usageMode, Long stationId) {
        return new AssetProvisionedEvent(
                1L, 10L, 99L, "VEHICLE",
                2L, 5L, 3L, 5L,
                usageMode, stationId,
                "QR1", "V1", "F1", "M1",
                "[{\"type\":\"BATTERY\",\"no\":\"B1\"}]");
    }

    @Test
    void onShared_with_station_pools_asset() {
        AssetProvisionedEvent e = baseEvent("SHARED", 20L);
        integration.onAssetProvisioned(e);

        ArgumentCaptor<Long> assetIdCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> ownerCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> stationCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<BigDecimal> ownerRateCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> stationRateCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(sharedPoolService).poolAsset(assetIdCap.capture(), ownerCap.capture(),
                stationCap.capture(), ownerRateCap.capture(), stationRateCap.capture(),
                any(), any());
        assertEquals(99L, assetIdCap.getValue());
        assertEquals(5L, ownerCap.getValue());
        assertEquals(20L, stationCap.getValue());
        assertEquals(new BigDecimal("0.70"), ownerRateCap.getValue());
        assertEquals(new BigDecimal("0.15"), stationRateCap.getValue());
        verify(assetService, never()).bindDevice(any(), anyLong());
    }

    @Test
    void onSelf_with_station_binds_device() {
        AssetProvisionedEvent e = baseEvent("SELF", 20L);
        integration.onAssetProvisioned(e);

        ArgumentCaptor<BindDevice> cap = ArgumentCaptor.forClass(BindDevice.class);
        verify(assetService).bindDevice(cap.capture(), eq(5L));
        BindDevice bd = cap.getValue();
        assertEquals(99L, bd.assetId());
        assertEquals(20L, bd.stationId());
        verify(sharedPoolService, never()).poolAsset(anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void onSelf_without_station_stays_in_stock() {
        AssetProvisionedEvent e = baseEvent("SELF", null);
        integration.onAssetProvisioned(e);

        verify(sharedPoolService, never()).poolAsset(anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any());
        verify(assetService, never()).bindDevice(any(), anyLong());
    }

    @Test
    void onNullAssetId_returns_early() {
        AssetProvisionedEvent e = new AssetProvisionedEvent(
                1L, 10L, null, "VEHICLE",
                2L, 5L, 3L, 5L,
                "SELF", 20L,
                "QR1", "V1", "F1", "M1", null);
        integration.onAssetProvisioned(e);

        verify(sharedPoolService, never()).poolAsset(anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any());
        verify(assetService, never()).bindDevice(any(), anyLong());
    }

    @Test
    void onShared_without_station_skips_pooling() {
        AssetProvisionedEvent e = baseEvent("SHARED", null);
        integration.onAssetProvisioned(e);

        verify(sharedPoolService, never()).poolAsset(anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any());
        verify(assetService, never()).bindDevice(any(), anyLong());
    }
}
