package com.claw.server.common.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 项目管理域出入参（common 层：不得 import 任何 domain 类，只用基础类型 / String / 枚举名 /
 * BigDecimal / Instant，authType 以 String 透传，服务层做枚举校验）。
 * 对应 Increment 3 C 期（项目管理域 V36）。
 */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    /** 项目视图。 */
    public record ProjectView(
            Long id,
            Long ownerUserId,
            String name,
            Long parentId,
            int depth,
            int sortNo,
            Long accountId,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 创建项目请求。 */
    public record CreateProjectReq(
            String name,
            Long parentId,
            Integer sortNo) {
    }

    /** 更新项目请求（全字段可选，仅覆盖非空项）。 */
    public record UpdateProjectReq(
            String name,
            Long parentId,
            Integer sortNo,
            String status) {
    }

    /** 项目树节点（含子节点，递归组装）。 */
    public record ProjectTreeNode(
            Long id,
            Long ownerUserId,
            String name,
            Long parentId,
            int depth,
            int sortNo,
            Long accountId,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<ProjectTreeNode> children) {
    }

    /** 项目-设备视图（含资产摘要）。 */
    public record ProjectDeviceView(
            Long id,
            Long projectId,
            Long assetId,
            Long productId,
            String category,
            int sortNo,
            String assetNo,
            String assetType,
            String assetStatus) {
    }

    /** 绑定设备请求。 */
    public record BindDeviceReq(
            @NotNull Long assetId) {
    }

    /** 三态授权请求：authType ∈ {TRANSFER, SHARE, AUTHORIZE}。 */
    public record DeviceAuthorizeReq(
            String authType,
            Long granteeUserId,
            String scope,
            Long stationId,
            BigDecimal ownerSplitRate,
            BigDecimal stationSplitRate,
            BigDecimal dailyUsageFee,
            BigDecimal perSwapFee) {
    }

    /** 设备授权视图。 */
    public record DeviceAuthorizationView(
            Long id,
            Long assetId,
            Long grantorUserId,
            Long granteeUserId,
            String authType,
            String scopeJson,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 项目核算账户视图。 */
    public record ProjectAccountView(
            Long projectId,
            Long accountId,
            BigDecimal balance,
            String currency,
            Instant updatedAt) {
    }

    /** 项目记账请求：type ∈ {INCOME, EXPENSE}（亦兼容 CREDIT/DEBIT）。 */
    public record ProjectEntryReq(
            @NotNull BigDecimal amount,
            String type,
            String memo) {
    }
}
