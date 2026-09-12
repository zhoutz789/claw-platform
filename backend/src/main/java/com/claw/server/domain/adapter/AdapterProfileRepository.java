package com.claw.server.domain.adapter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdapterProfileRepository extends JpaRepository<AdapterProfile, Long> {

    Optional<AdapterProfile> findByProtocol(String protocol);
}
