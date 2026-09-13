package com.claw.server.domain.user;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.security.JwtUtil;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.RoleGrantService;
import com.claw.server.domain.role.RoleView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

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
    private final PermissionService permissionService;
    private final JwtUtil jwtUtil;
    private final SmsGateway smsGateway;
    private final PasswordEncoder passwordEncoder;

    @Value("${claw.security.sms-dev-echo:true}")
    private boolean smsDevEcho;

    /** 发送验证码；dev 回显验证码（生产经 SMS 网关真实下发）。 */
    public String sendSmsCode(String phone) {
        String code = smsCodeStore.issue(phone);
        if (smsDevEcho) {
            return code;
        }
        // 生产路径：经 SMS 网关真实下发（mock 仅日志），不再回显
        smsGateway.send(phone, code);
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

    /**
     * 当前登录态详情：聚合身份、账号语言、生效角色与有效权限位集合。
     * userId 为 null（理论上 SecurityConfig 已拦截未登录）时返回空壳，避免空指针。
     */
    public ApiViews.AuthMeView me(Long userId) {
        if (userId == null) {
            return new ApiViews.AuthMeView(null, null, null, List.of(), Set.of());
        }
        User u = userRepository.findById(userId).orElse(null);
        String phone = u == null ? null : u.getPhone();
        String locale = u == null ? null : u.getLocale();
        List<String> roles = roleGrantService.listActive(userId).stream()
                .map(RoleView::roleCode).toList();
        Set<String> permissions = permissionService.effectivePermissions(userId);
        return new ApiViews.AuthMeView(userId, phone, locale, roles, permissions);
    }

    /** 更新当前账号语言偏好（PUT /users/me/locale）。 */
    @Transactional
    public void updateLocale(Long userId, String locale) {
        if (locale == null || locale.isBlank()) {
            return;
        }
        userRepository.findById(userId).ifPresent(u -> {
            u.setLocale(locale);
            userRepository.save(u);
        });
    }

    /** 更新最后活跃时间等（占位，后续扩展资料字段）。 */
    @Transactional
    public void touch(Long userId) {
        userRepository.findById(userId).ifPresent(u -> {
            u.setUpdatedAt(Instant.now());
            userRepository.save(u);
        });
    }

    /**
     * 账号 + 密码登录。
     * 未设置密码的账号（仅 OTP 注册）引导走短信找回；密码错误按参数异常拒绝。
     */
    public ApiViews.AuthResp loginWithPassword(String phone, String rawPassword) {
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> BizException.invalidParam("error.auth.account.notfound"));
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw BizException.invalidParam("error.auth.password.notset");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw BizException.invalidParam("error.auth.password.wrong");
        }
        String token = jwtUtil.issue(user.getId(), user.getPhone(), summarizeRoles(user.getId()));
        return new ApiViews.AuthResp(token, user.getId(), user.getPhone());
    }

    /**
     * 短信验证码找回 / 重置密码。校验通过后直接覆写密码哈希，不要求旧密码。
     * 用于「忘记密码」以及「修改密码时验证手机号」的等价入口（均走手机号 + 短信码）。
     */
    @Transactional
    public void resetPassword(String phone, String code, String newPassword) {
        if (!smsCodeStore.verify(phone, code)) {
            throw BizException.invalidParam("error.auth.code.invalid");
        }
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> BizException.invalidParam("error.auth.account.notfound"));
        applyPassword(user, newPassword);
        userRepository.save(user);
    }

    /**
     * 登录态下修改密码：校验旧密码（已设密码时）后更新。
     * 若用户此前从未设密码（仅 OTP），则视为首次设置，跳过旧密码校验。
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("error.user.not.found"));
        if (user.getPasswordHash() != null && !user.getPasswordHash().isBlank()) {
            if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
                throw BizException.invalidParam("error.auth.password.wrong");
            }
        }
        applyPassword(user, newPassword);
        userRepository.save(user);
    }

    /** 校验并落地密码哈希（长度下限 6，BCrypt 编码）。 */
    private void applyPassword(User user, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 6) {
            throw BizException.invalidParam("error.auth.password.weak");
        }
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
    }
}
