package com.claw.server.common.enums;

import com.claw.server.common.api.BizException;

import java.util.Arrays;

/**
 * 业务主体类型（增量 C · Q9 拍板：商家是独立主体，与厂家、服务站并列）。
 *
 * <p>与 {@code principal_bindings.principal_type}、{@code sub_accounts.owner_principal_type}、
 * {@code v_org_governance.principal_type}、角色模板 code 五处取值保持一致。
 */
public enum PrincipalType {

    STATION("stations"),
    MANUFACTURER("manufacturers"),
    /** 商家：独立经营主体，不挂靠服务站（Q9）。 */
    MERCHANT("merchants");

    private final String tableName;

    PrincipalType(String tableName) {
        this.tableName = tableName;
    }

    /** 对应物理表名（组织治理写操作时用于定位主体表）。 */
    public String tableName() {
        return tableName;
    }

    /**
     * 解析主体类型字符串（大小写/空白容忍）。
     *
     * @param value 主体类型字符串，如 "STATION"
     * @return 枚举值
     * @throws BizException 40401 onboarding.principal.type.unknown
     */
    public static PrincipalType of(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.of(40401, "onboarding.principal.type.unknown", String.valueOf(value));
        }
        String norm = value.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(t -> t.name().equals(norm))
                .findFirst()
                .orElseThrow(() -> BizException.of(40401, "onboarding.principal.type.unknown", value));
    }
}
