package com.claw.server.domain.vpp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VppDispatchOrderRepository extends JpaRepository<VppDispatchOrder, Long> {

    List<VppDispatchOrder> findByPortfolioIdOrderByCreatedAtDesc(Long portfolioId);

    List<VppDispatchOrder> findByPortfolioIdAndStatusOrderByCreatedAtDesc(Long portfolioId, String status);
}
