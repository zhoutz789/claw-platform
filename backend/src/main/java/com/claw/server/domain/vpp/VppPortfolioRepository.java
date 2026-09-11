package com.claw.server.domain.vpp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VppPortfolioRepository extends JpaRepository<VppPortfolio, Long> {

    List<VppPortfolio> findByStatusOrderByIdAsc(String status);
}
