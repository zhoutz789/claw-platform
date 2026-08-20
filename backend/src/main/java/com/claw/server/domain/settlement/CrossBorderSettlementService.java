package com.claw.server.domain.settlement;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.SettlementViews;
import com.claw.server.domain.jurisdiction.Country;
import com.claw.server.domain.jurisdiction.CountryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 跨境物资转移结算服务（「各算各的」）。
 *
 * <p>牵扯两国贸易的物资转移：出口国按其自身退税/单证计算，进口国按其自身关税/增值税/单证计算，
 * 双方互不直接抵扣。中间手续（报关/发票/许可证等）由平台线上补充采集，法律合规按各自法域落实。
 *
 * <p>每国的规则来自 {@code countries.trade_policy_json}，进一国只需维护该 JSON，核心引擎不变。
 */
@Service
@RequiredArgsConstructor
public class CrossBorderSettlementService {

    private final CountryRepository countryRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> POLICY_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    /** 引擎回退默认策略（当某国未配置 trade_policy_json 时）。 */
    private static final TradePolicy DEFAULT_POLICY = new TradePolicy(
            new BigDecimal("0.10"), BigDecimal.ZERO, new BigDecimal("0.10"),
            "LOCAL",
            List.of("commercial_invoice", "customs_declaration"),
            List.of("export_declaration", "commercial_invoice"),
            "未配置国别策略，采用平台默认：进口关税 10% / 增值税 10%");

    public SettlementViews.CrossBorderQuoteResult quote(SettlementViews.CrossBorderQuoteRequest req) {
        if (req.goodsValue() == null || req.goodsValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(40961, "error.settlement.value");
        }
        if (req.fromCountry().equalsIgnoreCase(req.toCountry())) {
            throw BizException.of(40960, "error.settlement.domestic");
        }

        Country from = countryRepository.findByCode(req.fromCountry().toUpperCase())
                .orElseThrow(() -> BizException.of(40450, "error.country.not.found"));
        Country to = countryRepository.findByCode(req.toCountry().toUpperCase())
                .orElseThrow(() -> BizException.of(40450, "error.country.not.found"));

        TradePolicy fp = policyOf(from);
        TradePolicy tp = policyOf(to);

        BigDecimal v = req.goodsValue();

        // 出口国口径：出口退税（正向返还），单证为出口侧要求
        BigDecimal exportRebate = v.multiply(fp.exportRebateRate).setScale(2, RoundingMode.HALF_UP);
        SettlementViews.SideObligation exportSide = new SettlementViews.SideObligation(
                from.getCode(), "EXPORT",
                exportRebate, BigDecimal.ZERO,
                v.subtract(exportRebate),            // 出口国口径：货值扣减退税后净额
                fp.requiredExportDocs, fp.fxSettlement, fp.complianceNote);

        // 进口国口径：关税 + 进口增值税（各算各的—进口国自有税率）
        BigDecimal importDuty = v.multiply(tp.importDutyRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal importVat = v.add(importDuty).multiply(tp.vatRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal landedCost = v.add(importDuty).add(importVat);
        SettlementViews.SideObligation importSide = new SettlementViews.SideObligation(
                to.getCode(), "IMPORT",
                importDuty, importVat, landedCost,
                tp.requiredImportDocs, tp.fxSettlement, tp.complianceNote);

        return new SettlementViews.CrossBorderQuoteResult(
                req.fromCountry().toUpperCase(), req.toCountry().toUpperCase(),
                v, req.currency(),
                exportSide, importSide, landedCost,
                "各算各的：出口国按自身退税/单证计算，进口国按自身关税/增值税/单证计算；"
                        + "中间手续由平台线上补充，法律合规分别落实于两国法域。");
    }

    private static TradePolicy policyOf(Country c) {
        String json = c.getTradePolicyJson();
        if (json == null || json.isBlank()) {
            return DEFAULT_POLICY;
        }
        Map<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(json, POLICY_TYPE);
        } catch (Exception e) {
            return DEFAULT_POLICY;
        }
        return new TradePolicy(
                asDecimal(parsed.get("import_duty_rate"), DEFAULT_POLICY.importDutyRate),
                asDecimal(parsed.get("export_rebate_rate"), DEFAULT_POLICY.exportRebateRate),
                asDecimal(parsed.get("vat_rate"), DEFAULT_POLICY.vatRate),
                asText(parsed.get("fx_settlement"), DEFAULT_POLICY.fxSettlement),
                asList(parsed.get("required_import_docs"), DEFAULT_POLICY.requiredImportDocs),
                asList(parsed.get("required_export_docs"), DEFAULT_POLICY.requiredExportDocs),
                asText(parsed.get("compliance_note"), DEFAULT_POLICY.complianceNote));
    }

    private static BigDecimal asDecimal(Object o, BigDecimal fallback) {
        if (o == null) {
            return fallback;
        }
        return o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : fallback;
    }

    private static String asText(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object o, List<String> fallback) {
        if (!(o instanceof List<?> list)) {
            return fallback;
        }
        return list.stream().map(String::valueOf).toList();
    }

    /** 国别贸易策略（不可变值对象）。 */
    private record TradePolicy(
            BigDecimal importDutyRate,
            BigDecimal exportRebateRate,
            BigDecimal vatRate,
            String fxSettlement,
            List<String> requiredImportDocs,
            List<String> requiredExportDocs,
            String complianceNote) {
    }
}
