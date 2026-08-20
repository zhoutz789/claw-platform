package com.claw.server.domain.user;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.KycRequests;
import com.claw.server.common.enums.KycMethod;
import com.claw.server.common.enums.KycStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * KYC 服务：支持两种合规认证路径。
 *
 * <ul>
 *   <li>{@code MANUAL}：平台实名（dev 阶段直接置 VERIFIED；生产对接 NBC/第三方证件核验）；</li>
 *   <li>{@code CAMDIGIKEY}：柬埔寨国家数字身份 OAuth2.0 eKYC（MPTC 签发）。
 *       本方法为接入 stub——真实流程为：APP 拉起 CamDigiKey 授权页 → 拿 code →
 *       后端用 code 换 token 并 introspect 校验 → 落 camdigikey_ref。此处接收回执并标记已认证。</li>
 * </ul>
 * 不存储原始证件，仅存授权引用与已授权字段清单（脱敏）。
 */
@Service
@RequiredArgsConstructor
public class KycService {

    private final UserRepository userRepository;
    private final KycRecordRepository kycRecordRepository;

    /** 平台实名认证（dev：直接通过）。 */
    @Transactional
    public ApiViews.KycView submitManual(Long userId, KycRequests.Manual req) {
        User user = load(userId);
        KycRecord record = KycRecord.builder()
                .userId(userId)
                .method(KycMethod.MANUAL)
                .status(KycStatus.VERIFIED)
                .idType(req.idType())
                .consentVersion("v1.0")
                .verifiedAt(Instant.now())
                .build();
        record = kycRecordRepository.save(record);
        markVerified(user);
        return toView(record);
    }

    /** CamDigiKey eKYC 授权回执落库（OAuth2.0 接入点，详见类注释）。 */
    @Transactional
    public ApiViews.KycView submitCamdigikey(Long userId, KycRequests.Camdigikey req) {
        User user = load(userId);
        KycRecord record = KycRecord.builder()
                .userId(userId)
                .method(KycMethod.CAMDIGIKEY)
                .status(KycStatus.VERIFIED)
                .idType("CAMDIGIKEY")
                .camdigikeyTokenRef(req.camdigikeyTokenRef())
                .fieldsGranted(req.fieldsGranted())
                .consentVersion(req.consentVersion() == null ? "v1.0" : req.consentVersion())
                .verifiedAt(Instant.now())
                .build();
        record = kycRecordRepository.save(record);
        user.setCamdigikeyRef(req.camdigikeyTokenRef());
        markVerified(user);
        return toView(record);
    }

    private User load(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("error.user.not.found"));
    }

    private void markVerified(User user) {
        user.setKycStatus(KycStatus.VERIFIED);
        user.setKycVerifiedAt(Instant.now());
        userRepository.save(user);
    }

    private ApiViews.KycView toView(KycRecord r) {
        return new ApiViews.KycView(r.getId(), r.getMethod(), r.getStatus(), r.getVerifiedAt());
    }
}
