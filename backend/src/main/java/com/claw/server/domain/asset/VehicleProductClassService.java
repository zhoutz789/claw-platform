package com.claw.server.domain.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 车辆产品类服务（T1 薄层）：配置驱动多车型的核心读写。
 * 新增车型 = createClass + addAttr，零代码；能力/设备/证件以 CSV 表达，
 * 直接对接既有资产类型注册表与 RBAC 品类管理方向。
 */
@Service
@RequiredArgsConstructor
public class VehicleProductClassService {

    private final VehicleProductClassRepository classRepository;
    private final VehicleScenarioAttrRepository attrRepository;

    @Transactional
    public VehicleProductClass createClass(VehicleProductClass productClass) {
        if (classRepository.findByCode(productClass.getCode()).isPresent()) {
            throw new IllegalArgumentException("vehicle.product.class.code.exists:" + productClass.getCode());
        }
        Instant now = Instant.now();
        productClass.setCreatedAt(now);
        productClass.setUpdatedAt(now);
        return classRepository.save(productClass);
    }

    public List<VehicleProductClass> listAll() {
        return classRepository.findAll();
    }

    public VehicleProductClass getByCode(String code) {
        return classRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("vehicle.product.class.not.found:" + code));
    }

    @Transactional
    public VehicleScenarioAttr addAttr(Long productClassId, VehicleScenarioAttr attr) {
        if (!classRepository.existsById(productClassId)) {
            throw new IllegalArgumentException("vehicle.product.class.not.found:" + productClassId);
        }
        attr.setProductClassId(productClassId);
        return attrRepository.save(attr);
    }

    public List<VehicleScenarioAttr> listAttrs(Long productClassId) {
        return attrRepository.findByProductClassId(productClassId);
    }
}
