package com.claw.server.common.enums;

/** 产权/占有权类型。OWNED_BY_MFG=厂家自有；CONSIGNED=寄售（货权在厂家，服务站仅占有）；FULL=完整产权（含处置权，资产已售出给用户）。 */
public enum OwnershipType {
    OWNED_BY_MFG,
    CONSIGNED,
    FULL
}
