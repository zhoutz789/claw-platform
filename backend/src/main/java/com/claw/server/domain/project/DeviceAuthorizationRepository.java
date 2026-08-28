package com.claw.server.domain.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 设备授权仓储（对应 claw.device_authorizations）。
 */
public interface DeviceAuthorizationRepository extends JpaRepository<DeviceAuthorization, Long> {

    /** 按资产查其全部授权记录。 */
    List<DeviceAuthorization> findByAssetId(Long assetId);

    /** 按资产 + 授权类型查。 */
    List<DeviceAuthorization> findByAssetIdAndAuthType(Long assetId, String authType);

    /** 按被授权人查（其获得的使用权/共享）。 */
    List<DeviceAuthorization> findByGranteeUserId(Long granteeUserId);
}
