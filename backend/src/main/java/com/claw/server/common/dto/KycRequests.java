package com.claw.server.common.dto;

import jakarta.validation.constraints.NotBlank;

/** KYC 入参：实名（MANUAL）/ CamDigiKey eKYC（OAuth2.0）。 */
public final class KycRequests {

    private KycRequests() {
    }

    /** 平台实名（dev 阶段直接置 VERIFIED；生产接 NBC/第三方核验）。 */
    public static record Manual(@NotBlank String idType) {
    }

    /** CamDigiKey 国家数字身份授权回执（OAuth2.0 code 换 token 后回填）。 */
    public static record Camdigikey(@NotBlank String camdigikeyTokenRef,
                                    String fieldsGranted,
                                    String consentVersion) {
    }
}
