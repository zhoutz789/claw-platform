package com.claw.server.domain.credit;

import com.claw.server.common.dto.CreditViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Claw Score 信用分服务（S5）。
 *
 * <p>分数 0-1000（默认 600）；每次变动写事件审计（可回溯）。
 * 因子权重（技术文档）：换电频次 30% | 准时付费 30% | 里程出车 20% | 好评率 10% | 收入稳定 10%。
 * 高分权益：降首付 / 提额 / 解锁周租月租（下游消费，本版只提供分数与事件）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditScoreService {

    private static final int DEFAULT_SCORE = 600;
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 1000;

    private final CreditScoreRepository creditScoreRepository;
    private final CreditScoreEventRepository creditScoreEventRepository;

    /** 查询信用分；不存在则初始化默认分。 */
    @Transactional
    public CreditViews.CreditScoreView getScore(Long userId) {
        CreditScore score = creditScoreRepository.findByUserId(userId)
                .orElseGet(() -> creditScoreRepository.save(CreditScore.builder()
                        .userId(userId).score(DEFAULT_SCORE).build()));
        return toView(score);
    }

    /** 应用信用分变更（clamp 0-1000），写事件审计。 */
    @Transactional
    public CreditViews.CreditScoreView applyEvent(Long userId, int delta, String reason, String refId) {
        CreditScore score = creditScoreRepository.findByUserId(userId)
                .orElseGet(() -> creditScoreRepository.save(CreditScore.builder()
                        .userId(userId).score(DEFAULT_SCORE).build()));

        int newScore = clamp(score.getScore() + delta);
        score.setScore(newScore);
        score.setUpdatedAt(Instant.now());
        creditScoreRepository.save(score);

        creditScoreEventRepository.save(CreditScoreEvent.builder()
                .userId(userId).delta(delta).reason(reason).refId(refId).build());

        log.info("信用分变更 user={} delta={} reason={} → {}", userId, delta, reason, newScore);
        return toView(score);
    }

    /** 信用分事件历史。 */
    @Transactional(readOnly = true)
    public List<CreditViews.CreditEventView> events(Long userId) {
        return creditScoreEventRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new CreditViews.CreditEventView(e.getUserId(), e.getDelta(),
                        e.getReason(), e.getRefId(), e.getCreatedAt()))
                .toList();
    }

    private int clamp(int v) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, v));
    }

    private CreditViews.CreditScoreView toView(CreditScore s) {
        return new CreditViews.CreditScoreView(s.getUserId(), s.getScore(), s.getFactors(), s.getUpdatedAt());
    }
}
