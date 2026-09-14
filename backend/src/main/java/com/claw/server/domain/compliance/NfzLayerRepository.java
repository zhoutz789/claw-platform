package com.claw.server.domain.compliance;

import com.claw.server.common.enums.NfzLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 禁飞图层仓储（对应 claw.nfz_layers）。
 */
public interface NfzLayerRepository extends JpaRepository<NfzLayer, Long> {

    /** 启用中的图层（运营限制层评估用）。 */
    List<NfzLayer> findByEnabledTrue();

    /** 固有安全约束图层（恒定拒绝评估用，不看 enabled）。 */
    List<NfzLayer> findBySource(String source);

    /** 全部图层（管理端列表）。 */
    List<NfzLayer> findAllByOrderByIdAsc();

    /** 某级别启用中的图层。 */
    List<NfzLayer> findByLevelAndEnabledTrue(NfzLevel level);
}
