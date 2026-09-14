package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.clearing.ClearingInstruction;
import com.claw.server.domain.clearing.ClearingInstructionRepository;
import com.claw.server.domain.clearing.SettlementBatch;
import com.claw.server.domain.clearing.SettlementBatchRepository;
import com.claw.server.domain.clearing.SettlementBatchService;
import com.claw.server.domain.clearing.SettlementRule;
import com.claw.server.domain.clearing.SettlementRuleRepository;
import com.claw.server.domain.clearing.SuspenseEntry;
import com.claw.server.domain.clearing.SuspenseEntryRepository;
import com.claw.server.domain.clearing.SuspenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 后台清分管理（资金路由与清分域 · T10）。
 *
 * <p>覆盖设计 §4 / §6 / §8 的后台入口：分账规则、清分指令、结算批次、差错挂账四个维度。
 * 所有写接口与导出/下载接口均标注 {@link RequirePermission}（权限位挂在 {@code menu:finance} 下，
 * 由 V136 权限种子写入），未授权访问由 {@code web.support.PermissionAspect} 在入口拦截。
 *
 * <p>约定：操作人 {@code operatorId} 由请求体带入（审计留痕），控制器不依赖登录态，
 * 权限判定与登录态校验统一交给 {@code PermissionAspect} + {@code AuthContext}。
 */
@RestController
@RequestMapping("/api/v1/admin/clearing")
@RequiredArgsConstructor
public class AdminClearingController {

    private final SettlementRuleRepository settlementRuleRepository;
    private final ClearingInstructionRepository clearingInstructionRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementBatchService settlementBatchService;
    private final SuspenseEntryRepository suspenseEntryRepository;
    private final SuspenseService suspenseService;

    // ===================== 分账规则 =====================

    /** 分账规则列表（全部，按 id 升序）。 */
    @GetMapping("/rules")
    @RequirePermission("finance:clearing:view")
    public ApiResult<List<SettlementRule>> listRules() {
        return ApiResult.ok(settlementRuleRepository.findAll());
    }

    /** 分账规则详情（按 id）。 */
    @GetMapping("/rules/{id}")
    @RequirePermission("finance:clearing:view")
    public ApiResult<SettlementRule> getRule(@PathVariable Long id) {
        return ApiResult.ok(settlementRuleRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.clearing.rule.not.found", id)));
    }

    // ===================== 清分指令 =====================

    /**
     * 清分指令列表（支持 {@code scene} / {@code basisRef} 过滤，均选填）。
     *
     * <p>两者都给时优先按 {@code basisRef} 精确查；只给 {@code scene} 按场景查；
     * 都不给返回全部（按创建时间升序）。
     */
    @GetMapping("/instructions")
    @RequirePermission("finance:clearing:view")
    public ApiResult<List<ClearingInstruction>> listInstructions(
            @RequestParam(required = false) ClearingScene scene,
            @RequestParam(required = false) String basisRef) {
        List<ClearingInstruction> result;
        if (basisRef != null && !basisRef.isBlank()) {
            result = clearingInstructionRepository.findByBasisRef(basisRef);
        } else if (scene != null) {
            result = clearingInstructionRepository.findBySceneOrderByCreatedAtAsc(scene);
        } else {
            result = clearingInstructionRepository.findAll();
        }
        return ApiResult.ok(result);
    }

    // ===================== 结算批次 =====================

    /** 结算批次列表（全部，按 id 升序）。 */
    @GetMapping("/batches")
    @RequirePermission("finance:clearing:view")
    public ApiResult<List<SettlementBatch>> listBatches() {
        return ApiResult.ok(settlementBatchRepository.findAll());
    }

    /**
     * 周期汇总：收集该周期内 {@code status=CREATED} 的指令生成结算批次（设计 §6.2）。
     *
     * @param scene       清分场景（R1..R12）
     * @param periodStart 周期起（ISO-8601，含）
     * @param periodEnd   周期止（ISO-8601，不含）
     */
    @PostMapping("/batches/collect")
    @RequirePermission("finance:clearing:manage")
    public ApiResult<SettlementBatch> collectBatch(@RequestBody CollectBatchReq req) {
        ClearingScene scene = parseScene(req.scene());
        Instant periodStart = Instant.parse(req.periodStart());
        Instant periodEnd = Instant.parse(req.periodEnd());
        return ApiResult.ok(settlementBatchService.collect(scene, periodStart, periodEnd));
    }

    /** 批次审核：COLLECTING → REVIEWING → APPROVED（设计 §6.2）。 */
    @PostMapping("/batches/{id}/approve")
    @RequirePermission("finance:clearing:manage")
    public ApiResult<SettlementBatch> approveBatch(@PathVariable Long id, @RequestBody ApproveBatchReq req) {
        return ApiResult.ok(settlementBatchService.approve(id, req.operatorId()));
    }

    /** 批次下发：APPROVED / PARTIAL → SENDING，逐条调通道 SPI 代付（设计 §6.2）。 */
    @PostMapping("/batches/{id}/submit")
    @RequirePermission("finance:clearing:manage")
    public ApiResult<SettlementBatch> submitBatch(@PathVariable Long id) {
        return ApiResult.ok(settlementBatchService.submit(id));
    }

    // ===================== 差错挂账 =====================

    /** 差错工单列表（全部，按 id 升序）。 */
    @GetMapping("/suspense")
    @RequirePermission("finance:clearing:view")
    public ApiResult<List<SuspenseEntry>> listSuspense() {
        return ApiResult.ok(suspenseEntryRepository.findAll());
    }

    /** 处置差错工单：OPEN / PROCESSING → RESOLVED 或 WRITTEN_OFF（设计 §8）。 */
    @PostMapping("/suspense/{id}/resolve")
    @RequirePermission("finance:clearing:manage")
    public ApiResult<SuspenseEntry> resolveSuspense(@PathVariable Long id, @RequestBody ResolveSuspenseReq req) {
        return ApiResult.ok(suspenseService.resolve(id, req.operatorId(), req.resolution()));
    }

    // ===================== 内部 =====================

    private static ClearingScene parseScene(String raw) {
        try {
            return ClearingScene.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw BizException.invalidParam("error.clearing.request.invalid");
        }
    }

    /** 批次汇总入参。 */
    public record CollectBatchReq(String scene, String periodStart, String periodEnd) {
    }

    /** 批次审核入参（操作人审计）。 */
    public record ApproveBatchReq(Long operatorId) {
    }

    /** 差错处置入参。 */
    public record ResolveSuspenseReq(Long operatorId, String resolution) {
    }
}
