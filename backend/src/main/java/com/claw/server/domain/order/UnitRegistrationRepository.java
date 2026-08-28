package com.claw.server.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 逐台登记台账仓储（订单域自有，不跨域）。
 */
public interface UnitRegistrationRepository extends JpaRepository<UnitRegistration, Long> {

    /** 某订单项下某状态的登记列表（发货守卫 / 进度统计用）。 */
    List<UnitRegistration> findByOrderItemIdAndStatus(Long orderItemId,
                                                      UnitRegistration.UnitRegistrationStatus status);

    /** 某订单项下某状态的登记数（发货守卫：== quantity 才放行）。 */
    long countByOrderItemIdAndStatus(Long orderItemId, UnitRegistration.UnitRegistrationStatus status);

    /** 某订单的全部登记（进度面板列表）。 */
    List<UnitRegistration> findByOrderId(Long orderId);

    /** 某订单项的全部登记（序号计算 / 进度统计）。 */
    List<UnitRegistration> findByOrderItemId(Long orderItemId);

    /** 按资产查登记（溯源 / 纠错）。 */
    Optional<UnitRegistration> findByAssetId(Long assetId);

    /** 按 (订单项, 序号) 查（序号唯一性校验）。 */
    Optional<UnitRegistration> findByOrderItemIdAndSeq(Long orderItemId, int seq);

    /** 按二维码查（重复扫描判重）。 */
    Optional<UnitRegistration> findByQrCode(String qrCode);
}
