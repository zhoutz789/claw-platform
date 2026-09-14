package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.TaxpayerStatus;
import com.claw.server.common.enums.WhtCategory;
import com.claw.server.domain.funds.VirtualSubAccount;
import com.claw.server.domain.funds.VirtualSubAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * WHT 预扣税代扣引擎（T11，设计附录 B.3 / B.5 / C.2）。
 *
 * <p><b>纯函数语义</b>：{@link #compute(TaxpayerStatus, WhtCategory, BigDecimal)} 只做税率矩阵计算，
 * 无副作用、可单测。{@link #compute(Long, BigDecimal, String)} 先按虚拟子户 id 读取收款方税务档案
 * （{@code taxpayerStatus} / {@code whtCategory}）再委托纯函数计算。</p>
 *
 * <p><b>税率矩阵</b>（柬埔寨语境，WHT = 平台向第三方付款时的法定扣缴义务）：
 * <ul>
 *   <li>{@code REGISTERED} 已登记且开合规发票 → Prakas 578(2024) 豁免，WHT = 0；</li>
 *   <li>{@code INDIVIDUAL} 个人 → 租金 10% / 服务费 15%；</li>
 *   <li>{@code NON_RESIDENT} 非居民（境外）→ 默认 14%（中柬协定 10% 可配置，见 {@code claw.wht.nonResidentRate}）；</li>
 *   <li>{@code UNREGISTERED} / null → 按 whtCategory 法定税率（SERVICE 15% / RENTAL 10% / DIVIDEND 0% / NONE 0%）。</li>
 * </ul>
 *
 * <p><b>金额精度</b>：whtAmount / net 均按 4 位 HALF_UP 四舍五入；{@code net = gross − whtAmount}。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhtEngine {

    /** 金额标度（保留 4 位，HALF_UP）。 */
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** 服务费 WHT 税率。 */
    private static final BigDecimal SERVICE_RATE = new BigDecimal("0.15");
    /** 租金 WHT 税率。 */
    private static final BigDecimal RENTAL_RATE = new BigDecimal("0.10");

    /** 非居民 WHT 税率（可配置；中柬协定 10% 时设为 0.10，默认按法律 14%）。 */
    @Value("${claw.wht.nonResidentRate:0.14}")
    private BigDecimal nonResidentRate;

    private final VirtualSubAccountService virtualSubAccountService;

    /**
     * 按虚拟子户 id 读取税务档案后计算 WHT。
     *
     * @param payeeVsaId 收款方虚拟子户 id（平台自有收入等无第三方收款方传 {@code null} → 不代扣）
     * @param gross      代扣前毛额（必须 &gt; 0）
     * @param currency   币种（仅用于日志，不影响计算）
     * @return WHT 计算结果
     * @throws BizException 子户 id 非空但档案不存在（配置错误：清分层已解析出子户却查不到）
     */
    public WhtResult compute(Long payeeVsaId, BigDecimal gross, String currency) {
        if (gross == null || gross.signum() <= 0) {
            BigDecimal safe = gross == null ? BigDecimal.ZERO : gross;
            return WhtResult.noWithholding(safe);
        }
        if (payeeVsaId == null) {
            // 平台自有收入等无第三方收款方，不代扣；其余场景的收款方税务档案应在接入处保证已配置
            log.debug("[WHT] payeeVsaId 为空，按不代扣处理（gross={}, currency={}）", gross, currency);
            return WhtResult.noWithholding(gross);
        }
        VirtualSubAccount vsa = virtualSubAccountService.findById(payeeVsaId)
                .orElseThrow(() -> BizException.notFound("error.clearing.wht.profile.unresolved", payeeVsaId));
        return compute(vsa.getTaxpayerStatus(), vsa.getWhtCategory(), gross);
    }

    /**
     * 纯函数：按纳税人状态 + WHT 类别计算 WHT。
     *
     * @param status   纳税人状态（null 视为未登记处理）
     * @param category WHT 类别（非 REGISTERED 且非 null 状态下不能为 null）
     * @param gross    代扣前毛额（必须 &gt; 0）
     * @return WHT 计算结果
     * @throws BizException 需要代扣却缺类别 / 类别无法识别税率
     */
    public WhtResult compute(TaxpayerStatus status, WhtCategory category, BigDecimal gross) {
        if (gross == null || gross.signum() <= 0) {
            BigDecimal safe = gross == null ? BigDecimal.ZERO : gross;
            return WhtResult.noWithholding(safe);
        }
        BigDecimal rate = resolveRate(status, category);
        BigDecimal whtAmount = gross.multiply(rate).setScale(SCALE, ROUNDING);
        BigDecimal net = gross.subtract(whtAmount).setScale(SCALE, ROUNDING);
        return new WhtResult(gross, rate, whtAmount, net);
    }

    /**
     * 税率解析（矩阵核心）。
     *
     * @param status   纳税人状态
     * @param category WHT 类别
     * @return 适用税率（0~1）
     */
    private BigDecimal resolveRate(TaxpayerStatus status, WhtCategory category) {
        if (status == TaxpayerStatus.REGISTERED) {
            return BigDecimal.ZERO; // Prakas 578 合规发票豁免
        }
        if (category == null) {
            throw BizException.invalidParam("error.clearing.wht.category.invalid", status);
        }
        if (status == TaxpayerStatus.INDIVIDUAL) {
            return category == WhtCategory.RENTAL ? RENTAL_RATE : SERVICE_RATE;
        }
        if (status == TaxpayerStatus.NON_RESIDENT) {
            return nonResidentRate; // 可配置（中柬协定 10% 时设为 0.10）
        }
        // UNREGISTERED 或 null：按类别法定税率
        return categoryRate(category);
    }

    /**
     * 按 WHT 类别取法定税率。
     *
     * @param category 类别（非 null）
     * @return 税率
     */
    private BigDecimal categoryRate(WhtCategory category) {
        return switch (category) {
            case SERVICE -> SERVICE_RATE;
            case RENTAL -> RENTAL_RATE;
            case DIVIDEND -> BigDecimal.ZERO;
            case NONE -> BigDecimal.ZERO;
            default -> throw BizException.invalidParam("error.clearing.wht.rate.unconfigured", category);
        };
    }

    /**
     * WHT 计算结果。
     *
     * @param gross      代扣前毛额
     * @param whtRate    适用税率（0~1）
     * @param whtAmount  代扣税额
     * @param net        代扣后净额（= gross − whtAmount）
     */
    public record WhtResult(BigDecimal gross, BigDecimal whtRate, BigDecimal whtAmount, BigDecimal net) {

        /** 不代扣结果（net = gross，wht 为 0）。 */
        public static WhtResult noWithholding(BigDecimal gross) {
            return new WhtResult(gross, BigDecimal.ZERO, BigDecimal.ZERO, gross);
        }
    }
}
