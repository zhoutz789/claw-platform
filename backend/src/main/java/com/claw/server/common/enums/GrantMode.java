package com.claw.server.common.enums;

/**
 * 子账号授权模式（增量 C · O29）。
 *
 * <dl>
 *   <dt>{@link #ALL}</dt>
 *   <dd>全部功能：跟随主账号角色模板（只存 template_code，不落明细）。
 *       平台新增功能时只需注册新权限码并挂到模板，已授权 ALL 的子账号<b>零改动自动获得</b> ——
 *       这是满足「以后功能也会不断增加扩展」的核心机制。</dd>
 *   <dt>{@link #PARTIAL}</dt>
 *   <dd>部分功能：按权限码勾选，落 {@code sub_account_grant_items}；
 *       实际生效集合 = 明细 ∩ 主账号模板集合（O30 交集防越权）。</dd>
 * </dl>
 */
public enum GrantMode {

    ALL,
    PARTIAL
}
