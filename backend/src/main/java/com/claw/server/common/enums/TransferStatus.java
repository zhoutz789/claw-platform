package com.claw.server.common.enums;

/** 站间调拨单状态（R5）。DRAFT→CREATED→IN_TRANSIT→COMPLETED；CANCELLED。 */
public enum TransferStatus {
    DRAFT,
    CREATED,
    IN_TRANSIT,
    COMPLETED,
    CANCELLED
}
