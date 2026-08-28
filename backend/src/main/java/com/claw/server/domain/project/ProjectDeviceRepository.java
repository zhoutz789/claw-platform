package com.claw.server.domain.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 项目-设备绑定仓储（对应 claw.project_devices）。
 */
public interface ProjectDeviceRepository extends JpaRepository<ProjectDevice, Long> {

    /** 列出某项目绑定的设备，按 sort_no 升序。 */
    List<ProjectDevice> findByProjectIdOrderBySortNoAsc(Long projectId);

    /** 按资产查其所有项目绑定。 */
    List<ProjectDevice> findByAssetId(Long assetId);

    /** 判定某项目是否已绑定某资产（UNIQUE 约束前置校验）。 */
    boolean existsByProjectIdAndAssetId(Long projectId, Long assetId);

    /** 按项目+资产解绑（幂等）。 */
    void deleteByProjectIdAndAssetId(Long projectId, Long assetId);

    /** 删除某项目下全部设备绑定（删项目前级联解绑）。 */
    void deleteByProjectId(Long projectId);

    /** 按资产查（变相判存在，Optional 语义）。 */
    Optional<ProjectDevice> findByProjectIdAndAssetId(Long projectId, Long assetId);
}
