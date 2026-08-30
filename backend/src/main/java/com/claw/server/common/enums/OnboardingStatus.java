package com.claw.server.common.enums;

/**
 * 组织入驻状态（增量 C ·  §3.2）。
 *
 * <p>与既有 {@code stations.status}（运营状态：ACTIVE / CLOSED / BUILDing）<b>正交并存</b>：
 * 本枚举表达「平台治理的准入状态」，由入驻激活链路与平台管理员维护；运营状态由运营维护。
 * 二者可同时为 {@code DISABLED} + {@code CLOSED}。
 *
 * <p>C 端检索条件为 {@code status='ACTIVE' AND onboarding_status='ACTIVATED'}。
 *
 * <h2>命名：激活态统一为 {@code ACTIVATED}</h2>
 * 本枚举的激活态<b>只叫</b> {@link #ACTIVATED}，与入驻申请单状态机
 * {@link OnboardingApplicationStatus#ACTIVATED} 保持同名。
 *
 * <p>历史原因：早期实现里本枚举写作 {@code ACTIVE}，与申请单的 {@code ACTIVATED} 并存，
 * 且极易与三张主体表的运营状态列 {@code status='ACTIVE'} 混淆 —— 同一个库里
 * {@code ACTIVE} 一会儿表示「营业中」、一会儿表示「已准入」，SQL 里极易写错。
 * V64 已把存量数据从 {@code 'ACTIVE'} 回填为 {@code 'ACTIVATED'}，二者不再并存。
 *
 * <p>为避免再次漂移，判定入口一律走本类的静态方法（{@link #isWritableStatus(String)} /
 * {@link #isDisabledStatus(String)}），不要在业务代码里再写裸字符串。
 */
public enum OnboardingStatus {

    /** 主体记录已创建但未激活（保证金未到账）。 */
    PENDING,
    /** 已激活：可正常产生新单。与入驻申请单终态 {@code ACTIVATED} 同名。 */
    ACTIVATED,
    /** 已禁用：只切断新增，在途订单继续履约（Q7 拍板）。 */
    DISABLED,
    /** 终审驳回。 */
    REJECTED;

    /** 是否允许产生新单（新建履约单 / 建调拨单 / 铺货入站 / 新建子账号 / 再申请入驻）。 */
    public boolean writable() {
        return this == ACTIVATED;
    }

    /**
     * 判断数据库里的 {@code onboarding_status} 字面量是否为「已激活」。
     *
     * <p>业务代码统一走这里，避免各处硬编码裸字符串后再次漂移。
     *
     * @param status 数据库列值，可为 null
     * @return true 表示可写（未被禁用 / 未待激活 / 未驳回）
     */
    public static boolean isWritableStatus(String status) {
        return ACTIVATED.name().equals(status);
    }

    /**
     * 判断数据库里的 {@code onboarding_status} 字面量是否为「已禁用」。
     *
     * @param status 数据库列值，可为 null
     * @return true 表示已被平台禁用
     */
    public static boolean isDisabledStatus(String status) {
        return DISABLED.name().equals(status);
    }
}
