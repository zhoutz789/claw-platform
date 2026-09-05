package com.claw.server.common.enums;

/**
 * 服务站合约状态（对应 V73 claw.station_contracts.status）。
 *
 * <ul>
 *   <li>PENDING —— 已生成待签署（激活流水线中）；</li>
 *   <li>ACTIVE —— 生效中（生效起 ~ 生效止，默认 3 年）；</li>
 *   <li>EXPIRED —— 到期未续签，自动置为到期；</li>
 *   <li>EXIT_REQUESTED —— 到期前后申请退出，进入保证金清算（3 月内退还）；</li>
 *   <li>EXITED —— 已清退（保证金已退/已扣），合约终止。</li>
 * </ul>
 */
public enum ContractStatus {
    PENDING,
    ACTIVE,
    EXPIRED,
    EXIT_REQUESTED,
    EXITED
}
