package com.claw.server.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletTxnRepository extends JpaRepository<WalletTxn, Long> {
    Optional<WalletTxn> findByTxnNo(String txnNo);

    Optional<WalletTxn> findByPaymentOrderNo(String paymentOrderNo);

    List<WalletTxn> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<WalletTxn> findByStatus(String status);

    List<WalletTxn> findByCreatedAtBetween(java.time.Instant from, java.time.Instant to);
}
