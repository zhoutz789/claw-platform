package com.claw.server.domain.advertising;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdAccountRepository extends JpaRepository<AdAccount, Long> {

    /** 按 owner id 解析广告主账户（商家/用户）。 */
    Optional<AdAccount> findByOwnerId(Long ownerId);
}
