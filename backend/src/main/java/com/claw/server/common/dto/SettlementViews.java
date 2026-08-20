package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跨境物资转移结算视图（控制器出参 / 入参）。
 *
 * <p>核心原则「各算各的」：牵扯两国贸易的物资转移，出口国按自身退税/单证计算，
 * 进口国按自身关税/增值税/单证计算，中间手续线上补充、法律合规按法域分别落实。
 */
public final class SettlementViews {

    private SettlementViews() {
    }

    /** 跨境结算报价请求。 */
    public record CrossBorderQuoteRequest(
            String fromCountry,               // 出口国（供货方所属法域）
            String toCountry,                 // 进口国（收货方所属法域）
            BigDecimal goodsValue,            // 申报货值
            String currency                   // 货值币种
    ) {
    }

    /** 单边（出口国 / 进口国）在自身法域口径下的义务。 */
    public record SideObligation(
            String country,                   // 国家代码
            String side,                      // EXPORT / IMPORT
            BigDecimal dutyOrRebate,          // 出口国=出口退税(正向)；进口国=进口关税
            BigDecimal vat,                   // 进口国=进口环节增值税（出口国通常为 0）
            BigDecimal totalOwn,              // 该国口径下涉及的总额（各算各的）
            List<String> requiredDocs,        // 线上补充的中间手续 / 必备单证
            String fxSettlement,              // 该国结算币种口径
            String complianceNote             // 合规说明
    ) {
    }

    /** 跨境结算报价结果：出口国与进口国各自计算，互不直接抵扣。 */
    public record CrossBorderQuoteResult(
            String fromCountry,
            String toCountry,
            BigDecimal goodsValue,
            String currency,
            SideObligation exportSide,        // 出口国口径（退税 + 出口单证）
            SideObligation importSide,        // 进口国口径（关税 + 进口增值税 + 进口单证）
            BigDecimal landedCost,            // 到岸总成本（进口国币种，未做汇率换算）
            String note                       // 「各算各的」原则说明
    ) {
    }
}
