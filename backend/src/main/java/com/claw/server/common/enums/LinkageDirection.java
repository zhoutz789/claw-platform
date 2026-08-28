package com.claw.server.common.enums;

/**
 * 设备数据联动方向（四向闭环）。
 * 遥测/轨迹到达后，向下游四个业务域驱动闭环：
 * <ul>
 *   <li>ASSET_UPDATE — 资产档案更新（运营态快照刷新）</li>
 *   <li>REVENUE — 收益分账重算</li>
 *   <li>RISK — 风控告警</li>
 *   <li>LIFECYCLE — 全生命周期状态推进</li>
 * </ul>
 */
public enum LinkageDirection {
    ASSET_UPDATE,
    REVENUE,
    RISK,
    LIFECYCLE
}
