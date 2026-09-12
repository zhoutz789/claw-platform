package com.claw.server.domain.autonomy;

import com.claw.server.domain.sharedpool.RevenueSplitRule;
import com.claw.server.domain.sharedpool.RevenueSplitRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收益分账服务（AU7）：按车主/平台/算法三方比例拆分毛收入。
 */
@Service
@RequiredArgsConstructor
public class AutonomyRevenueService {

    private final RevenueSplitRuleRepository splitRuleRepository;

    private static final BigDecimal EPSILON = new BigDecimal("1e-6");
    private static final int SCALE = 2;

    /**
     * 计算三方分账。三比例之和须为 1.0（容差 1e-6），否则抛异常。
     */
    public Map<String, BigDecimal> computeSplit(BigDecimal gross, BigDecimal ownerRatio,
                                                 BigDecimal platformRatio, BigDecimal algoRatio) {
        BigDecimal sum = ownerRatio.add(platformRatio).add(algoRatio);
        if (sum.subtract(BigDecimal.ONE).abs().compareTo(EPSILON) > 0) {
            throw new IllegalArgumentException("autonomy.split.ratio.invalid");
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("OWNER", gross.multiply(ownerRatio).setScale(SCALE, RoundingMode.HALF_UP));
        result.put("PLATFORM", gross.multiply(platformRatio).setScale(SCALE, RoundingMode.HALF_UP));
        result.put("ALGO", gross.multiply(algoRatio).setScale(SCALE, RoundingMode.HALF_UP));
        return result;
    }

    /**
     * 任务默认分账：车主 0.6 / 平台 0.3 / 算法 0.1。
     */
    public Map<String, BigDecimal> splitForTask(Long taskId, BigDecimal gross) {
        return computeSplit(gross, new BigDecimal("0.6"), new BigDecimal("0.3"), new BigDecimal("0.1"));
    }

    /**
     * AU7 计算无人车自主任务三方分账（车主 / 平台(算法) / 服务方）。
     * 读取该资产既有 {@code revenue_split_rules}（即"算法归平台"可配分成）：
     * 车主 = owner_rate；平台(算法) = platform_rate；服务方 = station_rate + insurance_rate。
     * 无规则时回退默认 0.60 / 0.30 / 0.10。
     */
    public AutonomyRevenueSplit computeAutonomyRevenueSplit(Long assetId, BigDecimal gross) {
        RevenueSplitRule rule = splitRuleRepository
                .findFirstByAssetIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(assetId, "ACTIVE")
                .orElse(null);
        BigDecimal ownerRate;
        BigDecimal platformRate;
        BigDecimal providerRate;
        String basis;
        if (rule != null) {
            ownerRate = rule.getOwnerRate();
            platformRate = rule.getPlatformRate();
            BigDecimal station = rule.getStationRate() == null ? BigDecimal.ZERO : rule.getStationRate();
            BigDecimal insurance = rule.getInsuranceRate() == null ? BigDecimal.ZERO : rule.getInsuranceRate();
            providerRate = station.add(insurance);
            basis = "REVENUE_SPLIT_RULE#" + rule.getId();
        } else {
            ownerRate = new BigDecimal("0.60");
            platformRate = new BigDecimal("0.30");
            providerRate = new BigDecimal("0.10");
            basis = "DEFAULT";
        }
        BigDecimal owner = gross.multiply(ownerRate).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal platform = gross.multiply(platformRate).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal provider = gross.multiply(providerRate).setScale(SCALE, RoundingMode.HALF_UP);
        return new AutonomyRevenueSplit(
                assetId,
                gross.setScale(SCALE, RoundingMode.HALF_UP),
                owner, platform, provider, basis);
    }
}
