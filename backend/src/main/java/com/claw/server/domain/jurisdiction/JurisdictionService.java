package com.claw.server.domain.jurisdiction;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.JurisdictionViews;
import com.claw.server.common.enums.JurisdictionStatus;
import com.claw.server.common.enums.NodeRole;
import com.claw.server.common.security.CountryContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 法域服务：按国家解析活跃身份/支付/牌照适配器，并暴露国家清单。
 * 共营框架核心——进一国只需在注册表插数据，核心业务代码不变。
 * 全球一家：所有国家都是互通节点，不再有排除国概念。
 */
@Service
@RequiredArgsConstructor
public class JurisdictionService {

    private final CountryRepository countryRepository;
    private final IdentityProviderRepository identityProviderRepository;
    private final PaymentProviderRepository paymentProviderRepository;
    private final RegulatoryLicenseRepository regulatoryLicenseRepository;
    private final TenantRepository tenantRepository;

    /** 当前请求所属国家（来自 X-Country-Code，缺省 KHM）。全球开放，无排除校验。 */
    public Country currentCountry() {
        return countryRepository.findByCode(CountryContext.countryCode())
                .orElseThrow(() -> BizException.of(40450, "error.country.not.found"));
    }

    public JurisdictionViews.CountryView countryView(String code) {
        Country c = countryRepository.findByCode(code)
                .orElseThrow(() -> BizException.of(40450, "error.country.not.found"));
        return toView(c);
    }

    public List<JurisdictionViews.CountryView> listCountries(JurisdictionStatus status, NodeRole nodeRole) {
        List<Country> list;
        if (status != null && nodeRole != null) {
            list = countryRepository.findByStatusAndNodeRole(status, nodeRole);
        } else if (status != null) {
            list = countryRepository.findByStatus(status);
        } else if (nodeRole != null) {
            list = countryRepository.findByNodeRole(nodeRole);
        } else {
            list = countryRepository.findAll();
        }
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
                c.getStatus(), c.getNodeRole(), c.getTradePolicyJson(), c.isDataResidency());
    }
}
