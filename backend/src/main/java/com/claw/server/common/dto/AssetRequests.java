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
            BigDecimal depositValue,
            Long ownerId
    ) {
    }

    public static record CreateDrone(
            @NotBlank String assetNo,
            String qrCode,
            @NotBlank String remoteId,      // Remote ID 广播码（合规必填）
            @NotBlank String model,
            Integer maxFlightTimeMin,
            BigDecimal maxPayloadKg,
            com.claw.server.common.enums.DronePayloadType payloadType,
            String airworthinessCertNo,    // SSCA 适航证
            String pilotLicenseNo,
            String protocolVer,
            Long ownerId
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

    /** 设备上线部署（绑定到站点/产权人）。上线即写入产权链首笔（DEPLOY）。 */
    public static record BindDevice(
            @NotNull Long assetId,
            Long stationId,
            String imei,
            String deviceType,   // VEHICLE_TCU / BATTERY_BMS / CHARGER，缺省按 assetType 推导
            String location
    ) {
    }

    /**
     * 从订单登记生成资产请求（V38）。
     *
     * <p>由 {@code UnitRegistrationService} 构造，经 {@code AssetService.provisionFromRegistration}
     * 建主资产 + 按类型建扩展 + 生命周期 PRODUCED + 建立产权（ownerId）+ 写入溯源字段。
     * common 层类型，仅用基础类型 / String / BigDecimal。
     */
    public static record ProvisionAssetReq(
            String assetType,        // VEHICLE / EV / DRONE / BATTERY / CHARGER / PV_STATION
            String assetNo,          // 资产编号（可空，缺省自动生成 ASSET-<UUID>）
            String qrCode,           // 逐台二维码（必填，唯一）
            Long productId,          // 产品端溯源
            Long skuId,              // 商品端溯源
            Long manufacturerId,     // 厂家
            String serialNumber,     // 出厂序列号
            Long ownerId,            // 买家（产权人）
            Long orderItemId,        // 订单项溯源
            String vin,              // 车辆
            String frameNo,          // 车辆
            String motorNo,          // 车辆
            String remoteId,         // 无人机 Remote ID
            String model,            // 型号（车辆/电池/无人机通用）
            BigDecimal capacityKwh, // 电池容量
            String componentNosJson  // 当前主部件编号快照
    ) {
    }

    /** 主部件更换留痕请求（F7.4 / F16.5）。 */
    public static record ReplaceComponentReq(
            @NotBlank String componentType,   // MOTOR/BATTERY/CONTROLLER/REMOTE/CHARGER...
            String oldComponentNo,
            @NotBlank String newComponentNo
    ) {
    }
}
