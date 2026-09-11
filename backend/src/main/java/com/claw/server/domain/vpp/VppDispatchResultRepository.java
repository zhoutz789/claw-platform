package com.claw.server.domain.vpp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VppDispatchResultRepository extends JpaRepository<VppDispatchResult, Long> {

    List<VppDispatchResult> findByOrderIdOrderBySampleAtAsc(Long orderId);
}
