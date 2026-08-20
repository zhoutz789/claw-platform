package com.claw.server.common.dto;

import com.claw.server.common.enums.CountryRegion;
import com.claw.server.common.enums.JurisdictionStatus;
import com.claw.server.common.enums.NodeRole;
import com.claw.server.common.enums.OperatorType;
import com.claw.server.common.enums.ProviderType;
import com.claw.server.common.enums.LicenseStatus;

import java.util.List;

/** 全球化运营 / 法域视图（控制器出参，仅依赖 common.enums，不引 domain 实体）。 */
public final class JurisdictionViews {

    private JurisdictionViews() {
    }

    public static record CountryView(
            String code, String nameEn, String nameLocal, CountryRegion region,
            String currencyCode, String defaultLocale, int pilotOrder,
            JurisdictionStatus status, NodeRole nodeRole,
            String tradePolicy, boolean dataResidency) {
    }

    public static record IdentityProviderView(
            String providerCode, String protocol, String displayName, int priority) {
    }

    public static record PaymentProviderView(
            String providerCode, ProviderType providerType, boolean active) {
    }

    public static record LicenseView(
            String licenseType, String authority, LicenseStatus status, boolean required, String notes) {
    }

    /** 当前请求所属法域的完整适配摘要。 */
    public static record CurrentJurisdictionView(
            CountryView country, String tenantName, OperatorType operatorType,
            List<IdentityProviderView> identityProviders,
            List<PaymentProviderView> paymentProviders,
            List<LicenseView> licenses) {
    }
}
