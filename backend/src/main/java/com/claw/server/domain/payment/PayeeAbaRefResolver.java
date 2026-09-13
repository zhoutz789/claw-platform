package com.claw.server.domain.payment;

import java.util.Optional;

/**
 * 收款方 ABA 账户/MID 解析器（分账准入前置校验的数据源）。
 *
 * <p><b>业务准入规则（落地设计附录 A.3-1）</b>：ABA PayWay 要求分账受益人<b>必须是 ABA 账户持有人或
 * ABA MID</b>。因此厂家/服务站/投资人入驻签约时须绑定 ABA 收款账户；未绑定者不能作为分账受益人，
 * 只能降级走「到账即清 / 周期批量代付」。
 *
 * <p><b>为什么是函数式接口而不是具体服务</b>：解析逻辑依赖 L2 托管域（{@code virtual_subaccount.external_sub_no}）
 * 与主体绑定（{@code principal_bindings}）。把它抽象成 SPI，可让 L1 通道层不反向依赖 L2 托管域，
 * 也便于在真实主体绑定口径（设计 §11.4 待明确项）落定后替换实现而不动通道代码。
 */
@FunctionalInterface
public interface PayeeAbaRefResolver {

    /**
     * 解析收款方的 ABA 账户号 / MID。
     *
     * @param payeeType 收款方类型（MANUFACTURER/STATION/LOGISTICS/PLATFORM/...）
     * @param currency  币种（USD/KHR）
     * @return ABA 账户号/MID；未绑定、平台自有方或无法解析时返回 {@link Optional#empty()}
     */
    Optional<String> resolve(String payeeType, String currency);
}
