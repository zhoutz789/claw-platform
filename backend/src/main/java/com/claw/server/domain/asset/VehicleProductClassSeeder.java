package com.claw.server.domain.asset;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 车型配置种子启动器（T4）：应用启动时若 {@code vehicle_product_classes} 为空则灌入 8 类默认车型，
 * 使产品类配置模型开箱即用（新增车型只需追加配置，零代码）。
 * 幂等：表非空即跳过，可安全重复启动。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VehicleProductClassSeeder {

    private final VehicleProductClassService productClassService;

    @PostConstruct
    public void seedOnStart() {
        int n = productClassService.seedDefaultProfiles();
        if (n > 0) {
            log.info("[SEED] 车型产品类已灌入 {} 类默认配置", n);
        }
    }
}
