package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.AdminDtos.*;
import com.claw.server.domain.swap.ElecPriceSnapshot;
import com.claw.server.domain.swap.ElecPriceSnapshotRepository;
import com.claw.server.domain.swap.FeeRule;
import com.claw.server.domain.swap.FeeRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 后台费率模块（S5 补齐）：锁版费率规则维护 + 电价快照只读。
 *
 * <p>GET    /fee/rules           费率规则列表
 * POST   /fee/rules           新增费率规则（ruleCode 唯一）
 * PUT    /fee/rules/{id}      修改费率规则
 * DELETE /fee/rules/{id}      删除费率规则
 * GET    /fee/elec-prices     电价快照（只读）
 */
@RestController
@RequestMapping("/api/v1/admin/fee")
@RequiredArgsConstructor
public class AdminFeeController {

    private final FeeRuleRepository feeRuleRepository;
    private final ElecPriceSnapshotRepository elecPriceRepository;

    @GetMapping("/rules")
    public ApiResult<List<FeeRuleView>> listRules() {
        return ApiResult.ok(feeRuleRepository.findAll().stream().map(this::toView).toList());
    }

    @PostMapping("/rules")
    public ApiResult<FeeRuleView> createRule(@RequestBody FeeRuleReq req) {
        if (req.ruleCode() == null || req.ruleCode().isBlank()) {
            throw new BizException(40001, "fee.rule.code.required");
        }
        FeeRule r = new FeeRule();
        r.setRuleCode(req.ruleCode());
        r.setName(req.name());
        r.setUnit(req.unit());
        r.setPrice(req.price());
        r.setShareJson(req.shareJson());
        r.setEffectiveFrom(req.effectiveFrom());
        r.setEffectiveTo(req.effectiveTo());
        r.setStatus(req.status() == null ? "ACTIVE" : req.status());
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return ApiResult.ok(toView(feeRuleRepository.save(r)));
    }

    @PutMapping("/rules/{id}")
    public ApiResult<FeeRuleView> updateRule(@PathVariable Long id, @RequestBody FeeRuleReq req) {
        FeeRule r = feeRuleRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "fee.rule.not.found"));
        if (req.name() != null) r.setName(req.name());
        if (req.unit() != null) r.setUnit(req.unit());
        if (req.price() != null) r.setPrice(req.price());
        if (req.shareJson() != null) r.setShareJson(req.shareJson());
        if (req.effectiveFrom() != null) r.setEffectiveFrom(req.effectiveFrom());
        if (req.effectiveTo() != null) r.setEffectiveTo(req.effectiveTo());
        if (req.status() != null) r.setStatus(req.status());
        r.setUpdatedAt(Instant.now());
        return ApiResult.ok(toView(feeRuleRepository.save(r)));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResult<Void> deleteRule(@PathVariable Long id) {
        if (!feeRuleRepository.existsById(id)) {
            throw new BizException(40401, "fee.rule.not.found");
        }
        feeRuleRepository.deleteById(id);
        return ApiResult.ok();
    }

    @GetMapping("/elec-prices")
    public ApiResult<List<ElecPriceSnapshotView>> listElecPrices() {
        return ApiResult.ok(elecPriceRepository.findAll().stream().map(e -> new ElecPriceSnapshotView(
                e.getId(), e.getPvPrice(), e.getGridPrice(), e.getEffectiveDate())).toList());
    }

    private FeeRuleView toView(FeeRule r) {
        return new FeeRuleView(r.getId(), r.getRuleCode(), r.getName(), r.getUnit(), r.getPrice(),
                r.getShareJson(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getStatus());
    }
}
