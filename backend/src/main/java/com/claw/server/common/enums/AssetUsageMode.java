package com.claw.server.common.enums;

/**
 * 资产使用模式（锂电池 BMS 对接方案 Phase D，决策：ENERGY_STORAGE 与 BATTERY 互斥，
 * 但换电 BATTERY 在调度中可临时当储能用）。
 *
 * <ul>
 *   <li>{@code SWAP}：作为换电电池参与换电市场（默认）。</li>
 *   <li>{@code STORAGE}：被能源调度引擎临时借调为储能容量（usage mode 切换，非资产类型变更）。</li>
 * </ul>
 * 仅对 {@code BATTERY} 资产有意义；{@code ENERGY_STORAGE} 资产恒为 STORAGE。
 */
public enum AssetUsageMode {
    SWAP,
    STORAGE
}
