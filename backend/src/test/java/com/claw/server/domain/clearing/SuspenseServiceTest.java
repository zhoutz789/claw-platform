package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.SuspenseDiffType;
import com.claw.server.common.enums.SuspenseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 差错挂账服务单测：五类差异落账 + resolve 冲销/核销 + 已处置不可重复。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SuspenseServiceTest {

    @Mock private SuspenseEntryRepository suspenseEntryRepository;
    @InjectMocks private SuspenseService service;

    @BeforeEach
    void setUp() {
        when(suspenseEntryRepository.save(any(SuspenseEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("record：五类差异类型均可落账且初始 OPEN、金额符号保留")
    void record_five_diff_types() {
        for (SuspenseDiffType type : SuspenseDiffType.values()) {
            SuspenseEntry saved = service.record(type.name(), "CH-1", "LED-1",
                    new BigDecimal("5.00"), "USD", 1L);
            assertEquals(type.name(), saved.getDiffType());
            assertEquals(SuspenseStatus.OPEN, saved.getStatus());
            assertNotNull(saved.getEntryNo());
        }
    }

    @Test
    @DisplayName("record：长款（正）/短款（负）金额原样落账")
    void record_long_short_amount_sign() {
        SuspenseEntry longEntry = service.record(SuspenseDiffType.AMOUNT_MISMATCH.name(), "C", "L",
                new BigDecimal("12.34"), "USD", null);
        SuspenseEntry shortEntry = service.record(SuspenseDiffType.AMOUNT_MISMATCH.name(), "C", "L",
                new BigDecimal("-9.87"), "USD", null);
        assertEquals(0, new BigDecimal("12.34").compareTo(longEntry.getAmount()));
        assertEquals(0, new BigDecimal("-9.87").compareTo(shortEntry.getAmount()));
    }

    @Test
    @DisplayName("record：非法 diffType 抛参数异常")
    void record_invalid_diff_type_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.record("NONSENSE", "C", "L", BigDecimal.ONE, "USD", null));
        assertTrue(ex.getMessage().contains("error.clearing.request.invalid"));
    }

    @Test
    @DisplayName("resolve：OPEN → RESOLVED，且不可重复处置")
    void resolve_then_already_resolved() {
        SuspenseEntry open = SuspenseEntry.builder().id(1L).entryNo("SP-1")
                .diffType(SuspenseDiffType.CHANNEL_EXTRA.name()).status(SuspenseStatus.OPEN).build();
        when(suspenseEntryRepository.findById(1L)).thenReturn(Optional.of(open));

        SuspenseEntry resolved = service.resolve(1L, 7L, "补录确认");
        assertEquals(SuspenseStatus.RESOLVED, resolved.getStatus());
        assertEquals(7L, resolved.getResolvedBy());

        assertThrows(BizException.class, () -> service.resolve(1L, 7L, "再次处置"));
    }

    @Test
    @DisplayName("resolve：含 WRITTEN_OFF 结论 → 核销")
    void resolve_write_off() {
        SuspenseEntry open = SuspenseEntry.builder().id(2L).entryNo("SP-2")
                .diffType(SuspenseDiffType.BOOK_EXTRA.name()).status(SuspenseStatus.PROCESSING).build();
        when(suspenseEntryRepository.findById(2L)).thenReturn(Optional.of(open));

        SuspenseEntry writtenOff = service.resolve(2L, 8L, "WRITTEN_OFF 无法追回");
        assertEquals(SuspenseStatus.WRITTEN_OFF, writtenOff.getStatus());
    }

    @Test
    @DisplayName("resolve：工单不存在抛 notFound")
    void resolve_not_found() {
        when(suspenseEntryRepository.findById(anyLong())).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.resolve(404L, 1L, "x"));
    }

    @SuppressWarnings("unused")
    private void unused(List<?> l) {
    }
}
