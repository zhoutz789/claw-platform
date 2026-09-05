package com.claw.server.domain.credit;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryRepository;
import com.claw.server.domain.manufacturer.ProductSkuRepository;
import com.claw.server.domain.onboarding.OnboardingCreditBlock;
import com.claw.server.domain.onboarding.OnboardingCreditBlockService;
import com.claw.server.domain.onboarding.OnboardingDepositTier;
import com.claw.server.domain.onboarding.OnboardingDepositTierRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.claw.server.domain.station.Station;
import com.claw.server.domain.station.StationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 授信额度校验单元测试（增量 C · §4 AC ①②③）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>额度 = 保证金 × 倍率，倍率取自档位列（<b>不硬编码</b>），绝对额度覆盖优先；</li>
 *   <li>默认倍率取自 system_config（可配）；</li>
 *   <li>历史站点 credit_limit 为 NULL 时<b>放行不校验</b>（Q18）；</li>
 *   <li>超限抛 40941 并落 onboarding_credit_blocks 留痕；</li>
 *   <li>货值缺失时抛 40942（失败关闭，不静默按 0 处理）；</li>
 *   <li>C4 软预检只告警不抛异常。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreditLimitServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private StationRepository stationRepository;
    @Mock
    private ProductSkuRepository productSkuRepository;
    @Mock
    private OnboardingCreditBlockService creditBlockService;
    @Mock
    private OnboardingDepositTierRepository tierRepository;
    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private CreditLimitService creditLimitService;

    private OnboardingDepositTier tier(BigDecimal deposit, BigDecimal multiplier, BigDecimal override) {
        return OnboardingDepositTier.builder()
                .id(1L).applicantType("STATION").tierCode("STANDARD")
                .depositAmount(deposit)
                .creditMultiplier(multiplier)
                .creditLimitOverride(override)
                .build();
    }

    private Inventory inv(Long deviceId, BigDecimal unitValue, LifecycleStatus status, Long stationId) {
        return Inventory.builder()
                .id(deviceId).deviceId(deviceId)
                .unitValue(unitValue)
                .currentStatus(status)
                .holderStationId(stationId)
                .ownershipType(OwnershipType.CONSIGNED)
                .productId(99L)
                .build();
    }

    // ---------- 1) 额度计算：倍率可配、覆盖优先 ----------

    @Test
    void resolveTierCreditLimit_multipliesDepositByMultiplier() {
        // 20,000 × 3 = 60,000
        BigDecimal limit = creditLimitService.resolveTierCreditLimit(
                tier(new BigDecimal("20000.00"), new BigDecimal("3.0000"), null));
        assertEquals(0, new BigDecimal("60000.00").compareTo(limit));

        // 改倍率为 5 → 100,000（倍率改成可配参数，改数字即生效）
        limit = creditLimitService.resolveTierCreditLimit(
                tier(new BigDecimal("20000.00"), new BigDecimal("5.0000"), null));
        assertEquals(0, new BigDecimal("100000.00").compareTo(limit));

        // 5,000 × 3 = 15,000 / 50,000 × 3 = 150,000
        assertEquals(0, new BigDecimal("15000.00").compareTo(creditLimitService.resolveTierCreditLimit(
                tier(new BigDecimal("5000.00"), new BigDecimal("3.0000"), null))));
        assertEquals(0, new BigDecimal("150000.00").compareTo(creditLimitService.resolveTierCreditLimit(
                tier(new BigDecimal("50000.00"), new BigDecimal("3.0000"), null))));
    }

    @Test
    void resolveTierCreditLimit_overrideWins() {
        BigDecimal limit = creditLimitService.resolveTierCreditLimit(
                tier(new BigDecimal("20000.00"), new BigDecimal("3.0000"), new BigDecimal("123456.78")));
        assertEquals(0, new BigDecimal("123456.78").compareTo(limit), "绝对额度覆盖应绕过倍率");
    }

    @Test
    void defaultMultiplier_readFromSystemConfig() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("ONBOARDING_CREDIT_MULTIPLIER_DEFAULT"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("ONBOARDING_CREDIT_MULTIPLIER_DEFAULT").configValue("7").build()));
        assertEquals(0, new BigDecimal("7").compareTo(creditLimitService.defaultMultiplier()));
    }

    @Test
    void defaultMultiplier_fallsBackWhenConfigMissing() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("ONBOARDING_CREDIT_MULTIPLIER_DEFAULT"))
                .thenReturn(Optional.empty());
        assertEquals(0, new BigDecimal("4").compareTo(creditLimitService.defaultMultiplier()));
    }

    // ---------- 2) 历史站点未设额度 → 放行 ----------

    @Test
    void assertWithinLimit_passesWhenCreditLimitNull() {
        when(stationRepository.findById(7L)).thenReturn(Optional.of(Station.builder().id(7L).build()));
        when(inventoryRepository.findByDeviceId(1L)).thenReturn(Optional.of(inv(1L, new BigDecimal("100"),
                LifecycleStatus.IN_FACTORY, null)));
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of());

        creditLimitService.assertWithinLimit(7L, List.of(1L), OnboardingCreditBlock.Scene.CONSIGN_SHIP);
        verify(creditBlockService, never()).record(any(), anyLong(), any(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    // ---------- 3) 超限硬阻断 + 留痕 ----------

    @Test
    void assertWithinLimit_blocksAndRecordsWhenOverLimit() {
        Station station = Station.builder().id(7L).creditLimit(new BigDecimal("150000.00")).build();
        when(stationRepository.findById(7L)).thenReturn(Optional.of(station));
        // 已占用 142,000：在途 + 在库各占一半；SOLD / IN_USER_PROJECT 不计
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of(
                        inv(101L, new BigDecimal("100000"), LifecycleStatus.AT_STATION, 7L),
                        inv(102L, new BigDecimal("42000"), LifecycleStatus.IN_TRANSIT, 7L),
                        inv(103L, new BigDecimal("99999"), LifecycleStatus.IN_USER_PROJECT, 7L)));
        // 本次入站 20,000
        when(inventoryRepository.findByDeviceId(1L))
                .thenReturn(Optional.of(inv(1L, new BigDecimal("20000"), LifecycleStatus.IN_FACTORY, null)));

        BizException ex = assertThrows(BizException.class, () -> creditLimitService
                .assertWithinLimit(7L, List.of(1L), OnboardingCreditBlock.Scene.CONSIGN_SHIP));
        assertEquals(40941, ex.getCode());
        assertEquals("credit.limit.exceeded", ex.getMessageCode());
        // 缺口 = 142000 + 20000 - 150000 = 12000
        assertEquals("12000.00", ex.getArgs()[3]);
        verify(creditBlockService, times(1)).record(any(), anyLong(), any(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void assertWithinLimit_passesWhenWithinLimit() {
        Station station = Station.builder().id(7L).creditLimit(new BigDecimal("150000.00")).build();
        when(stationRepository.findById(7L)).thenReturn(Optional.of(station));
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of(inv(101L, new BigDecimal("1000"), LifecycleStatus.AT_STATION, 7L)));
        when(inventoryRepository.findByDeviceId(1L))
                .thenReturn(Optional.of(inv(1L, new BigDecimal("2000"), LifecycleStatus.IN_FACTORY, null)));

        creditLimitService.assertWithinLimit(7L, List.of(1L), OnboardingCreditBlock.Scene.CONSIGN_SHIP);
        verify(creditBlockService, never()).record(any(), anyLong(), any(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    // ---------- 4) 失败关闭：货值缺失 ----------

    @Test
    void assertWithinLimit_failsClosedWhenUnitValueMissing() {
        Station station = Station.builder().id(7L).creditLimit(new BigDecimal("150000.00")).build();
        when(stationRepository.findById(7L)).thenReturn(Optional.of(station));
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of());
        // unit_value 为 NULL 且 SKU 查不到价格 → 抛 40942，不静默按 0 处理
        when(inventoryRepository.findByDeviceId(1L))
                .thenReturn(Optional.of(inv(1L, null, LifecycleStatus.IN_FACTORY, null)));
        when(productSkuRepository.findAll()).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> creditLimitService
                .assertWithinLimit(7L, List.of(1L), OnboardingCreditBlock.Scene.CONSIGN_SHIP));
        assertEquals(40942, ex.getCode());
        assertEquals("inventory.unit_value.required", ex.getMessageCode());
    }

    // ---------- 5) C4 软预检只告警 ----------

    @Test
    void softPrecheck_returnsWarningWithoutBlocking() {
        Station station = Station.builder().id(7L).creditLimit(new BigDecimal("150000.00")).build();
        when(stationRepository.findById(7L)).thenReturn(Optional.of(station));
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of(inv(101L, new BigDecimal("145000"), LifecycleStatus.AT_STATION, 7L)));
        when(inventoryRepository.findByDeviceId(1L))
                .thenReturn(Optional.of(inv(1L, new BigDecimal("20000"), LifecycleStatus.IN_FACTORY, null)));

        Optional<String> warn = creditLimitService.softPrecheck(7L, List.of(1L), null);
        assertTrue(warn.isPresent());
        assertTrue(warn.get().contains("超出授信额度"));
        assertTrue(warn.get().contains("升档"), "提示须含可执行建议：引导升档补差价");
        // 软预检同样落阻断记录（scene=TRANSFER_CREATE），但不抛异常
        verify(creditBlockService, times(1)).record(any(), anyLong(), any(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void softPrecheck_emptyWhenWithinLimit() {
        Station station = Station.builder().id(7L).creditLimit(new BigDecimal("150000.00")).build();
        when(stationRepository.findById(7L)).thenReturn(Optional.of(station));
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of());
        when(inventoryRepository.findByDeviceId(1L))
                .thenReturn(Optional.of(inv(1L, new BigDecimal("100"), LifecycleStatus.IN_FACTORY, null)));

        assertTrue(creditLimitService.softPrecheck(7L, List.of(1L), null).isEmpty());
    }

    // ---------- 6) 占用口径 ----------

    @Test
    void usedValue_countsOnlyConsignedInTransitAndAtStation() {
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of(
                        inv(1L, new BigDecimal("100"), LifecycleStatus.IN_TRANSIT, 7L),
                        inv(2L, new BigDecimal("200"), LifecycleStatus.AT_STATION, 7L),
                        inv(3L, new BigDecimal("300"), LifecycleStatus.SOLD, 7L),
                        inv(4L, new BigDecimal("400"), LifecycleStatus.IN_USER_PROJECT, 7L),
                        inv(5L, new BigDecimal("500"), LifecycleStatus.RECALLED, 7L)));
        // 只有 IN_TRANSIT(100) + AT_STATION(200) 计入
        assertEquals(0, new BigDecimal("300.00").compareTo(creditLimitService.usedValue(7L)));
        assertEquals(2, creditLimitService.usedDeviceCount(7L));
    }

    @Test
    void stampUnitValue_marksSourceAndFailsClosed() {
        Inventory target = inv(1L, null, LifecycleStatus.IN_FACTORY, null);
        when(productSkuRepository.findAll()).thenReturn(List.of());
        assertThrows(BizException.class, () -> creditLimitService.stampUnitValue(target, null, null));

        creditLimitService.stampUnitValue(target, new BigDecimal("1234.5"), "USD");
        assertEquals(0, new BigDecimal("1234.50").compareTo(target.getUnitValue()));
        assertEquals("USD", target.getValueCurrency());
        assertEquals("MANUAL", target.getUnitValueSource());
    }

    @Test
    void creditUsage_returnsOverLimitFlag() {
        Station station = Station.builder().id(7L).creditLimit(new BigDecimal("100.00"))
                .depositTierId(5L).build();
        when(stationRepository.findById(7L)).thenReturn(Optional.of(station));
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of(inv(1L, new BigDecimal("150"), LifecycleStatus.AT_STATION, 7L)));
        when(tierRepository.findById(5L)).thenReturn(Optional.of(OnboardingDepositTier.builder()
                .id(5L).tierCode("PREMIUM").tierName("第三档").build()));

        CreditUsageView view = creditLimitService.creditUsage(7L);
        assertEquals(Boolean.TRUE, view.overLimit());
        assertEquals("PREMIUM", view.tierCode());
        assertEquals(1, view.deviceCount());
    }

    @Test
    void creditScope_defaultsToStation() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(eq("ONBOARDING_CREDIT_SCOPE")))
                .thenReturn(Optional.empty());
        assertEquals(com.claw.server.common.enums.CreditScope.STATION, creditLimitService.creditScope());
    }

    @Test
    void singleDeviceOverLimit_recordsDeviceCount() {
        Station station = Station.builder().id(7L).creditLimit(new BigDecimal("10.00")).build();
        when(stationRepository.findById(7L)).thenReturn(Optional.of(station));
        when(inventoryRepository.findByHolderStationIdAndOwnershipType(7L, OwnershipType.CONSIGNED))
                .thenReturn(List.of());
        when(inventoryRepository.findByDeviceId(anyLong()))
                .thenReturn(Optional.of(inv(1L, new BigDecimal("99"), LifecycleStatus.IN_FACTORY, null)));
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(anyString())).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> creditLimitService
                .assertWithinLimit(7L, List.of(1L), OnboardingCreditBlock.Scene.TRANSFER_IN));
        verify(creditBlockService).record(any(), anyLong(), any(), any(), any(), anyInt(), any(), any(), any(), any());
    }
}
