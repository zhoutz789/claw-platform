package com.claw.server.domain.credit;

import com.claw.server.common.dto.CreditViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Claw Score 信用分单元测试：初始分、事件变更 clamp、审计事件落库。
 */
@ExtendWith(MockitoExtension.class)
class CreditScoreServiceTest {

    @Mock private CreditScoreRepository creditScoreRepository;
    @Mock private CreditScoreEventRepository creditScoreEventRepository;
    @InjectMocks private CreditScoreService service;

    private CreditScore score(int s) {
        return CreditScore.builder().userId(100L).score(s).build();
    }

    @Test
    void getScore_initializes_default_when_absent() {
        when(creditScoreRepository.findByUserId(100L)).thenReturn(Optional.empty());
        when(creditScoreRepository.save(any(CreditScore.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreditViews.CreditScoreView v = service.getScore(100L);

        assertEquals(600, v.score());
    }

    @Test
    void getScore_returns_existing() {
        when(creditScoreRepository.findByUserId(100L)).thenReturn(Optional.of(score(720)));

        CreditViews.CreditScoreView v = service.getScore(100L);

        assertEquals(720, v.score());
    }

    @Test
    void applyEvent_increases_score_and_writes_event() {
        when(creditScoreRepository.findByUserId(100L)).thenReturn(Optional.of(score(600)));
        when(creditScoreRepository.save(any(CreditScore.class))).thenAnswer(inv -> inv.getArgument(0));
        when(creditScoreEventRepository.save(any(CreditScoreEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        CreditViews.CreditScoreView v = service.applyEvent(100L, 30, "ON_TIME_PAYMENT", "SW-1");

        assertEquals(630, v.score());
        verify(creditScoreEventRepository).save(any(CreditScoreEvent.class));
    }

    @Test
    void applyEvent_clamps_to_max_1000() {
        when(creditScoreRepository.findByUserId(100L)).thenReturn(Optional.of(score(990)));
        when(creditScoreRepository.save(any(CreditScore.class))).thenAnswer(inv -> inv.getArgument(0));
        when(creditScoreEventRepository.save(any(CreditScoreEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        CreditViews.CreditScoreView v = service.applyEvent(100L, 50, "REVIEW", null);

        assertEquals(1000, v.score());
    }

    @Test
    void applyEvent_clamps_to_min_0() {
        when(creditScoreRepository.findByUserId(100L)).thenReturn(Optional.of(score(20)));
        when(creditScoreRepository.save(any(CreditScore.class))).thenAnswer(inv -> inv.getArgument(0));
        when(creditScoreEventRepository.save(any(CreditScoreEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        CreditViews.CreditScoreView v = service.applyEvent(100L, -100, "PENALTY", null);

        assertEquals(0, v.score());
    }
}
