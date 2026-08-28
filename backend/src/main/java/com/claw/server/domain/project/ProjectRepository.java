package com.claw.server.domain.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 项目仓储（对应 claw.projects）。
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** 按所有者列出项目，按 sort_no 升序（用于组装父子树）。 */
    List<Project> findByOwnerUserIdOrderBySortNoAsc(Long ownerUserId);

    /** 列出某父项目下的直接子项目。 */
    List<Project> findByParentId(Long parentId);
}
