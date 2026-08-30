package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.commission.CommissionRule;
import com.claw.server.domain.commission.CommissionRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 设备销售提成规则管理（增量 B · R7，与光伏分成解耦）。 */
@RestController
@RequestMapping("/api/v1/admin/commission")
@RequiredArgsConstructor
public class AdminCommissionController {

    private final CommissionRuleService ruleService;

    @GetMapping("/rules")
    public ApiResult<List<CommissionRule>> list(@RequestParam(required = false) Long manufacturerId) {
        return ApiResult.ok(ruleService.listRules(manufacturerId));
    }

    @GetMapping("/rules/{id}")
    public ApiResult<CommissionRule> get(@PathVariable Long id) {
        return ApiResult.ok(ruleService.getRule(id));
    }

    @PostMapping("/rules")
    @RequirePermission("config:commission:manage")
    public ApiResult<CommissionRule> create(@RequestBody CommissionRule rule) {
        return ApiResult.ok(ruleService.createRule(rule));
    }

    @PutMapping("/rules/{id}")
    @RequirePermission("config:commission:manage")
    public ApiResult<CommissionRule> update(@PathVariable Long id, @RequestBody CommissionRule rule) {
        return ApiResult.ok(ruleService.updateRule(id, rule));
    }

    @DeleteMapping("/rules/{id}")
    @RequirePermission("config:commission:manage")
    public ApiResult<Void> delete(@PathVariable Long id) {
        ruleService.deleteRule(id);
        return ApiResult.ok();
    }
}
