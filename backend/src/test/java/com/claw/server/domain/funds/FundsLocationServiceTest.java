package com.claw.server.domain.funds;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.LocationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link FundsLocationService} 单元测试（Mockito，不依赖 Docker/PG）。
 * 覆盖：注册校验、覆盖科目解析、读侧辅助、对账锚点、状态更新。
 */
@ExtendWith(MockitoExtension.class)
class FundsLocationServiceTest {

    @Mock
    private FundsLocationRepository fundsLocationRepository;

    @InjectMocks
    private FundsLocationService service;

    private static FundsLocation newLocation(String covers) {
        return FundsLocation.builder()
                .locationCode("ABA_PAYWAY_RESERVE")
                .institution("ABA")
                .locationType(LocationType.CLIENT_CUSTODY)
                .coversAccountTypes(covers)
                .build();
    }

    // ===================== register =====================

    @Test
    void register_success_appliesDefaultsAndSaves() {
        when(fundsLocationRepository.existsByLocationCodeAndDeletedFalse("ABA_PAYWAY_RESERVE"))
                .thenReturn(false);
        when(fundsLocationRepository.save(any(FundsLocation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        FundsLocation saved = service.register(newLocation("PAYABLE_MFG, PLATFORM_REVENUE"));

        assertEquals("USD", saved.getCurrency());
        assertEquals(FundsLocationService.STATUS_ACTIVE, saved.getStatus());
        assertEquals(1L, saved.getTenantId());
        assertFalse(saved.getDeleted());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        verify(fundsLocationRepository).save(any(FundsLocation.class));
    }

    @Test
    void register_duplicateCode_throwsConflict() {
        when(fundsLocationRepository.existsByLocationCodeAndDeletedFalse("ABA_PAYWAY_RESERVE"))
                .thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.register(newLocation(null)));

        assertEquals(40960, ex.getCode());
        verify(fundsLocationRepository, never()).save(any());
    }

    @Test
    void register_missingCode_throwsInvalidParam() {
        FundsLocation loc = newLocation(null);
        loc.setLocationCode("  ");
        BizException ex = assertThrows(BizException.class, () -> service.register(loc));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void register_null_throwsInvalidParam() {
        BizException ex = assertThrows(BizException.class, () -> service.register(null));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void register_missingInstitution_throwsInvalidParam() {
        FundsLocation loc = newLocation(null);
        loc.setInstitution(null);
        BizException ex = assertThrows(BizException.class, () -> service.register(loc));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void register_missingLocationType_throwsInvalidParam() {
        FundsLocation loc = newLocation(null);
        loc.setLocationType(null);
        BizException ex = assertThrows(BizException.class, () -> service.register(loc));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void register_invalidCoveredAccountType_throwsInvalidParam() {
        when(fundsLocationRepository.existsByLocationCodeAndDeletedFalse("ABA_PAYWAY_RESERVE"))
                .thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> service.register(newLocation("PAYABLE_MFG,BOGUS_TYPE")));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        verify(fundsLocationRepository, never()).save(any());
    }

    // ===================== coveredAccountTypes =====================

    @Test
    void coveredAccountTypes_parsesCsv() {
        FundsLocation loc = newLocation("PAYABLE_MFG, PAYABLE_STATION,PLATFORM_REVENUE");
        loc.setId(5L);
        when(fundsLocationRepository.findById(5L)).thenReturn(Optional.of(loc));

        List<AccountType> types = service.coveredAccountTypes(5L);

        assertEquals(List.of(AccountType.PAYABLE_MFG, AccountType.PAYABLE_STATION,
                AccountType.PLATFORM_REVENUE), types);
    }

    @Test
    void coveredAccountTypes_skipsUnknownTokensLeniently() {
        FundsLocation loc = newLocation("PAYABLE_MFG,BOGUS_TYPE");
        loc.setId(5L);
        when(fundsLocationRepository.findById(5L)).thenReturn(Optional.of(loc));

        List<AccountType> types = service.coveredAccountTypes(5L);

        assertEquals(List.of(AccountType.PAYABLE_MFG), types);
    }

    @Test
    void coveredAccountTypes_blankCsv_returnsEmpty() {
        FundsLocation loc = newLocation(null);
        loc.setId(5L);
        when(fundsLocationRepository.findById(5L)).thenReturn(Optional.of(loc));

        assertTrue(service.coveredAccountTypes(5L).isEmpty());
    }

    // ===================== read helpers =====================

    @Test
    void findByCode_blank_returnsEmptyWithoutQuery() {
        assertTrue(service.findByCode("  ").isEmpty());
        verify(fundsLocationRepository, never()).findByLocationCodeAndDeletedFalse(anyString());
    }

    @Test
    void getByCode_notFound_throws() {
        when(fundsLocationRepository.findByLocationCodeAndDeletedFalse("NOPE"))
                .thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.getByCode("NOPE"));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }

    @Test
    void getByCode_blank_throwsInvalidParam() {
        BizException ex = assertThrows(BizException.class, () -> service.getByCode(null));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void getById_deleted_throws() {
        FundsLocation loc = newLocation(null);
        loc.setId(9L);
        loc.setDeleted(true);
        when(fundsLocationRepository.findById(9L)).thenReturn(Optional.of(loc));

        BizException ex = assertThrows(BizException.class, () -> service.getById(9L));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }

    @Test
    void markReconciled_setsLastReconciledAt() {
        FundsLocation loc = newLocation(null);
        loc.setId(3L);
        when(fundsLocationRepository.findById(3L)).thenReturn(Optional.of(loc));
        when(fundsLocationRepository.save(any(FundsLocation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        FundsLocation result = service.markReconciled(3L);

        assertNotNull(result.getLastReconciledAt());
        verify(fundsLocationRepository).save(loc);
    }

    @Test
    void updateStatus_setsStatus() {
        FundsLocation loc = newLocation(null);
        loc.setId(3L);
        when(fundsLocationRepository.findById(3L)).thenReturn(Optional.of(loc));
        when(fundsLocationRepository.save(any(FundsLocation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        FundsLocation result = service.updateStatus(3L, "INACTIVE");

        assertEquals("INACTIVE", result.getStatus());
        verify(fundsLocationRepository).save(loc);
    }

    @Test
    void listActiveByTypeAndCurrency_defaultsCurrency() {
        FundsLocation loc = newLocation(null);
        loc.setId(1L);
        when(fundsLocationRepository.findByLocationTypeAndCurrencyAndStatusAndDeletedFalse(
                LocationType.CLIENT_CUSTODY, "USD", "ACTIVE"))
                .thenReturn(List.of(loc));

        List<FundsLocation> result = service.listActiveByTypeAndCurrency(LocationType.CLIENT_CUSTODY, null);

        assertEquals(1, result.size());
    }
}
