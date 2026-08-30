package com.claw.server.domain.onboarding;

import java.util.List;

/**
 * 入驻申请详情视图（增量 C · O35「一屏决策」）。
 *
 * <p>一屏聚合：申请单主体字段（身份证已脱敏）+ 全部材料 + 逐项审核结论 +
 * 身份认证状态 + 场地定位 + 合同签署版本 + 保证金档位 + 缴款记录 + 审批时间轴 + 组织当前入驻状态。
 *
 * <p>⚠️ 用 record 而非直接返回实体：{@code idCardNo} 是 AES 密文，
 * 直接序列化会把敏感信息下发到前端，故按 {@code idCardMasked} 单独暴露脱敏值。
 *
 * @param application      申请单（idCardNo 已置空）
 * @param idCardMasked     脱敏身份证号
 * @param attachments      全部材料附件（含逐项审核结论）
 * @param requirements     该类主体的材料清单
 * @param logs             审批 / 操作时间轴
 * @param contract         签署时锁定的合同版本（可能为 null：历史数据）
 * @param tier             所选保证金档位（可能为 null）
 * @param deposits         缴款记录
 * @param orgOnboardingStatus 已激活时关联组织的入驻状态（ACTIVE / DISABLED），未激活为 null
 */
public record ApplicationDetail(
        OnboardingApplication application,
        String idCardMasked,
        List<OnboardingAttachment> attachments,
        List<OnboardingMaterialRequirement> requirements,
        List<OnboardingApplicationLog> logs,
        OnboardingContract contract,
        OnboardingDepositTier tier,
        List<OnboardingDeposit> deposits,
        String orgOnboardingStatus) {
}
