package com.claw.server.domain.project;

/**
 * 设备授权类型（对应 claw.device_authorizations.auth_type）。
 *
 * <p>三态正交：
 * <ul>
 *   <li>{@link #TRANSFER}  —— 所有权→资产大厅（公开可见）；</li>
 *   <li>{@link #SHARE}     —— 入共享池；</li>
 *   <li>{@link #AUTHORIZE} —— 仅授予使用权（不转移所有权）。</li>
 * </ul>
 * 放在本包（domain/project）而非 common，避免 common 反向依赖 domain；
 * DTO 层以 String 透传，由服务层经本枚举校验。
 */
public enum DeviceAuthType {
    /** 转让：所有权转移至资产大厅（公开可见）。 */
    TRANSFER,
    /** 共享：资产入共享池。 */
    SHARE,
    /** 授权：仅授予使用权（不转移所有权）。 */
    AUTHORIZE
}
