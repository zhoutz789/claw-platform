package com.claw.server.domain.user;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.security.JwtUtil;
import com.claw.server.domain.role.RoleGrantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 认证服务：短信验证码登录（OTP，无密码）。
 *
 * <p>注册即登录：未知手机号首次带有效验证码登录时自动建号，并触发人人经济自动角色包授予。
 * dev 环境 {@code sms-dev-echo=true} 时 {@code sendSmsCode} 把验证码回显，便于联调。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final SmsCodeStore smsCodeStore;
    private final RoleGrantService roleGrantService;
    private final JwtUtil jwtUtil;

    @Value("${claw.security.sms-dev-echo:true}")
    private boolean smsDevEcho;

    /** 发送验证码；dev 回显验证码（生产改为网关发送）。 */
    public String sendSmsCode(String phone) {
        String code = smsCodeStore.issue(phone);
        if (smsDevEcho) {
            return code;
        }
        log.info("已向 {} 发送验证码（生产经 SMS 网关）", phone);
        return null;
    }

    /** 验证码登录：未知用户自动注册并授予自动角色包。 */
    @Transactional
    public ApiViews.AuthResp login(String phone, String code) {
        if (!smsCodeStore.verify(phone, code)) {
            throw BizException.invalidParam("error.auth.code.invalid");
        }
        User user = userRepository.findByPhone(phone)
                .orElseGet(() -> register(phone));
        String token = jwtUtil.issue(user.getId(), user.getPhone(), summarizeRoles(user.getId()));
        return new ApiViews.AuthResp(token, user.getId(), user.getPhone());
    }

    @Transactional
    public User register(String phone) {
        User user = User.builder().phone(phone).build();
        user = userRepository.save(user);
        roleGrantService.grantAutoRoles(user.getId());
        log.info("新用户注册 {}", user.getId());
        return user;
    }

    private String summarizeRoles(Long userId) {
        // JWT 仅带轻量摘要（逗号分隔角色码），完整权限运行时计算
        return String.join(",", roleGrantService.listActive(userId).stream()
                .map(r -> r.roleCode()).toList());
    }

    public ApiViews.UserProfile profile(Long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("error.user.not.found"));
        return new ApiViews.UserProfile(u.getId(), u.getPhone(), u.getFullName(),
                u.getKycStatus(), u.getLocale());
    }

    /** 更新最后活跃时间等（占位，后续扩展资料字段）。 */
    @Transactional
    public void touch(Long userId) {
        userRepository.findById(userId).ifPresent(u -> {
            u.setUpdatedAt(Instant.now());
            userRepository.save(u);
        });
    }
}
