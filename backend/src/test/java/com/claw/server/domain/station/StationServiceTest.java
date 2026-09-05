package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.ContractStatus;
import com.claw.server.domain.contract.ContractService;
import com.claw.server.domain.contract.StationContract;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.onboarding.OnboardingDeposit;
import com.claw.server.domain.onboarding.OnboardingDepositRepository;
import com.claw.server.domain.onboarding.OnboardingDepositTier;
import com.claw.server.domain.onboarding.OnboardingDepositTierRepository;
import com.claw.server.domain.station.StationService.StationUpgradeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StationService#upgradeTier} 单元测试（缺口① · 追加保证金升档，不依赖 Spring / PG）。
 * 覆盖：① 升档→信用按新档×4 放大、旧约续签、追加保证金入账；② 新档位不高于当前档位时拒绝。
 */
class StationServiceTest {

    @Mock
    private StationRepository stationRepository;
    @Mock
    private StationStockRepository stockRepository;
    @Mock
    private StationBatteryRepository batteryRepository;
    @Mock
    private OnboardingDepositTierRepository tierRepository;
    @Mock
    private OnboardingDepositRepository depositRepository;
    @Mock
    private CreditLimitService creditLimitService;
    @Mock
    private ContractService contractService;

    @InjectMocks
    private StationService stationService;

    private Station station;
    private OnboardingDepositTier curTier;
    private OnboardingDepositTier nextTier;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        station = Station.builder().id(10L).depositTierId(1L).onboardingApplicationId(5L).build();
        curTier = OnboardingDepositTier.builder().id(1L).depositAmount(new BigDecimal("5000.00")).build();
        nextTier = OnboardingDepositTier.builder().id(2L).depositAmount(new BigDecimal("20000.00"))
                .enabled(true).build();
    }

    @Test
    @DisplayName("升档：信用按新档×4放大，旧约续签，追加保证金入账")
    void upgradeTier_upgradesTierAndRenewsContract() {
        when(stationRepository.findById(10L)).thenReturn(Optional.of(station));
        when(tierRepository.findById(1L)).thenReturn(Optional.of(curTier));
        when(tierRepository.findById(2L)).thenReturn(Optional.of(nextTier));
        when(creditLimitService.resolveTierCreditLimit(nextTier)).thenReturn(new BigDecimal("80000.00"));
        StationContract newContract = StationContract.builder().id(2L).contractNo("CT20260906ABCD")
                .status(ContractStatus.ACTIVE).build();
        when(contractService.renewOnUpgrade(10L, 2L, new BigDecimal("20000.00"),
                new BigDecimal("80000.00"), 7L)).thenReturn(newContract);

        StationUpgradeResult r = stationService.upgradeTier(10L, 2L, 7L);

        assertEquals(0, new BigDecimal("80000.00").compareTo(r.newCreditLimit()));
        assertEquals(0, new BigDecimal("15000.00").compareTo(r.additionalDeposit())); // 20000 - 5000
        assertEquals("CT20260906ABCD", r.newContractNo());
        // 写回档位与额度（项目扩大）
        assertEquals(2L, station.getDepositTierId());
        assertEquals(0, new BigDecimal("80000.00").compareTo(station.getCreditLimit()));
        verify(contractService).renewOnUpgrade(10L, 2L, new BigDecimal("20000.00"),
                new BigDecimal("80000.00"), 7L);
        // 追加保证金入账（CONFIRMED）
        ArgumentCaptor<OnboardingDeposit> cap = ArgumentCaptor.forClass(OnboardingDeposit.class);
        verify(depositRepository).save(cap.capture());
        assertEquals(0, new BigDecimal("15000.00").compareTo(cap.getValue().getAmount()));
        assertEquals(OnboardingDeposit.Status.CONFIRMED.name(), cap.getValue().getStatus());
        assertEquals(10L, cap.getValue().getPrincipalId());
    }

    @Test
    @DisplayName("升档拒绝：新档位保证金不高于当前档位")
    void upgradeTier_rejectsNotHigher() {
        OnboardingDepositTier lower = OnboardingDepositTier.builder().id(2L)
                .depositAmount(new BigDecimal("5000.00")).enabled(true).build();
        when(stationRepository.findById(10L)).thenReturn(Optional.of(station));
        when(tierRepository.findById(1L)).thenReturn(Optional.of(curTier));
        when(tierRepository.findById(2L)).thenReturn(Optional.of(lower));

        assertThrows(BizException.class, () -> stationService.upgradeTier(10L, 2L, 7L));
        verify(contractService, never()).renewOnUpgrade(anyLong(), anyLong(), any(), any(), anyLong());
        verify(depositRepository, never()).save(any());
    }

    @Test
    @DisplayName("升档拒绝：目标档位被禁用")
    void upgradeTier_rejectsDisabledTier() {
        OnboardingDepositTier disabled = OnboardingDepositTier.builder().id(2L)
                .depositAmount(new BigDecimal("20000.00")).enabled(false).build();
        when(stationRepository.findById(10L)).thenReturn(Optional.of(station));
        when(tierRepository.findById(1L)).thenReturn(Optional.of(curTier));
        when(tierRepository.findById(2L)).thenReturn(Optional.of(disabled));

        assertThrows(BizException.class, () -> stationService.upgradeTier(10L, 2L, 7L));
        verify(contractService, never()).renewOnUpgrade(anyLong(), anyLong(), any(), any(), anyLong());
    }
}
