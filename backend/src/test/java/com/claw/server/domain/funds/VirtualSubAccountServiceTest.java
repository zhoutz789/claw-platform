package com.claw.server.domain.funds;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CustodyOwnerType;
import com.claw.server.common.enums.LocationType;
import com.claw.server.domain.ledger.AccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link VirtualSubAccountService} 单元测试（Mockito，不依赖 Docker/PG）。
 * 覆盖：幂等开户、账本账户映射、冻结/关闭、查询、参数校验。
 */
@ExtendWith(MockitoExtension.class)
class VirtualSubAccountServiceTest {

    @Mock
    private VirtualSubAccountRepository virtualSubAccountRepository;

    @Mock
    private FundsLocationRepository fundsLocationRepository;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private VirtualSubAccountService service;

    private static FundsLocation activeLocation(Long id) {
        return FundsLocation.builder()
                .id(id)
                .locationCode("ABA_PAYWAY_RESERVE")
                .institution("ABA")
                .locationType(LocationType.CLIENT_CUSTODY)
                .currency("USD")
                .status("ACTIVE")
                .deleted(false)
                .build();
    }

    // ===================== open =====================

    @Test
    void open_newOwner_createsActiveSubAccountAndMapsLedgerAccount() {
        when(virtualSubAccountRepository
                .findByOwnerTypeAndOwnerIdAndCurrencyAndFundsLocationIdAndDeletedFalse(
                        CustodyOwnerType.MANUFACTURER, 100L, "USD", 5L))
                .thenReturn(Optional.empty());
        when(fundsLocationRepository.findById(5L)).thenReturn(Optional.of(activeLocation(5L)));
        when(virtualSubAccountRepository.save(any(VirtualSubAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VirtualSubAccount vsa = service.open(CustodyOwnerType.MANUFACTURER, 100L, 7L, 5L, "USD");

        assertNotNull(vsa);
        assertEquals(CustodyOwnerType.MANUFACTURER, vsa.getOwnerType());
        assertEquals(100L, vsa.getOwnerId());
        assertEquals(7L, vsa.getOwnerUserId());
        assertEquals(5L, vsa.getFundsLocationId());
        assertEquals("USD", vsa.getCurrency());
        assertEquals(VirtualSubAccountService.STATUS_ACTIVE, vsa.getStatus());
        assertTrue(vsa.getVsaNo().startsWith("VSA-MANUFACTURER-100-USD-"));
        // 账本账户映射：ownerUserId 非空 → 确保账本账户
        verify(accountService).getOrCreateUserAccount(7L);
        verify(virtualSubAccountRepository).save(any(VirtualSubAccount.class));
    }

    @Test
    void open_existing_returnsExistingWithoutSideEffects() {
        VirtualSubAccount existing = VirtualSubAccount.builder()
                .id(42L).vsaNo("VSA-STATION-9-USD-ABCD1234")
                .ownerType(CustodyOwnerType.STATION).ownerId(9L)
                .fundsLocationId(5L).currency("USD").status("ACTIVE").deleted(false)
                .build();
        when(virtualSubAccountRepository
                .findByOwnerTypeAndOwnerIdAndCurrencyAndFundsLocationIdAndDeletedFalse(
                        CustodyOwnerType.STATION, 9L, "USD", 5L))
                .thenReturn(Optional.of(existing));

        VirtualSubAccount vsa = service.open(CustodyOwnerType.STATION, 9L, 3L, 5L, "USD");

        assertSame(existing, vsa);
        verify(virtualSubAccountRepository, never()).save(any());
        verify(accountService, never()).getOrCreateUserAccount(anyLong());
    }

    @Test
    void open_platformInternalOwnerWithoutUser_skipsLedgerMapping() {
        when(virtualSubAccountRepository
                .findByOwnerTypeAndOwnerIdAndCurrencyAndFundsLocationIdAndDeletedFalse(
                        CustodyOwnerType.LOGISTICS, 55L, "USD", 5L))
                .thenReturn(Optional.empty());
        when(fundsLocationRepository.findById(5L)).thenReturn(Optional.of(activeLocation(5L)));
        when(virtualSubAccountRepository.save(any(VirtualSubAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VirtualSubAccount vsa = service.open(CustodyOwnerType.LOGISTICS, 55L, null, 5L, "USD");

        assertNull(vsa.getOwnerUserId());
        verify(accountService, never()).getOrCreateUserAccount(anyLong());
        verify(virtualSubAccountRepository).save(any(VirtualSubAccount.class));
    }

    @Test
    void open_missingLocation_throwsNotFound() {
        when(virtualSubAccountRepository
                .findByOwnerTypeAndOwnerIdAndCurrencyAndFundsLocationIdAndDeletedFalse(
                        CustodyOwnerType.MANUFACTURER, 100L, "USD", 999L))
                .thenReturn(Optional.empty());
        when(fundsLocationRepository.findById(999L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.open(CustodyOwnerType.MANUFACTURER, 100L, 7L, 999L, "USD"));

        assertEquals(BizException.NOT_FOUND, ex.getCode());
        verify(virtualSubAccountRepository, never()).save(any());
    }

    @Test
    void open_nullOwnerType_throwsInvalidParam() {
        BizException ex = assertThrows(BizException.class,
                () -> service.open(null, 100L, 7L, 5L, "USD"));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        verify(virtualSubAccountRepository, never()).save(any());
    }

    @Test
    void open_nullOwnerId_throwsInvalidParam() {
        BizException ex = assertThrows(BizException.class,
                () -> service.open(CustodyOwnerType.MANUFACTURER, null, 7L, 5L, "USD"));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void open_nullLocationId_throwsInvalidParam() {
        BizException ex = assertThrows(BizException.class,
                () -> service.open(CustodyOwnerType.MANUFACTURER, 100L, 7L, null, "USD"));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void open_blankCurrency_defaultsToUsd() {
        when(virtualSubAccountRepository
                .findByOwnerTypeAndOwnerIdAndCurrencyAndFundsLocationIdAndDeletedFalse(
                        CustodyOwnerType.MANUFACTURER, 100L, "USD", 5L))
                .thenReturn(Optional.empty());
        when(fundsLocationRepository.findById(5L)).thenReturn(Optional.of(activeLocation(5L)));
        when(virtualSubAccountRepository.save(any(VirtualSubAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VirtualSubAccount vsa = service.open(CustodyOwnerType.MANUFACTURER, 100L, null, 5L, "   ");

        assertEquals("USD", vsa.getCurrency());
    }

    // ===================== freeze =====================

    @Test
    void freeze_active_setsFrozen() {
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .id(1L).vsaNo("VSA-USER-1-USD-AAAA1111").status("ACTIVE").deleted(false).build();
        when(virtualSubAccountRepository.findById(1L)).thenReturn(Optional.of(vsa));
        when(virtualSubAccountRepository.save(any(VirtualSubAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VirtualSubAccount result = service.freeze(1L, "风控拦截");

        assertEquals(VirtualSubAccountService.STATUS_FROZEN, result.getStatus());
        verify(virtualSubAccountRepository).save(vsa);
    }

    @Test
    void freeze_alreadyFrozen_returnsAsIsWithoutSave() {
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .id(1L).vsaNo("VSA-USER-1-USD-AAAA1111").status("FROZEN").deleted(false).build();
        when(virtualSubAccountRepository.findById(1L)).thenReturn(Optional.of(vsa));

        VirtualSubAccount result = service.freeze(1L, "重复冻结");

        assertEquals(VirtualSubAccountService.STATUS_FROZEN, result.getStatus());
        verify(virtualSubAccountRepository, never()).save(any());
    }

    @Test
    void freeze_closed_throws() {
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .id(1L).vsaNo("VSA-USER-1-USD-AAAA1111").status("CLOSED").deleted(false).build();
        when(virtualSubAccountRepository.findById(1L)).thenReturn(Optional.of(vsa));

        BizException ex = assertThrows(BizException.class, () -> service.freeze(1L, "已关闭"));

        assertEquals(40902, ex.getCode());
        verify(virtualSubAccountRepository, never()).save(any());
    }

    @Test
    void freeze_notFound_throws() {
        when(virtualSubAccountRepository.findById(88L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.freeze(88L, "不存在"));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }

    // ===================== close =====================

    @Test
    void close_active_setsClosed() {
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .id(1L).vsaNo("VSA-USER-1-USD-AAAA1111").status("ACTIVE").deleted(false).build();
        when(virtualSubAccountRepository.findById(1L)).thenReturn(Optional.of(vsa));
        when(virtualSubAccountRepository.save(any(VirtualSubAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VirtualSubAccount result = service.close(1L);

        assertEquals(VirtualSubAccountService.STATUS_CLOSED, result.getStatus());
        verify(virtualSubAccountRepository).save(vsa);
    }

    @Test
    void close_alreadyClosed_returnsAsIsWithoutSave() {
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .id(1L).vsaNo("VSA-USER-1-USD-AAAA1111").status("CLOSED").deleted(false).build();
        when(virtualSubAccountRepository.findById(1L)).thenReturn(Optional.of(vsa));

        VirtualSubAccount result = service.close(1L);

        assertEquals(VirtualSubAccountService.STATUS_CLOSED, result.getStatus());
        verify(virtualSubAccountRepository, never()).save(any());
    }

    // ===================== find =====================

    @Test
    void find_returnsFirstMatch() {
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .id(1L).vsaNo("VSA-STATION-9-USD-AAAA1111")
                .ownerType(CustodyOwnerType.STATION).ownerId(9L).currency("USD").deleted(false).build();
        when(virtualSubAccountRepository
                .findByOwnerTypeAndOwnerIdAndCurrencyAndDeletedFalse(CustodyOwnerType.STATION, 9L, "USD"))
                .thenReturn(java.util.List.of(vsa));

        Optional<VirtualSubAccount> found = service.find(CustodyOwnerType.STATION, 9L, "USD");

        assertTrue(found.isPresent());
        assertEquals(1L, found.get().getId());
    }

    @Test
    void find_nullArgs_returnsEmptyWithoutQuery() {
        assertTrue(service.find(null, 9L, "USD").isEmpty());
        assertTrue(service.find(CustodyOwnerType.STATION, null, "USD").isEmpty());
        verify(virtualSubAccountRepository, never())
                .findByOwnerTypeAndOwnerIdAndCurrencyAndDeletedFalse(any(), anyLong(), anyString());
    }

    @Test
    void get_deleted_throws() {
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .id(1L).vsaNo("VSA-USER-1-USD-AAAA1111").status("ACTIVE").deleted(true).build();
        when(virtualSubAccountRepository.findById(1L)).thenReturn(Optional.of(vsa));
        BizException ex = assertThrows(BizException.class, () -> service.get(1L));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }
}
