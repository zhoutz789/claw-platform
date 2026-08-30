package com.claw.server.domain.subaccount;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubAccountGrantItemRepository extends JpaRepository<SubAccountGrantItem, SubAccountGrantItem.Key> {

    List<SubAccountGrantItem> findByGrantId(Long grantId);

    void deleteByGrantId(Long grantId);
}
