package com.claw.server.infra;

import com.claw.server.common.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** S0 冒烟接口：验证服务/DB/i18n 链路通畅，S1 后可移除。 */
@RestController
@RequestMapping("/api/v1")
public class PingController {

    @GetMapping("/ping")
    public ApiResult<Map<String, String>> ping() {
        return ApiResult.ok(Map.of(
                "service", "claw-server",
                "stage", "S0-scaffold",
                "version", "0.1.0-SNAPSHOT"));
    }
}
