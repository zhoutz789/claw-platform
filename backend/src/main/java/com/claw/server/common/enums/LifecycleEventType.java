package com.claw.server.common.enums;

/** 流通链事件类型（lifecycle_events.event_type）。 */
public enum LifecycleEventType {
    PRODUCE,
    CERTIFY,
    SHIP,
    RECEIVE,
    TRANSFER_OUT,
    TRANSFER_IN,
    PICKUP,
    DEPLOY,
    RECALL,
    RETURN
}
