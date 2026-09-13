package com.claw.server.domain.funds;

import com.claw.server.common.enums.CustodyOwnerType;
import com.claw.server.domain.payment.PayeeAbaRefResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 基于虚拟子户的收款方 ABA 账户解析器（L2 托管域实现 L1 通道域的 SPI）。
 *
 * <p><b>数据源</b>：{@code virtual_subaccount.external_sub_no}（机构侧子户号 / ABA 账户号 / MID）。
 * 该列正是落地设计附录 A.4「新增前置校验」指定的准入字段：<b>非空才可作为分账受益人</b>。
 *
 * <p><b>映射口径</b>：分账收款方类型（{@code MANUFACTURER/STATION/LOGISTICS/INSURER/INVESTOR}）
 * → {@link CustodyOwnerType} 同名映射；{@code PLATFORM} 等平台自有方返回 {@link Optional#empty()}
 * （其 ABA MID 由通道侧商户配置承载，不来自虚拟子户，{@code ChannelSplitPlanner} 对其豁免前置校验）。
 *
 * <p><b>粒度与降级</b>：精确到 {@code owner_id} 的映射依赖 {@code principal_bindings}
 * （落地设计 §11.4 待明确项），当前按「持有方类型 + 币种」做<b>尽力解析</b>——取首个 ACTIVE 且已绑定
 * {@code external_sub_no} 的子户。解析不到返回空，由调用方降级到账即清 / 周期批量代付，
 * <b>绝不臆造账户、绝不静默丢失收款方</b>。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VirtualSubAccountAbaRefResolver implements PayeeAbaRefResolver {

    /** 只解析 ACTIVE 子户。 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 默认币种（与 {@code AccountService} 现行硬编码口径一致）。 */
    private static final String DEFAULT_CURRENCY = "USD";

    private final VirtualSubAccountRepository virtualSubAccountRepository;

    /**
     * 解析收款方 ABA 账户号 / MID。
     *
     * @param payeeType 收款方类型（MANUFACTURER/STATION/LOGISTICS/INSURER/INVESTOR/PLATFORM）
     * @param currency  币种
     * @return ABA 账户号/MID；平台自有方、类型不支持或未绑定时返回空
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolve(String payeeType, String currency) {
        CustodyOwnerType ownerType = mapOwnerType(payeeType);
        if (ownerType == null) {
            return Optional.empty();
        }
        String ccy = (currency == null || currency.isBlank()) ? DEFAULT_CURRENCY : currency;
        List<VirtualSubAccount> candidates = virtualSubAccountRepository
                .findByOwnerTypeAndCurrencyAndStatusAndDeletedFalseOrderByIdAsc(ownerType, ccy, STATUS_ACTIVE);
        Optional<String> resolved = candidates.stream()
                .map(VirtualSubAccount::getExternalSubNo)
                .filter(no -> no != null && !no.isBlank())
                .findFirst();
        if (resolved.isEmpty()) {
            log.debug("[AbaRef] 收款方 {} 在币种 {} 下无已绑定 ABA 账户的 ACTIVE 子户，分账将降级", payeeType, ccy);
        }
        return resolved;
    }

    /**
     * 分账收款方类型 → 托管持有方类型。
     *
     * @param payeeType 收款方类型
     * @return 对应持有方类型；平台自有方或未知类型返回 {@code null}
     */
    private static CustodyOwnerType mapOwnerType(String payeeType) {
        if (payeeType == null) {
            return null;
        }
        return switch (payeeType) {
            case "MANUFACTURER" -> CustodyOwnerType.MANUFACTURER;
            case "STATION" -> CustodyOwnerType.STATION;
            case "LOGISTICS" -> CustodyOwnerType.LOGISTICS;
            case "INSURER" -> CustodyOwnerType.INSURER;
            case "INVESTOR" -> CustodyOwnerType.INVESTOR;
            // PLATFORM（平台收入）：ABA MID 由通道侧商户配置承载，不由虚拟子户解析
            default -> null;
        };
    }
}
