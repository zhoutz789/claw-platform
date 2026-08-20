package com.claw.server.common.dto;

import com.claw.server.common.enums.AclRelation;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.ContractType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** 资产域入参：车辆/电池创建、ACL 设置、状态变更。 */
public final class AssetRequests {

    private AssetRequests() {
    }

    public static record CreateVehicle(
            @NotBlank String assetNo,
            String qrCode,
            @NotBlank String model,
            String vin,
            String frameNo,
            String motorNo,
            ContractType contractType,
            Long lessorId,
            String protocolVer,
            Long ownerId
    ) {
    }

    public static record CreateBattery(
            @NotBlank String assetNo,
            String qrCode,
            @NotBlank String model,
            @NotNull BigDecimal capacityKwh,
            String protocolVer,
            BigDecimal soh,
            BigDecimal depositValue
    ) {
    }

    public static record SetAcl(
            @NotNull Long userId,
            @NotNull AclRelation relation
    ) {
    }

    public static record ChangeStatus(
            @NotNull AssetStatus toStatus,
            String reason
    ) {
    }
}
