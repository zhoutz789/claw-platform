package com.claw.server.domain.airspace;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.PilotBehaviorType;
import com.claw.server.common.enums.PilotPenaltyType;
import com.claw.server.common.enums.PilotStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 飞手运营服务（V141）：建档审核闭环 + 行为事件记录 + 违规处罚历史。
 *
 * <p>三条职责边界刻意分开，避免「一次性动作糊在一起」：
 * <ol>
 *   <li><b>建档/审核</b>：{@link #submitProfile} 落 PENDING；{@link #approve} 唯一合法迁移
 *       PENDING → ACTIVE，非法状态抛 409（状态冲突），而非静默改写；</li>
 *   <li><b>行为记录</b>：{@link #recordBehavior} 只记录事实（遥测派生），<b>不处罚</b>；</li>
 *   <li><b>处罚决策</b>：{@link #penalize} 落处罚历史并施加对档案状态的确定性副作用，
 *       两者同一事务，避免「有处罚单但档案还是 ACTIVE」的脏态。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PilotOpsService {

    /** 信用分下限（违规扣分不使其为负）。 */
    private static final int MIN_CREDIT_SCORE = 0;

    /** 建档初始信用分。 */
    private static final int INITIAL_CREDIT_SCORE = 100;

    private final PilotProfileRepository profileRepository;
    private final PilotBehaviorEventRepository behaviorRepository;
    private final PilotPenaltyRepository penaltyRepository;
    private final PilotLicenseRepository licenseRepository;

    /**
     * 提交飞手建档（落 PENDING，等待审核）。
     *
     * @param userId    平台用户 id
     * @param licenseNo 执照编号（可空；非空时须已在 pilot_licenses 登记）
     * @param kycLevel  KYC 等级 BASIC/STANDARD/ENHANCED（空则 BASIC）
     * @return 新建档案（status=PENDING）
     * @throws BizException 10001 error.pilot.profile.invalid（userId 为空）
     * @throws BizException 40964 error.pilot.profile.duplicate（该用户已建档）
     * @throws BizException 40469 error.pilot.license.not.found（执照编号未登记）
     */
    @Transactional
    public PilotProfile submitProfile(Long userId, String licenseNo, String kycLevel) {
        if (userId == null) {
            throw BizException.invalidParam("error.pilot.profile.invalid");
        }
        if (profileRepository.existsByUserId(userId)) {
            throw BizException.of(40964, "error.pilot.profile.duplicate", userId);
        }
        String normalizedLicense = licenseNo != null && !licenseNo.isBlank() ? licenseNo.trim() : null;
        if (normalizedLicense != null && !licenseRepository.existsByLicenseNo(normalizedLicense)) {
            throw BizException.of(40469, "error.pilot.license.not.found", normalizedLicense);
        }

        PilotProfile profile = PilotProfile.builder()
                .userId(userId)
                .licenseNo(normalizedLicense)
                .kycLevel(kycLevel != null && !kycLevel.isBlank()
                        ? kycLevel.trim().toUpperCase(Locale.ROOT) : "BASIC")
                .status(PilotStatus.PENDING)
                .level(1)
                .creditScore(INITIAL_CREDIT_SCORE)
                .build();
        PilotProfile saved = profileRepository.save(profile);
        log.info("飞手建档 user={} license={} kyc={} status=PENDING",
                userId, normalizedLicense, saved.getKycLevel());
        return saved;
    }

    /**
     * 审核通过（唯一合法迁移：PENDING → ACTIVE）。
     *
     * @param userId     平台用户 id
     * @param approverId 审核人用户 id（可空）
     * @return 更新后的档案（status=ACTIVE）
     * @throws BizException 40468 error.pilot.profile.not.found（档案不存在）
     * @throws BizException 40965 error.pilot.status.invalid（当前状态非 PENDING）
     */
    @Transactional
    public PilotProfile approve(Long userId, Long approverId) {
        PilotProfile profile = requireProfile(userId);
        if (profile.getStatus() != PilotStatus.PENDING) {
            throw BizException.of(40965, "error.pilot.status.invalid", profile.getStatus().name());
        }
        Instant now = Instant.now();
        profile.setStatus(PilotStatus.ACTIVE);
        profile.setApprovedBy(approverId);
        profile.setApprovedAt(now);
        profile.setUpdatedAt(now);
        PilotProfile saved = profileRepository.save(profile);
        log.info("飞手审核通过 user={} approvedBy={}", userId, approverId);
        return saved;
    }

    /**
     * 飞手档案列表（可按状态过滤）。
     *
     * @param status 状态名（{@code PENDING/ACTIVE/SUSPENDED/BANNED}，大小写不敏感）；为空返回全部
     * @return 档案列表
     * @throws BizException 10001 error.pilot.status.unknown（状态名非法）
     */
    @Transactional(readOnly = true)
    public List<PilotProfile> list(String status) {
        if (status == null || status.isBlank()) {
            return profileRepository.findByDeletedFalse();
        }
        return profileRepository.findByStatusAndDeletedFalse(parseStatus(status));
    }

    /**
     * 取档案（按平台用户）。
     *
     * @param userId 平台用户 id
     * @return 档案
     * @throws BizException 40468 error.pilot.profile.not.found（档案不存在）
     */
    @Transactional(readOnly = true)
    public PilotProfile getProfile(Long userId) {
        return requireProfile(userId);
    }

    /**
     * 记录飞手行为事件（只记录事实，不处罚）。
     *
     * @param pilotUserId 飞手平台用户 id
     * @param assetId     事发无人机 asset_id（可空）
     * @param eventType   行为类型名或 i18n key
     * @param severity    严重度 LOW/MEDIUM/HIGH/CRITICAL（空则 LOW）
     * @param detailJson  事件明细 JSON（可空）
     * @param sourceRef   来源引用（可空）
     * @return 已落库的行为事件
     * @throws BizException 10001 error.pilot.behavior.invalid（入参非法）
     */
    @Transactional
    public PilotBehaviorEvent recordBehavior(Long pilotUserId, Long assetId, String eventType,
                                             String severity, String detailJson, String sourceRef) {
        if (pilotUserId == null) {
            throw BizException.invalidParam("error.pilot.behavior.invalid");
        }
        PilotBehaviorType type;
        try {
            type = PilotBehaviorType.fromCode(eventType);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("error.pilot.behavior.invalid");
        }

        PilotBehaviorEvent event = PilotBehaviorEvent.builder()
                .pilotUserId(pilotUserId)
                .assetId(assetId)
                .eventType(type)
                .severity(normalizeSeverity(severity))
                .detailJson(detailJson)
                .sourceRef(sourceRef)
                .occurredAt(Instant.now())
                .build();
        PilotBehaviorEvent saved = behaviorRepository.save(event);
        log.info("飞手行为事件 user={} asset={} type={} severity={}",
                pilotUserId, assetId, type, saved.getSeverity());
        return saved;
    }

    /**
     * 施加违规处罚：落处罚历史 + 对档案状态/信用分的确定性副作用（同事务）。
     *
     * <p>副作用规则：
     * <ul>
     *   <li>{@link PilotPenaltyType#WARN} / {@link PilotPenaltyType#FINE}：仅扣信用分；</li>
     *   <li>{@link PilotPenaltyType#SUSPEND}：档案 → SUSPENDED；</li>
     *   <li>{@link PilotPenaltyType#REVOKE}：档案 → BANNED。</li>
     * </ul>
     *
     * @param userId      飞手平台用户 id
     * @param penaltyType 处罚类型名或 i18n key
     * @param cause       违规原因（空则取处罚类型名）
     * @param severity    严重度（空则 LOW）
     * @param points      扣减信用分（空/负则 0）
     * @param bizRef      业务引用（可空）
     * @param decidedBy   决策人用户 id（可空）
     * @return 已落库的处罚记录
     * @throws BizException 40468 error.pilot.profile.not.found（档案不存在）
     * @throws BizException 10001 error.pilot.penalty.invalid（处罚类型非法）
     */
    @Transactional
    public PilotPenalty penalize(Long userId, String penaltyType, String cause, String severity,
                                 Integer points, String bizRef, Long decidedBy) {
        PilotProfile profile = requireProfile(userId);

        PilotPenaltyType type;
        try {
            type = PilotPenaltyType.fromCode(penaltyType);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("error.pilot.penalty.invalid", penaltyType);
        }

        int deducted = points != null ? Math.max(0, points) : 0;
        Instant now = Instant.now();

        PilotPenalty penalty = PilotPenalty.builder()
                .pilotId(profile.getId())
                .cause(cause != null && !cause.isBlank() ? cause.trim() : type.name())
                .severity(normalizeSeverity(severity))
                .points(deducted)
                .penaltyType(type)
                .bizRef(bizRef)
                .status("ACTIVE")
                .effectiveFrom(now)
                .effectiveTo(null)
                .decidedBy(decidedBy)
                .decidedAt(now)
                .build();
        PilotPenalty saved = penaltyRepository.save(penalty);

        applyPenaltySideEffect(profile, type, deducted, now);
        log.info("飞手处罚 user={} type={} points={} -> status={} 累计生效处罚={}",
                userId, type, deducted, profile.getStatus(), countActivePenalties(profile.getId()));
        return saved;
    }

    /**
     * 统计档案下生效处罚数（仅用于日志观测）。
     *
     * <p>刻意兜异常并回退 0：这是纯观测信息，统计失败绝不能把已落库的处罚事务整体回滚
     * （否则会出现「处罚没生效、档案状态也没变」的静默丢失）。
     *
     * @param pilotId 飞手档案 id
     * @return 生效处罚条数；统计失败回退 0
     */
    private long countActivePenalties(Long pilotId) {
        try {
            return penaltyRepository.countByPilotIdAndStatusAndDeletedFalse(pilotId, "ACTIVE");
        } catch (Exception e) {
            log.warn("生效处罚数统计失败（仅影响日志观测）pilotId={}", pilotId, e);
            return 0L;
        }
    }

    /**
     * 飞手处罚历史（按决策时间倒序）。
     *
     * @param userId 飞手平台用户 id
     * @return 处罚列表
     * @throws BizException 40468 error.pilot.profile.not.found（档案不存在）
     */
    @Transactional(readOnly = true)
    public List<PilotPenalty> penalties(Long userId) {
        PilotProfile profile = requireProfile(userId);
        return penaltyRepository.findByPilotIdAndDeletedFalseOrderByDecidedAtDesc(profile.getId());
    }

    /**
     * 飞手行为事件列表（按发生时间倒序）。
     *
     * @param pilotUserId 飞手平台用户 id
     * @return 行为事件列表
     */
    @Transactional(readOnly = true)
    public List<PilotBehaviorEvent> behaviors(Long pilotUserId) {
        return behaviorRepository.findByPilotUserIdOrderByOccurredAtDesc(pilotUserId);
    }

    /**
     * 处罚对档案的副作用：扣信用分 + 按处罚类型推进状态。
     *
     * @param profile  飞手档案
     * @param type     处罚类型
     * @param deducted 扣减分值
     * @param now      决策时刻
     */
    private void applyPenaltySideEffect(PilotProfile profile, PilotPenaltyType type, int deducted, Instant now) {
        if (deducted > 0) {
            Integer current = profile.getCreditScore() != null ? profile.getCreditScore() : INITIAL_CREDIT_SCORE;
            profile.setCreditScore(Math.max(MIN_CREDIT_SCORE, current - deducted));
        }
        switch (type) {
            case SUSPEND -> profile.setStatus(PilotStatus.SUSPENDED);
            case REVOKE -> profile.setStatus(PilotStatus.BANNED);
            case WARN, FINE -> {
                // 仅扣信用分，档案状态不变。
            }
        }
        profile.setUpdatedAt(now);
        profileRepository.save(profile);
    }

    /**
     * 取未删除档案，不存在则抛 404。
     *
     * @param userId 平台用户 id
     * @return 档案
     * @throws BizException 10001 error.pilot.profile.invalid（userId 为空）
     * @throws BizException 40468 error.pilot.profile.not.found（档案不存在）
     */
    private PilotProfile requireProfile(Long userId) {
        if (userId == null) {
            throw BizException.invalidParam("error.pilot.profile.invalid");
        }
        return profileRepository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> BizException.of(40468, "error.pilot.profile.not.found", userId));
    }

    /**
     * 解析飞手状态名（大小写不敏感）。
     *
     * <p>与 {@link #approve} 的状态机冲突分开报错：这里是「查询参数里的状态名不认识」
     * （10001 参数非法），那里是「档案状态不允许本次迁移」（40965 状态冲突）。
     * 二者混用同一条文案会让前端把「输入错误」误判为「状态冲突」。
     *
     * @param status 状态名
     * @return 状态枚举
     * @throws BizException 10001 error.pilot.status.unknown（状态名非法）
     */
    private PilotStatus parseStatus(String status) {
        try {
            return PilotStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("error.pilot.status.unknown", status);
        }
    }

    /**
     * 归一化严重度（大写；空则 LOW）。
     *
     * @param severity 原始严重度
     * @return 归一化后的严重度
     */
    private String normalizeSeverity(String severity) {
        return severity != null && !severity.isBlank()
                ? severity.trim().toUpperCase(Locale.ROOT)
                : "LOW";
    }
}
