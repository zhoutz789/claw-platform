package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 后台管理（S5 补齐）统一出入参。
 * 覆盖风控监控 / 异常告警 / 产权链 / 争议仲裁 / 分账 / 费率 / 角色权限 / 订单 / 系统配置
 * 九大模块的 admin CRUD DTO，避免在 web 层散落大量一次性 record。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /* ===================== 风控监控 ===================== */

    public static record StationRiskMonitorView(Long id, Long stationId, Long operatorId,
                                                String metricType, BigDecimal metricValue, BigDecimal threshold,
                                                BigDecimal baseline, Integer riskScore, String status,
                                                Instant triggeredAt, String triggeredReason, Instant resolvedAt,
                                                Long resolvedBy, String resolutionNote, Long riskEventId) {
    }

    public static record StationRiskMonitorReq(Long stationId, Long operatorId, String metricType,
                                               BigDecimal metricValue, BigDecimal threshold, BigDecimal baseline,
                                               Integer riskScore, String status, String triggeredReason,
                                               String resolutionNote) {
    }

    public static record OperatorRiskEventView(Long id, Long operatorId, Long stationId, String eventType,
                                               String severity, String description, BigDecimal detectedValue,
                                               BigDecimal expectedValue, String autoAction, Boolean actionTaken,
                                               Boolean resolved, Long resolvedBy, String resolutionNote,
                                               Instant createdAt) {
    }

    public static record OperatorRiskEventResolveReq(Long resolvedBy, String resolutionNote) {
    }

    public static record InsuranceFundView(Long id, BigDecimal totalBalance, BigDecimal totalCollected,
                                           BigDecimal totalClaimed, BigDecimal totalRecovered, BigDecimal coverageRatio,
                                           BigDecimal totalAssetValue, BigDecimal coverageActual, String status,
                                           Instant lastUpdatedAt, Long lastUpdatedBy, String auditNotes) {
    }

    public static record InsuranceFundUpdateReq(BigDecimal totalBalance, BigDecimal coverageRatio,
                                                String status, Long lastUpdatedBy, String auditNotes) {
    }

    /* ===================== 产权链 / 争议仲裁 ===================== */

    public static record CustodyTransferView(Long id, Long assetId, String assetType, Long fromUserId,
                                             Long toUserId, String transferType, Long stationId, Long swapOrderId,
                                             String chainHash, BigDecimal assetSoh, BigDecimal assetSoc,
                                             Integer assetCycleCount, Instant transferredAt) {
    }

    public static record CustodyAuditView(Long id, Long transferId, Long assetId, String anomalyType,
                                          Integer riskScore, String description, Integer userDailyTransferCount,
                                          Integer assetDailyTransferCount, Instant detectedAt, Boolean reviewed) {
    }

    public static record CustodyTransferDetailView(CustodyTransferView transfer, List<CustodyAuditView> audits) {
    }

    public static record CustodyDisputeView(Long id, Long transferId, Long assetId, Long claimantId,
                                            Long respondentId, String disputeType, String description,
                                            String evidenceUrls, BigDecimal claimAmount, BigDecimal awardedAmount,
                                            String status, Long arbitratorId, String resolution, Instant resolvedAt,
                                            Instant createdAt) {
    }

    public static record CustodyDisputeReq(Long transferId, Long assetId, Long claimantId, Long respondentId,
                                           String disputeType, String description, String evidenceUrls,
                                           BigDecimal claimAmount) {
    }

    public static record CustodyArbitrateReq(Long arbitratorId, BigDecimal awardedAmount, String resolution) {
    }

    /* ===================== 分账报告 ===================== */

    public static record RevenueSettlementView(Long id, String settlementNo, LocalDate settlementDate,
                                               Long stationId, Long poolEntryId, BigDecimal totalRevenue,
                                               BigDecimal ownerShare, BigDecimal stationShare,
                                               BigDecimal platformShare, BigDecimal insuranceShare,
                                               String ledgerTxnId, String status, Instant periodStart,
                                               Instant periodEnd, Instant settledAt) {
    }

    public static record RevenueSplitRuleView(Long id, Long assetId, Long poolEntryId, BigDecimal ownerRate,
                                              BigDecimal stationRate, BigDecimal platformRate,
                                              BigDecimal insuranceRate, String shareBasis, LocalDate effectiveFrom,
                                              LocalDate effectiveTo, String status) {
    }

    public static record RevenueSplitRuleReq(Long assetId, Long poolEntryId, BigDecimal ownerRate,
                                             BigDecimal stationRate, BigDecimal platformRate,
                                             BigDecimal insuranceRate, String shareBasis, LocalDate effectiveFrom,
                                             LocalDate effectiveTo, String status) {
    }

    /* ===================== 费率配置 ===================== */

    public static record FeeRuleView(Long id, String ruleCode, String name, String unit, BigDecimal price,
                                     String shareJson, LocalDate effectiveFrom, LocalDate effectiveTo,
                                     String status) {
    }

    public static record FeeRuleReq(String ruleCode, String name, String unit, BigDecimal price, String shareJson,
                                    LocalDate effectiveFrom, LocalDate effectiveTo, String status) {
    }

    public static record ElecPriceSnapshotView(Long id, BigDecimal pvPrice, BigDecimal gridPrice,
                                               LocalDate effectiveDate) {
    }

    /* ===================== 角色权限 ===================== */

    public static record RoleReq(String code, String nameI18n, String grants, Boolean autoGrant,
                                 String grantRule, String status, String dataScope, String dataScopeTypes,
                                 Long parentId, String dataRuleIds) {
    }

    /** 角色数据范围更新（PUT /api/v1/admin/roles/{id}/datascope）。 */
    public static record RoleDataScopeReq(String dataScope, String dataScopeTypes, String dataRuleIds) {
    }

    public static record UserRoleAssignmentView(Long id, Long userId, String userName, String roleCode,
                                                String roleName, String source, Instant grantedAt,
                                                Instant revokedAt) {
    }

    public static record UserRoleAssignReq(Long userId, String roleCode) {
    }

    /* ===================== 订单管理 ===================== */

    public static record SwapOrderView(Long id, String orderNo, Long userId, Long stationId, Long vehicleId,
                                       Long batteryOutId, Long batteryInId, String status, BigDecimal batteryDeposit,
                                       BigDecimal oldBatteryDeposit, BigDecimal estKwh, BigDecimal estElecFee,
                                       BigDecimal estServiceFee, BigDecimal estTotal, BigDecimal actualTotal,
                                       BigDecimal socStart, BigDecimal socEnd, String settleStatus,
                                       String cancelReason, Instant createdAt) {
    }

    public static record SwapOrderStatusReq(String status, String cancelReason) {
    }

    public static record RentalOrderView(Long id, String orderNo, Long assetId, Long renterUserId, Long stationId,
                                         String rentalType, String status, BigDecimal totalFee, BigDecimal ownerShare,
                                         BigDecimal stationShare, BigDecimal platformShare,
                                         BigDecimal insuranceShare, Instant startedAt, Instant completedAt) {
    }

    /* ===================== 系统配置 ===================== */

    public static record SystemConfigView(Long id, String configKey, String configValue, String category,
                                          String description, String dataType, Boolean editable) {
    }

    public static record SystemConfigReq(String configKey, String configValue, String category,
                                         String description, String dataType, Boolean editable) {
    }
}
