package com.claw.server.domain.deposit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepositRepository extends JpaRepository<Deposit, Long> {

    Optional<Deposit> findByDepositNo(String depositNo);

    List<Deposit> findByUserIdOrderByCreatedAtDesc(Long userId);
}
