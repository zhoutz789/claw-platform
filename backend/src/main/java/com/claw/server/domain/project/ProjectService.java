package com.claw.server.domain.project;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.dto.ProjectDtos.*;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.domain.asset.AssetService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.sharedpool.SharedPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 项目管理域服务（Increment 3 C 期核心）。
 *
 * <p>职责：
 * <ul>
 *   <li>项目 CRUD + 自引用树（parent_id / depth / sort_no）；</li>
 *   <li>项目-设备绑定 / 解绑（自动带出 product_id，强制 UNIQUE(project_id, asset_id)）；</li>
 *   <li>三态授权（TRANSFER / SHARE / AUTHORIZE 正交，分别路由到不同既有域）；</li>
 *   <li>每项目独立核算（绑定一个 PROJECT 账户，委托 LedgerService 双记账）。</li>
 * </ul>
 *
 * <p>跨域边界（ArchUnit）：本服务<strong>只</strong>依赖本包仓储，以及 SharedPoolService /
 * LedgerService / AssetService 等<strong>服务接口</strong>，绝不直持 ledger / sharedpool /
 * asset 的 Repository（沿 OrderSharedPoolIntegration 模式）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectDeviceRepository projectDeviceRepository;
    private final DeviceAuthorizationRepository deviceAuthorizationRepository;

    private final SharedPoolService sharedPoolService;
    private final LedgerService ledgerService;
    private final AssetService assetService;

    // ---- 项目 CRUD + 树 ----

    /**
     * 创建项目：同时为每个项目建一个 account_type=PROJECT 的账户（走 LedgerService），
     * 并将账户 id 写入 projects.account_id。
     */
    @Transactional
    public ProjectView create(CreateProjectReq req, Long ownerUserId) {
        if (req.name() == null || req.name().isBlank()) {
            throw BizException.invalidParam("error.project.name.required");
        }
        int depth = 0;
        if (req.parentId() != null) {
            Project parent = projectRepository.findById(req.parentId())
                    .orElseThrow(() -> BizException.notFound("error.project.not.found"));
            depth = parent.getDepth() + 1;
        }
        var account = ledgerService.createAccount(AccountType.PROJECT, ownerUserId, null);
        Project project = Project.builder()
                .ownerUserId(ownerUserId)
                .name(req.name())
                .parentId(req.parentId())
                .depth(depth)
                .sortNo(req.sortNo() != null ? req.sortNo() : 0)
                .accountId(account.getId())
                .status("ACTIVE")
                .build();
        project = projectRepository.save(project);
        log.info("创建项目 id={} name={} owner={} account={} depth={}", project.getId(),
                project.getName(), ownerUserId, account.getId(), depth);
        return toView(project);
    }

    /** 更新项目（仅所有者）：名称 / 父项目（联动 depth 重算）/ 排序 / 状态。 */
    @Transactional
    public ProjectView update(Long id, UpdateProjectReq req, Long ownerUserId) {
        Project p = load(id, ownerUserId);
        if (req.name() != null && !req.name().isBlank()) {
            p.setName(req.name());
        }
        if (req.parentId() != null && !req.parentId().equals(p.getParentId())) {
            if (req.parentId().equals(id)) {
                throw BizException.invalidParam("error.project.parent.self");
            }
            Project parent = projectRepository.findById(req.parentId())
                    .orElseThrow(() -> BizException.notFound("error.project.not.found"));
            p.setParentId(req.parentId());
            int newDepth = parent.getDepth() + 1;
            p.setDepth(newDepth);
            recomputeDepth(id, newDepth); // 级联重算子树 depth
        }
        if (req.sortNo() != null) {
            p.setSortNo(req.sortNo());
        }
        if (req.status() != null && !req.status().isBlank()) {
            p.setStatus(req.status());
        }
        p.setUpdatedAt(Instant.now());
        p = projectRepository.save(p);
        return toView(p);
    }

    /** 删除项目：先把直接子项目上提一级（避免悬挂 parent_id），再解绑设备，最后删项目。 */
    @Transactional
    public void delete(Long id, Long ownerUserId) {
        Project p = load(id, ownerUserId);
        Long grandParent = p.getParentId();
        int grandDepth = grandParent == null ? 0
                : projectRepository.findById(grandParent).map(Project::getDepth).orElse(0) + 1;
        for (Project child : projectRepository.findByParentId(id)) {
            child.setParentId(grandParent);
            child.setDepth(grandDepth);
            child.setUpdatedAt(Instant.now());
            projectRepository.save(child);
        }
        projectDeviceRepository.deleteByProjectId(id);
        projectRepository.delete(p);
        log.info("删除项目 id={} owner={}", id, ownerUserId);
    }

    /** 列出某用户全部项目并组装父子树（根节点 parent_id 为空，子节点按 sort_no 嵌套）。 */
    @Transactional(readOnly = true)
    public List<ProjectTreeNode> listByOwner(Long ownerUserId) {
        List<Project> all = projectRepository.findByOwnerUserIdOrderBySortNoAsc(ownerUserId);
        Map<Long, List<Project>> childrenMap = all.stream()
                .filter(p -> p.getParentId() != null)
                .collect(Collectors.groupingBy(Project::getParentId));
        return all.stream()
                .filter(p -> p.getParentId() == null)
                .sorted(Comparator.comparingInt(Project::getSortNo))
                .map(p -> toNode(p, childrenMap))
                .toList();
    }

    // ---- 设备绑定 / 解绑 ----

    /** 绑定设备到项目：自动带出 product_id，强制 UNIQUE(project_id, asset_id)。 */
    @Transactional
    public ProjectDeviceView bindDevice(Long projectId, Long assetId, Long ownerUserId) {
        load(projectId, ownerUserId);
        if (projectDeviceRepository.existsByProjectIdAndAssetId(projectId, assetId)) {
            throw BizException.of(40970, "error.project.device.duplicate");
        }
        ApiViews.AssetView asset = assetService.getAsset(assetId);
        ProjectDevice pd = ProjectDevice.builder()
                .projectId(projectId)
                .assetId(assetId)
                .productId(asset.productId())
                .category(asset.assetType() != null ? asset.assetType().name() : null)
                .sortNo(0)
                .build();
        pd = projectDeviceRepository.save(pd);
        log.info("绑定设备 projectId={} assetId={} productId={}", projectId, assetId, asset.productId());
        return toDeviceView(pd, asset);
    }

    /** 解绑设备：按 project_device id 删除（校验项目归属）。 */
    @Transactional
    public void unbindDevice(Long pdId, Long ownerUserId) {
        ProjectDevice pd = projectDeviceRepository.findById(pdId)
                .orElseThrow(() -> BizException.notFound("error.project.device.not.found"));
        load(pd.getProjectId(), ownerUserId);
        projectDeviceRepository.deleteById(pdId);
        log.info("解绑设备 pdId={} projectId={}", pdId, pd.getProjectId());
    }

    /** 列出项目下设备 + 资产摘要。 */
    @Transactional(readOnly = true)
    public List<ProjectDeviceView> getDevices(Long projectId, Long ownerUserId) {
        load(projectId, ownerUserId);
        return projectDeviceRepository.findByProjectIdOrderBySortNoAsc(projectId).stream()
                .map(pd -> toDeviceView(pd, assetService.getAsset(pd.getAssetId())))
                .toList();
    }

    // ---- 三态授权 ----

    /**
     * 三态授权（正交、分别落不同表）：
     * <ul>
     *   <li>TRANSFER —— 所有权→资产大厅：资产置公开可见（AssetStatus.LISTED）+ 记录授权；</li>
     *   <li>SHARE —— 入池：委托 SharedPoolService.poolAsset（stationId / 费率取请求，缺失用平台默认）；</li>
     *   <li>AUTHORIZE —— 仅授予使用权：仅落 device_authorizations 记录，不转移所有权。</li>
     * </ul>
     */
    @Transactional
    public DeviceAuthorizationView authorize(Long pdId, DeviceAuthorizeReq req, Long ownerUserId) {
        ProjectDevice pd = projectDeviceRepository.findById(pdId)
                .orElseThrow(() -> BizException.notFound("error.project.device.not.found"));
        load(pd.getProjectId(), ownerUserId);
        DeviceAuthType type;
        try {
            type = DeviceAuthType.valueOf(req.authType().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw BizException.invalidParam("error.project.auth.type");
        }
        Long assetId = pd.getAssetId();
        DeviceAuthorization auth = switch (type) {
            case TRANSFER -> {
                // 转让 → 资产大厅（大厅挂牌语义）：所有权仍归原主，仅置公开可见。
                // 点对点真实过户（指定 granteeUserId）本期不支持，强制大厅挂牌（granteeUserId=null），
                // 否则抛明确业务错误且不落授权记录（避免授权记录与真实所有权不一致）。
                if (req.granteeUserId() != null) {
                    throw BizException.invalidParam("error.project.transfer.only.hall");
                }
                assetService.listInHall(assetId, ownerUserId);
                yield DeviceAuthorization.builder()
                        .assetId(assetId).grantorUserId(ownerUserId)
                        .granteeUserId(null)
                        .authType(DeviceAuthType.TRANSFER.name())
                        .scopeJson(req.scope()).status("ACTIVE").build();
            }
            case SHARE -> {
                // 共享 → 入池：stationId 必填，缺失则抛明确业务错误并拒绝静默落库
                // （currentStationId=null 的池记录属数据质量缺陷）。owner/station 分成与费率
                // 缺失时 poolAsset 用平台默认 70/15。
                if (req.stationId() == null) {
                    throw BizException.invalidParam("error.project.share.station.required");
                }
                sharedPoolService.poolAsset(assetId, ownerUserId, req.stationId(),
                        req.ownerSplitRate(), req.stationSplitRate(),
                        req.dailyUsageFee(), req.perSwapFee());
                yield DeviceAuthorization.builder()
                        .assetId(assetId).grantorUserId(ownerUserId)
                        .granteeUserId(req.granteeUserId())
                        .authType(DeviceAuthType.SHARE.name())
                        .scopeJson(req.scope()).status("ACTIVE").build();
            }
            case AUTHORIZE -> DeviceAuthorization.builder()
                    .assetId(assetId).grantorUserId(ownerUserId)
                    .granteeUserId(req.granteeUserId())
                    .authType(DeviceAuthType.AUTHORIZE.name())
                    .scopeJson(req.scope()).status("ACTIVE").build();
        };
        auth = deviceAuthorizationRepository.save(auth);
        log.info("设备授权 pdId={} assetId={} type={} grantee={}", pdId, assetId, type, req.granteeUserId());
        return toAuthView(auth);
    }

    // ---- 每项目核算 ----

    /**
     * 记录项目收支：委托 LedgerService 双记账。
     * 项目绑定的 PROJECT 账户为一侧，平台 MASTER 账户为对冲侧（借贷平衡）。
     * type ∈ {INCOME|CREDIT 收入, EXPENSE|DEBIT 支出}。
     */
    @Transactional
    public LedgerViews.TxnResult recordProjectEntry(Long projectId, BigDecimal amount,
                                                    String type, String memo, Long ownerUserId) {
        Project p = load(projectId, ownerUserId);
        Long projectAccount = p.getAccountId();
        if (projectAccount == null) {
            throw BizException.of(40971, "error.project.no.account");
        }
        Long platformAccount = ledgerService.getPlatformAccountId();
        boolean income = "INCOME".equalsIgnoreCase(type) || "CREDIT".equalsIgnoreCase(type);
        LedgerRequests.Direction dir = income ? LedgerRequests.Direction.C : LedgerRequests.Direction.D;
        LedgerRequests.Direction opposite = income ? LedgerRequests.Direction.D : LedgerRequests.Direction.C;
        String bizRef = "PROJ-" + projectId + "-" + UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase();
        List<LedgerRequests.Entry> entries = List.of(
                new LedgerRequests.Entry(projectAccount, dir, amount, memo),
                new LedgerRequests.Entry(platformAccount, opposite, amount, memo));
        return ledgerService.postEntries(BizType.PROJECT_LEDGER, bizRef, entries);
    }

    /** 查询项目核算账户余额概览。 */
    @Transactional(readOnly = true)
    public ProjectAccountView getAccount(Long projectId, Long ownerUserId) {
        Project p = load(projectId, ownerUserId);
        if (p.getAccountId() == null) {
            throw BizException.of(40971, "error.project.no.account");
        }
        BigDecimal balance = ledgerService.getBalance(p.getAccountId());
        return new ProjectAccountView(p.getId(), p.getAccountId(), balance, "USD", Instant.now());
    }

    // ---- 内部工具 ----

    private Project load(Long id, Long ownerUserId) {
        Project p = projectRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.project.not.found"));
        if (ownerUserId != null && !p.getOwnerUserId().equals(ownerUserId)) {
            throw BizException.of(40301, "error.project.forbidden");
        }
        return p;
    }

    /** 级联重算子树 depth（父节点层级变化后保持一致性）。 */
    private void recomputeDepth(Long parentId, int parentDepth) {
        for (Project child : projectRepository.findByParentId(parentId)) {
            child.setDepth(parentDepth + 1);
            child.setUpdatedAt(Instant.now());
            projectRepository.save(child);
            recomputeDepth(child.getId(), child.getDepth());
        }
    }

    private ProjectTreeNode toNode(Project p, Map<Long, List<Project>> childrenMap) {
        List<ProjectTreeNode> children = childrenMap.getOrDefault(p.getId(), List.of()).stream()
                .sorted(Comparator.comparingInt(Project::getSortNo))
                .map(c -> toNode(c, childrenMap))
                .toList();
        return toTreeNode(p, children);
    }

    private ProjectView toView(Project p) {
        return new ProjectView(p.getId(), p.getOwnerUserId(), p.getName(), p.getParentId(),
                p.getDepth(), p.getSortNo(), p.getAccountId(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    private ProjectTreeNode toTreeNode(Project p, List<ProjectTreeNode> children) {
        return new ProjectTreeNode(p.getId(), p.getOwnerUserId(), p.getName(), p.getParentId(),
                p.getDepth(), p.getSortNo(), p.getAccountId(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt(), children);
    }

    private ProjectDeviceView toDeviceView(ProjectDevice pd, ApiViews.AssetView asset) {
        return new ProjectDeviceView(pd.getId(), pd.getProjectId(), pd.getAssetId(),
                pd.getProductId(), pd.getCategory(), pd.getSortNo(),
                asset != null ? asset.assetNo() : null,
                asset != null && asset.assetType() != null ? asset.assetType().name() : null,
                asset != null && asset.status() != null ? asset.status().name() : null);
    }

    private DeviceAuthorizationView toAuthView(DeviceAuthorization a) {
        return new DeviceAuthorizationView(a.getId(), a.getAssetId(), a.getGrantorUserId(),
                a.getGranteeUserId(), a.getAuthType(), a.getScopeJson(), a.getStatus(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
