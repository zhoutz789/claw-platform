package com.claw.server.domain.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 无人机机型产品类服务（切片 1 薄层）：配置驱动多机型的只读查询。
 * 新增机型 = 插 {@code drone_product_classes} + {@code drone_scenario_attrs} 行，零代码。
 */
@Service
@RequiredArgsConstructor
public class DroneProductClassService {

    private final DroneProductClassRepository classRepository;

    /**
     * 机型列表。
     *
     * @param scenario 场景过滤（可选，取 {@code DroneScenario} 名或 GENERAL）；为空返回全部
     * @return 机型产品类列表
     */
    @Transactional(readOnly = true)
    public List<DroneProductClass> list(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return classRepository.findAll();
        }
        return classRepository.findByScenario(scenario);
    }
}
