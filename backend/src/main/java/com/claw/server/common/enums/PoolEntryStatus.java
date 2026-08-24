package com.claw.server.common.enums;

/**
 * 共享池入池状态（对应 V12 shared_pool_entries.status）。
 */
public enum PoolEntryStatus {
    IN_POOL,
    IN_USE,
    IN_TRANSIT,
    REMOVED
}
