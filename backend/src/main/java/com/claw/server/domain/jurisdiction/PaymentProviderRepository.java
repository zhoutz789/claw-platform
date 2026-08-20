package com.claw.server.domain.jurisdiction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentProviderRepository extends JpaRepository<PaymentProvider, Long> {
    List<PaymentProvider> findByCountryCodeAndActiveTrueOrderByProviderCodeAsc(String countryCode);
}
