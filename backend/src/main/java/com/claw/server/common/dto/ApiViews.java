package com.claw.server.common.dto;

import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.KycMethod;
import com.claw.server.common.enums.KycStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 通用视图（控制器出参）。 */
public final class ApiViews {

    private ApiViews() {
    }

    public static record AuthResp(String token, Long userId, String phone) {
    }

    /**
     * 当前登录态详情（扩展自原 {@code GET /auth/me} 的数字型 userId）。
     * 用于前端登录后一次性取得身份、语言、角色与权限位集合，驱动权限内核（菜单可见性 / 按钮级控制）。
     */
    public static record AuthMeView(Long userId, String phone, String locale,
                                    List<String> roles, Set<String> permissions) {
    }

    public static record AssetView(Long id, AssetType assetType, String assetNo, String qrCode,
                                   String serialNumber, Long manufacturerId, Long productId, Long skuId,
                                   Long ownerId, Long userId, AssetStatus status, Instant createdAt) {
    }

    public static record UserProfile(Long id, String phone, String fullName, KycStatus kycStatus,
                                     String locale) {
    }

    public static record AdminUserView(Long id, String phone, String fullName, KycStatus kycStatus,
                                       String status, String locale, List<String> roles, Long departmentId) {
    }

    public static record KycView(Long id, KycMethod method, KycStatus status, Instant verifiedAt) {
    }
}
