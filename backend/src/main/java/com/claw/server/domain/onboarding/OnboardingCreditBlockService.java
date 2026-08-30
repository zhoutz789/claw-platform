package com.claw.server.domain.onboarding;

import com.claw.server.common.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 授信额度超限阻断留痕（增量 C · §2.2(8)）。
 *
 * <p>⚠️ <b>为什么单独成一个 Bean 并用 REQUIRES_NEW</b>：
 * 阻断记录必须在「业务事务回滚后依然存在」——
 * {@code CreditLimitService.assertWithinLimit} 落完阻断记录后会立刻抛 40941，
 * 若两者同处一个事务，回滚会把这条风控留痕一并抹掉，超限纠纷就无据可查了。
 * 独立事务保证「超限事件」与「业务失败」解耦。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingCreditBlockService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OnboardingCreditBlockRepository creditBlockRepository;

    /**
     * 落一条阻断记录（独立事务，不随业务回滚）。
     *
     * @param principalType 被限主体（首期仅 STATION）
     * @param principalId   主体 ID
     * @param scene         阻断场景
     * @param bizRefType    业务单据类型（TRANSFER / FULFILLMENT / CONSIGNMENT）
     * @param bizRefId      业务单据 ID
     * @param deviceCount   本次尝试入站设备数
     * @param incoming      本次尝试新增货值
     * @param used          当时已占用货值
     * @param limit         当时额度上限
     * @param overflow      超出金额
     * @return 阻断记录；写库失败时返回 null（不掩盖超限本身）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OnboardingCreditBlock record(String principalType, Long principalId,
                                        OnboardingCreditBlock.Scene scene, String bizRefType, Long bizRefId,
                                        int deviceCount, BigDecimal incoming, BigDecimal used,
                                        BigDecimal limit, BigDecimal overflow) {
        try {
            return creditBlockRepository.save(OnboardingCreditBlock.builder()
                    .blockNo(generateBlockNo())
                    .principalType(principalType)
                    .principalId(principalId)
                    .scene(scene.name())
                    .bizRefType(bizRefType)
                    .bizRefId(bizRefId)
                    .deviceCount(deviceCount)
                    .incomingValue(incoming)
                    .usedValue(used)
                    .creditLimit(limit)
                    .overflowValue(overflow)
                    .operatorId(AuthContext.currentUserId())
                    .build());
        } catch (Exception e) {
            // 留痕失败不掩盖超限本身：调用方仍会抛 40941
            log.error("写入额度超限阻断记录失败 principal={}#{}：{}", principalType, principalId, e.getMessage());
            return null;
        }
    }

    /** 某主体的阻断历史（组织管理页 / 风控排查用）。 */
    @Transactional(readOnly = true)
    public java.util.List<OnboardingCreditBlock> listByPrincipal(String principalType, Long principalId) {
        return creditBlockRepository.findByPrincipalTypeAndPrincipalIdOrderByCreatedAtDesc(principalType, principalId);
    }

    private static String generateBlockNo() {
        String date = NO_DATE.format(java.time.Instant.now().atZone(ZoneOffset.UTC));
        return "CRB" + date + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
