package com.claw.server.domain.jurisdiction;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.JurisdictionViews;
import com.claw.server.common.enums.JurisdictionStatus;
import com.claw.server.common.security.CountryContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 法域服务：按国家解析活跃身份/支付/牌照适配器，并暴露国家清单。
 * 共营框架核心——进一国只需在注册表插数据，核心业务代码不变。
 */
@Service
@RequiredArgsConstructor
public class JurisdictionService {

    private final CountryRepository countryRepository;
    private final IdentityProviderRepository identityProviderRepository;
    private final PaymentProviderRepository paymentProviderRepository;
    private final RegulatoryLicenseRepository regulatoryLicenseRepository;
    private final TenantRepository tenantRepository;

    /** 当前请求所属国家（来自 X-Country-Code，缺省 KHM）。 */
    public Country currentCountry() {
        Country c = countryRepository.findByCode(CountryContext.countryCode())
                .orElseThrow(() -> BizException.of(40450, "error.country.not.found"));
        if (c.getStatus() == JurisdictionStatus.EXCLUDED) {
            throw BizException.of(40950, "error.country.excluded");
        }
        return c;
    }

    public JurisdictionViews.CountryView countryView(String code) {
        Country c = countryRepository.findByCode(code)
                .orElseThrow(() -> BizException.of(40450, "error.country.not.found"));
        return toView(c);
    }

    public List<JurisdictionViews.CountryView> listCountries(JurisdictionStatus status) {
        List<Country> list = (status == null)
                ? countryRepository.findAll()
                : countryRepository.findByStatus(status);
        return list.stream().map(JurisdictionService::toView).toList();
    }

    public List<JurisdictionViews.IdentityProviderView> identityProviders(String code) {
        return identityProviderRepository.findByCountryCodeAndActiveTrueOrderByPriorityAsc(code).stream()
                .map(p -> new JurisdictionViews.IdentityProviderView(
                        p.getProviderCode(), p.getProtocol(), p.getDisplayName(), p.getPriority()))
                .toList();
    }

    public List<JurisdictionViews.PaymentProviderView> paymentProviders(String code) {
        return paymentProviderRepository.findByCountryCodeAndActiveTrueOrderByProviderCodeAsc(code).stream()
                .map(p -> new JurisdictionViews.PaymentProviderView(
                        p.getProviderCode(), p.getProviderType(), p.isActive()))
                .toList();
    }

    public List<JurisdictionViews.LicenseView> licenses(String code) {
        return regulatoryLicenseRepository.findByCountryCodeOrderByLicenseTypeAsc(code).stream()
                .map(l -> new JurisdictionViews.LicenseView(
                        l.getLicenseType(), l.getAuthority(), l.getStatus(), l.isRequired(), l.getNotes()))
                .toList();
    }

    /** 当前法域完整摘要（/jurisdictions/me）。 */
    public JurisdictionViews.CurrentJurisdictionView current() {
        Country c = currentCountry();
        Tenant tenant = tenantRepository.findByCountryCode(c.getCode()).orElse(null);
        return new JurisdictionViews.CurrentJurisdictionView(
                toView(c),
                tenant == null ? null : tenant.getName(),
                tenant == null ? null : tenant.getOperatorType(),
                identityProviders(c.getCode()),
                paymentProviders(c.getCode()),
                licenses(c.getCode()));
    }

    private static JurisdictionViews.CountryView toView(Country c) {
        return new JurisdictionViews.CountryView(
                c.getCode(), c.getNameEn(), c.getNameLocal(), c.getRegion(),
                c.getCurrencyCode(), c.getDefaultLocale(), c.getPilotOrder(),
                c.getStatus(), c.isDataResidency());
    }
}
