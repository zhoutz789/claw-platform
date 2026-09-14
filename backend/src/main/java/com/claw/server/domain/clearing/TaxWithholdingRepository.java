package com.claw.server.domain.clearing;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * WHT 代扣台账仓储（claw.tax_withholding）。
 */
public interface TaxWithholdingRepository extends JpaRepository<TaxWithholding, Long> {
}
