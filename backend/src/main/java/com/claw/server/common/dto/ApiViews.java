package com.claw.server.common.dto;

import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.KycMethod;
import com.claw.server.common.enums.KycStatus;

import java.time.Instant;

/** 通用视图（控制器出参）。 */
public final class ApiViews {

    private ApiViews() {
    }

    public static record AuthResp(String token, Long userId, String phone) {
    }

    public static record AssetView(Long id, AssetType assetType, String assetNo, String qrCode,
                                   Long ownerId, Long userId, AssetStatus status, Instant createdAt) {
    }

    public static record UserProfile(Long id, String phone, String fullName, KycStatus kycStatus,
                                     String locale) {
    }

    public static record KycView(Long id, KycMethod method, KycStatus status, Instant verifiedAt) {
    }
}
