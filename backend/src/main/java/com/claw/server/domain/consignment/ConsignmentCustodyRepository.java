package com.claw.server.domain.consignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 寄售占有权仓储。
 *
 * <p><b>一台设备 = 一条当前占有权 + N 条历史占有权。</b>占有权流转（站间调拨收货）会关掉旧行
 * （写 {@code ended_at/ended_reason}）并开新行接管，故 {@code device_id} 上不是全表唯一，
 * 而是 V63 的部分唯一索引 {@code uq_cc_device_active (device_id) WHERE ended_at IS NULL}。
 *
 * <p>因此<b>取「当前」占有权一律走 {@link #findByDeviceIdAndEndedAtIsNull}</b>：按
 * {@code device_id} 做无条件的 {@code Optional} 查询在设备发生过调拨后会命中多行，
 * 抛 {@code IncorrectResultSizeDataAccessException}。查历史用 {@link #findByDeviceIdOrderByIdDesc}。
 */
public interface ConsignmentCustodyRepository extends JpaRepository<ConsignmentCustody, Long> {

    /**
     * 该设备<b>当前</b>有效的占有权（未结束的那一行）。
     *
     * <p>与部分唯一索引 {@code uq_cc_device_active} 同口径，最多命中一行。
     *
     * @param deviceId 设备 ID
     * @return 当前占有权；设备从未入站或占有权已全部结束则为空
     */
    Optional<ConsignmentCustody> findByDeviceIdAndEndedAtIsNull(Long deviceId);

    /**
     * 该设备的全部占有权（含已结束的历史行），按 ID 倒序 —— 最新的在最前。
     *
     * @param deviceId 设备 ID
     * @return 占有权流转留痕（可为空列表）
     */
    List<ConsignmentCustody> findByDeviceIdOrderByIdDesc(Long deviceId);

    List<ConsignmentCustody> findByManufacturerId(Long manufacturerId);

    List<ConsignmentCustody> findByHolderStationId(Long stationId);

    List<ConsignmentCustody> findByTransferOrderId(Long transferOrderId);
}
