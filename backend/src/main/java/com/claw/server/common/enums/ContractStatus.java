package com.claw.server.common.enums;

/**
 * 服务站合约状态（对应 V73 claw.station_contracts.status）。
 *
 * <ul>
 *   <li>PENDING —— 已生成待签署（激活流水线中）；</li>
 *   <li>ACTIVE —— 生效中（生效起 ~ 生效止，默认 3 年）；</li>
 *   <li>EXPIRED —— 到期未续签，自动置为到期；</li>
 *   <li>EXIT_REQUESTED —— 到期前后申请退出，进入保证金清算（3 月内退还）；</li>
 *   <li>RENEWED —— 升档续签时被新约替代（终态，可多份）；</li>
 *   <li>EXITED —— 已清退（保证金已退/已扣），合约终止。</li>
 * </ul>
 *
 * <p><b>唯一约束</b>：仅进行中状态 ACTIVE / EXIT_REQUESTED 受 UNIQUE(station_id, status) 约束
 * （见 V74 部分唯一索引），终态 RENEWED / EXITED / EXPIRED 允许多份（多次升档会产生多份 RENEWED）。
 */
public enum ContractStatus {
    PENDING,
    ACTIVE,
    EXPIRED,
    EXIT_REQUESTED,
    RENEWED,
    EXITED
}
