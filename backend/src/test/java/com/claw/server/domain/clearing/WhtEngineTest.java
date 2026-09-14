package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.TaxpayerStatus;
import com.claw.server.common.enums.WhtCategory;
import com.claw.server.domain.funds.VirtualSubAccount;
import com.claw.server.domain.funds.VirtualSubAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link WhtEngine} 单元测试（Mockito，无 DB）。
 * 覆盖：税率矩阵（REGISTERED 豁免 / INDIVIDUAL 15%(服务)·10%(租金) / NON_RESIDENT 14% 可配 /
 * UNREGISTERED 按类别法定税率 / NONE·DIVIDEND 0%）、零/负毛额、缺类别报错，
 * 以及「按虚拟子户 id 读取税务档案」路径（缺失→noWithholding、档案在→计算、子户不存在→报错）。
 */
@ExtendWith(MockitoExtension.class)
class WhtEngineTest {

    @Mock
    private VirtualSubAccountService virtualSubAccountService;

    @InjectMocks
    private WhtEngine whtEngine;

    /** @Value 注入在单测中无效，手动设为默认 14% 以覆盖 NON_RESIDENT 分支。 */
    @BeforeEach
    void setUp() throws Exception {
        Field f = WhtEngine.class.getDeclaredField("nonResidentRate");
        f.setAccessible(true);
        f.set(whtEngine, new BigDecimal("0.14"));
    }

    private static final BigDecimal G100 = new BigDecimal("100.0000");

    @Test
    void registered_isExempt_regardlessOfCategory() {
        WhtEngine.WhtResult r1 = whtEngine.compute(TaxpayerStatus.REGISTERED, WhtCategory.RENTAL, G100);
        assertZeroWithholding(r1, G100);
        WhtEngine.WhtResult r2 = whtEngine.compute(TaxpayerStatus.REGISTERED, WhtCategory.SERVICE, G100);
        assertZeroWithholding(r2, G100);
        WhtEngine.WhtResult r3 = whtEngine.compute(TaxpayerStatus.REGISTERED, null, G100);
        assertZeroWithholding(r3, G100);
    }

    @Test
    void individual_serviceIs15Percent_rentalIs10Percent() {
        WhtEngine.WhtResult svc = whtEngine.compute(TaxpayerStatus.INDIVIDUAL, WhtCategory.SERVICE, G100);
        assertEquals(new BigDecimal("0.15"), svc.whtRate());
        assertEquals(new BigDecimal("15.0000"), svc.whtAmount());
        assertEquals(new BigDecimal("85.0000"), svc.net());

        WhtEngine.WhtResult rent = whtEngine.compute(TaxpayerStatus.INDIVIDUAL, WhtCategory.RENTAL, G100);
        assertEquals(new BigDecimal("0.10"), rent.whtRate());
        assertEquals(new BigDecimal("10.0000"), rent.whtAmount());
        assertEquals(new BigDecimal("90.0000"), rent.net());
    }

    @Test
    void unregistered_followsStatutoryCategoryRate() {
        assertEquals(new BigDecimal("15.0000"), whtEngine.compute(TaxpayerStatus.UNREGISTERED, WhtCategory.SERVICE, G100).whtAmount());
        assertEquals(new BigDecimal("10.0000"), whtEngine.compute(TaxpayerStatus.UNREGISTERED, WhtCategory.RENTAL, G100).whtAmount());
        assertZeroWithholding(whtEngine.compute(TaxpayerStatus.UNREGISTERED, WhtCategory.DIVIDEND, G100), G100);
        assertZeroWithholding(whtEngine.compute(TaxpayerStatus.UNREGISTERED, WhtCategory.NONE, G100), G100);
    }

    @Test
    void nullStatus_treatedAsUnregistered() {
        assertEquals(new BigDecimal("15.0000"), whtEngine.compute(null, WhtCategory.SERVICE, G100).whtAmount());
    }

    @Test
    void nonResident_usesConfigurable14Percent() {
        WhtEngine.WhtResult r = whtEngine.compute(TaxpayerStatus.NON_RESIDENT, WhtCategory.SERVICE, G100);
        assertEquals(new BigDecimal("0.14"), r.whtRate());
        assertEquals(new BigDecimal("14.0000"), r.whtAmount());
        assertEquals(new BigDecimal("86.0000"), r.net());
    }

    @Test
    void unregisteredMissingCategory_throws() {
        BizException ex = assertThrows(BizException.class,
                () -> whtEngine.compute(TaxpayerStatus.UNREGISTERED, null, G100));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void zeroOrNegativeGross_isNoWithholding() {
        assertZeroWithholding(whtEngine.compute(TaxpayerStatus.INDIVIDUAL, WhtCategory.SERVICE, BigDecimal.ZERO), BigDecimal.ZERO);
        assertZeroWithholding(whtEngine.compute(TaxpayerStatus.INDIVIDUAL, WhtCategory.SERVICE, new BigDecimal("-5")), new BigDecimal("-5"));
    }

    @Test
    void nonResidentRate_scaleIsFourDecimals() {
        WhtEngine.WhtResult r = whtEngine.compute(TaxpayerStatus.NON_RESIDENT, WhtCategory.SERVICE, new BigDecimal("33.3333"));
        // 33.3333 * 0.14 = 4.666662 → 4 位四舍五入 = 4.6667
        assertEquals(new BigDecimal("4.6667"), r.whtAmount());
        assertEquals(new BigDecimal("28.6666"), r.net());
    }

    @Test
    void byVsaId_nullVsaId_isNoWithholding() {
        WhtEngine.WhtResult r = whtEngine.compute(null, G100, "USD");
        assertZeroWithholding(r, G100);
    }

    @Test
    void byVsaId_profileLoaded_computesWht() {
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .id(7L).taxpayerStatus(TaxpayerStatus.INDIVIDUAL).whtCategory(WhtCategory.RENTAL).build();
        when(virtualSubAccountService.findById(7L)).thenReturn(Optional.of(vsa));

        WhtEngine.WhtResult r = whtEngine.compute(7L, G100, "USD");
        assertEquals(new BigDecimal("10.0000"), r.whtAmount());
        assertEquals(new BigDecimal("90.0000"), r.net());
    }

    @Test
    void byVsaId_vsaNotFound_throws() {
        when(virtualSubAccountService.findById(anyLong())).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> whtEngine.compute(9L, G100, "USD"));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }

    private static void assertZeroWithholding(WhtEngine.WhtResult r, BigDecimal gross) {
        assertEquals(0, r.whtRate().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.whtAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.net().compareTo(gross));
        assertEquals(0, r.gross().compareTo(gross));
    }
}
