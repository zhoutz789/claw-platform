package com.claw.server.common.enums;

/**
 * 禁飞图层级别（对应 claw.nfz_layers.level）。
 *
 * <ul>
 *   <li>{@link #ABSOLUTE}：绝对禁飞 —— 图层多边形内恒定拒绝，任何档位/时段都不放行；</li>
 *   <li>{@link #OPERATION}：作业限飞 —— 多边形内默认拒绝（档位放开后可放行）；</li>
 *   <li>{@link #TIME_WINDOW}：时段限飞 —— 仅当当前时刻落在 {@code time_window_json} 窗口内才拒绝。</li>
 * </ul>
 *
 * <p>「恒定拒绝」的例外只有一个：安全指令豁免（LAND / RETURN_HOME / HOLD / PAUSE / CANCEL_TASK），
 * 空中安全优先于任何地面合规规则。
 */
public enum NfzLevel {
    ABSOLUTE,
    OPERATION,
    TIME_WINDOW
}
