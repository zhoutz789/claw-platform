package com.claw.server.domain.airspace;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PilotLicenseRepository extends JpaRepository<PilotLicense, Long> {

    /**
     * 执照号是否已存在（登记前预检）。
     *
     * <p>{@code license_no} 上有唯一约束，重复插入会撞
     * {@code DataIntegrityViolationException} → 全局兜底 500。前端拿到 500 无法
     * 判断是「编号重复」还是「服务挂了」，只能提示「操作失败」。这里预检后抛
     * 40962，客户端可直接提示「执照编号已存在，请核对后重试」。
     */
    boolean existsByLicenseNo(String licenseNo);
}
