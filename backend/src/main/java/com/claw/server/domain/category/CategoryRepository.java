package com.claw.server.domain.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByTenantIdAndDeletedFalseOrderBySortNoAsc(Long tenantId);

    List<Category> findByParentIdAndDeletedFalseOrderBySortNoAsc(Long parentId);

    List<Category> findByParentId(Long parentId);

    long countByParentIdAndDeletedFalse(Long parentId);

    Optional<Category> findByIdAndDeletedFalse(Long id);
}
