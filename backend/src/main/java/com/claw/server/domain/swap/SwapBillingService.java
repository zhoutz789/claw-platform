package com.claw.server.domain.swap;

import com.claw.server.common.dto.SwapViews;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 换电计价服务（锁版：实缴实结 + 按度计价 + 时刻快照）。
 *
 * <p>单价来源（PRD v1.1 4.2 费用标准，V6 种子）：
 * <ul>
 *   <li>电费：光伏供电 $0.12/度（elec_price_snapshots 当日快照，缺省 0.12）；
 *       市电 $0.18/度 仅作为电网备用口径（本 MVP 换电统一按光伏快照计价）；</li>
 *   <li>服务费：$0.32/度 = 电池折旧基金 $0.10 + 站经营分成 $0.19 + 平台 $0.03（fee_rules SWAP_SERVICE）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SwapBillingService {

    private static final BigDecimal DEFAULT_PV_PRICE = new BigDecimal("0.12");
    private static final BigDecimal DEFAULT_SERVICE_RATE = new BigDecimal("0.32");

    private final ElecPriceSnapshotRepository elecRepo;
    private final FeeRuleRepository feeRepo;
    private final ObjectMapper objectMapper;

    /** 按当前锁定快照计价（下单/结算统一入口）。 */
    public SwapViews.QuoteView quote(BigDecimal kwh) {
        return calc(kwh, currentElecRate(), currentServiceRate());
    }

    /** 按给定单价计价（结算时用订单锁定快照中的单价）。 */
    public SwapViews.QuoteView calc(BigDecimal kwh, BigDecimal elecRate, BigDecimal serviceRate) {
        BigDecimal elecFee = kwh.multiply(elecRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal serviceFee = kwh.multiply(serviceRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalRate = elecRate.add(serviceRate);
        return new SwapViews.QuoteView(elecRate, serviceRate, totalRate, kwh,
                elecFee, serviceFee, elecFee.add(serviceFee));
    }

    /** 当前电费单价（当日或最近生效快照，光伏价）。 */
    public BigDecimal currentElecRate() {
        return elecRepo.findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(LocalDate.now())
                .map(ElecPriceSnapshot::getPvPrice)
                .orElse(DEFAULT_PV_PRICE);
    }

    /** 当前换电服务费单价。 */
    public BigDecimal currentServiceRate() {
        return feeRepo.findByRuleCodeAndStatus("SWAP_SERVICE", "ACTIVE")
                .map(FeeRule::getPrice)
                .orElse(DEFAULT_SERVICE_RATE);
    }

    /** 生成订单锁定快照 JSON：{elecRate, serviceRate, serviceSplit}，结算按此口径不追价。 */
    public String snapshotJson() {
        BigDecimal elecRate = currentElecRate();
        BigDecimal serviceRate = currentServiceRate();
        JsonNode split = feeRepo.findByRuleCodeAndStatus("SWAP_SERVICE", "ACTIVE")
                .map(f -> readJson(f.getShareJson()))
                .orElse(objectMapper.createObjectNode());
        try {
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("elecRate", elecRate)
                    .put("serviceRate", serviceRate)
                    .set("serviceSplit", split));
        } catch (Exception e) {
            return "{\"elecRate\":%s,\"serviceRate\":%s}".formatted(elecRate, serviceRate);
        }
    }

    /** 从订单锁定快照解析电费/服务费单价。 */
    public SwapViews.QuoteView calcBySnapshot(String priceSnapshotJson, BigDecimal kwh) {
        try {
            JsonNode snap = objectMapper.readTree(priceSnapshotJson);
            BigDecimal elecRate = snap.get("elecRate").decimalValue();
            BigDecimal serviceRate = snap.get("serviceRate").decimalValue();
            return calc(kwh, elecRate, serviceRate);
        } catch (Exception e) {
            return quote(kwh);   // 快照缺失回退当前价
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
