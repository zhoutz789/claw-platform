package com.claw.server.common.enums;

/**
 * 车辆合同结构（对应 vehicles.contract_type）。
 * v0.4 D28：与持牌租赁公司联合放款，平台做获客/风控/IoT。
 */
public enum ContractType {
    FULL_PAYMENT,   // 全款购车
    RENT_TO_OWN,    // 融资租赁 / 期满买断
    FLEET_LEASE     // 车队租赁（B2B）
}
