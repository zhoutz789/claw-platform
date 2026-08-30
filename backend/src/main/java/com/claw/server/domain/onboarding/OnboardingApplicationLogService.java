package com.claw.server.domain.onboarding;

import com.claw.server.domain.onboarding.OnboardingApplicationLog.Action;
import com.claw.server.domain.onboarding.OnboardingApplicationLog.OperatorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 入驻申请留痕服务（增量 C · O17）。
 *
 * <p>记录「谁、何时、动作、意见、前后状态」构成审批时间轴，供详情页一屏决策与事后追溯。
 * 写日志与业务变更在同一事务内，保证留痕与状态迁移强一致。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingApplicationLogService {

    private final OnboardingApplicationLogRepository logRepository;

    /** 取某申请单的时间轴（新在前）。 */
    @Transactional(readOnly = true)
    public List<OnboardingApplicationLog> timeline(Long applicationId) {
        return logRepository.findByApplicationIdOrderByCreatedAtDescIdDesc(applicationId);
    }

    /**
     * 写一条留痕。
     *
     * @param applicationId 申请单 id
     * @param fromStatus    迁移前状态（可为 null）
     * @param toStatus      迁移后状态（可为 null）
     * @param action        动作
     * @param operatorId    操作人（SYSTEM 动作为 null）
     * @param operatorType  PLATFORM / APPLICANT / SYSTEM
     * @param remark        审核意见
     * @param payloadJson   变更明细（哪个字段 / 材料被驳回），JSON 字符串
     */
    @Transactional
    public OnboardingApplicationLog record(Long applicationId, String fromStatus, String toStatus,
                                           Action action, Long operatorId, OperatorType operatorType,
                                           String remark, String payloadJson) {
        OnboardingApplicationLog entry = OnboardingApplicationLog.builder()
                .applicationId(applicationId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .action(action.name())
                .operatorId(operatorId)
                .operatorType(operatorType.name())
                .remark(remark)
                .payloadJson(payloadJson)
                .createdAt(Instant.now())
                .build();
        OnboardingApplicationLog saved = logRepository.save(entry);
        log.debug("入驻申请留痕 app={} {} -> {} action={}", applicationId, fromStatus, toStatus, action);
        return saved;
    }

    /** 简化重载：无 payload。 */
    @Transactional
    public OnboardingApplicationLog record(Long applicationId, String fromStatus, String toStatus,
                                           Action action, Long operatorId, OperatorType operatorType,
                                           String remark) {
        return record(applicationId, fromStatus, toStatus, action, operatorId, operatorType, remark, null);
    }
}
