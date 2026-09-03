package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 服务站项目层服务（模块四 · ②）。
 *
 * <p><b>解耦铁律（BC-1/BC-3）</b>：本服务的写事务只触碰
 * {@link StationProjectRepository} 与 {@link StationProjectInventoryAllocRepository}。
 * 分配（alloc）只写 alloc 表，<b>绝不</b>回写 {@code station_stock.stock_qty}；
 * 可用量 = stock_qty − Σallocated，由本层读时计算（仅读 stock，不写）。
 * 项目树与既有 {@code projects}（资产项目域）完全解耦（D2）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StationProjectService {

    private final StationProjectRepository projectRepository;
    private final StationProjectInventoryAllocRepository allocRepository;
    private final StationStockRepository stockRepository;
    private final StationScopeService scopeService;

    private StationProject load(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("station.project.not.found"));
    }

    /** 新建项目（可挂 parentId）。 */
    @Transactional
    public StationViews.StationProjectView create(StationRequests.StationProjectCreate req, Long ownerUserId) {
        assertStationAllowed(req.stationId());
        if (req.name() == null || req.name().isBlank()) {
            throw BizException.invalidParam("station.project.name.required");
        }
        int depth = 0;
        if (req.parentId() != null) {
            StationProject parent = load(req.parentId());
            depth = parent.getDepth() + 1;
        }
        StationProject p = StationProject.builder()
                .stationId(req.stationId())
                .ownerUserId(ownerUserId)
                .name(req.name())
                .parentId(req.parentId())
                .depth(depth)
                .sortNo(req.sortNo() != null ? req.sortNo() : 0)
                .status("ACTIVE")
                .build();
        p = projectRepository.save(p);
        log.info("新建服务站项目 id={} stationId={} name={} owner={}", p.getId(), req.stationId(), req.name(), ownerUserId);
        return toView(p);
    }

    /** 更新项目（改名/改父/状态）。 */
    @Transactional
    public StationViews.StationProjectView update(Long id, StationRequests.StationProjectUpdate req) {
        StationProject p = load(id);
        assertStationAllowed(p.getStationId());

        if (req.name() != null && !req.name().isBlank()) {
            p.setName(req.name());
        }
        if (req.parentId() != null && !req.parentId().equals(p.getParentId())) {
            if (req.parentId().equals(id)) {
                throw BizException.invalidParam("station.project.parent.self");
            }
            StationProject parent = load(req.parentId());
            p.setParentId(req.parentId());
            int newDepth = parent.getDepth() + 1;
            p.setDepth(newDepth);
            recomputeDepth(id, newDepth);
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

    /** 删除项目：子项目上提一级，解绑 alloc，再删项目。 */
    @Transactional
    public void delete(Long id) {
        StationProject p = load(id);
        assertStationAllowed(p.getStationId());
        Long grandParent = p.getParentId();
        int grandDepth = grandParent == null ? 0
                : projectRepository.findById(grandParent).map(StationProject::getDepth).orElse(0) + 1;
        for (StationProject child : projectRepository.findByParentId(id)) {
            child.setParentId(grandParent);
            child.setDepth(grandDepth);
            child.setUpdatedAt(Instant.now());
            projectRepository.save(child);
        }
        allocRepository.deleteByStationProjectId(id);
        projectRepository.delete(p);
        log.info("删除服务站项目 id={}", id);
    }

    /** 站下项目树（按 allowedStationIds 过滤）。 */
    @Transactional(readOnly = true)
    public List<StationViews.StationProjectTreeNode> listTree(List<Long> allowedStationIds) {
        if (allowedStationIds != null && allowedStationIds.isEmpty()) {
            return List.of();
        }
        List<StationProject> all = allowedStationIds == null
                ? projectRepository.findAll()
                : projectRepository.findByStationIdIn(allowedStationIds);
        Map<Long, List<StationProject>> childrenMap = all.stream()
                .filter(p -> p.getParentId() != null)
                .collect(Collectors.groupingBy(StationProject::getParentId));
        return all.stream()
                .filter(p -> p.getParentId() == null)
                .sorted(Comparator.comparingInt(StationProject::getSortNo))
                .map(p -> toNode(p, childrenMap))
                .toList();
    }

    /** 项目占用列表。 */
    @Transactional(readOnly = true)
    public List<StationViews.StationProjectAllocView> listAllocs(Long projectId) {
        StationProject p = load(projectId); // 存在性校验
        assertStationAllowed(p.getStationId()); // BC-5
        return allocRepository.findByStationProjectId(projectId).stream()
                .map(a -> toAllocView(a))
                .sorted(Comparator.comparing(StationViews.StationProjectAllocView::createdAt))
                .toList();
    }

    /**
     * 占用库存（BC-3：只写 alloc 表，绝不写 station_stock）。
     * 同一 (project, stock) 唯一，重复占用累加 allocatedQty。
     */
    @Transactional
    public StationViews.StationProjectAllocView alloc(Long projectId, StationRequests.StationProjectAlloc req) {
        StationProject p = load(projectId);
        assertStationAllowed(p.getStationId());
        StationStock stock = stockRepository.findById(req.stationStockId())
                .orElseThrow(() -> BizException.notFound("station.stock.not.found"));
        if (!stock.getStationId().equals(p.getStationId())) {
            throw BizException.invalidParam("station.project.alloc.station.mismatch");
        }
        StationProjectInventoryAlloc alloc = allocRepository
                .findByStationProjectIdAndStationStockId(projectId, req.stationStockId())
                .orElseGet(() -> StationProjectInventoryAlloc.builder()
                        .stationProjectId(projectId)
                        .stationStockId(req.stationStockId())
                        .skuCode(stock.getSkuCode())
                        .allocatedQty(0)
                        .build());
        alloc.setAllocatedQty(alloc.getAllocatedQty() + req.qty());
        if (req.note() != null) {
            alloc.setNote(req.note());
        }
        alloc.setUpdatedAt(Instant.now());
        StationProjectInventoryAlloc saved = allocRepository.save(alloc);
        log.info("占用库存 projectId={} stockId={} qty=+{}", projectId, req.stationStockId(), req.qty());
        return toAllocView(saved);
    }

    /** 解除占用（只删 alloc 表）。 */
    @Transactional
    public void dealloc(Long allocId) {
        StationProjectInventoryAlloc alloc = allocRepository.findById(allocId)
                .orElseThrow(() -> BizException.notFound("station.project.alloc.not.found"));
        StationProject p = load(alloc.getStationProjectId());
        assertStationAllowed(p.getStationId());
        allocRepository.deleteById(allocId);
        log.info("解除占用 allocId={} projectId={}", allocId, alloc.getStationProjectId());
    }

    /** 读时计算某库存行的可用量 = stock_qty − Σallocated（同 station_stock_id）。 */
    @Transactional(readOnly = true)
    public int availableQty(Long stationStockId) {
        StationStock stock = stockRepository.findById(stationStockId).orElse(null);
        int stockQty = stock == null ? 0 : stock.getStockQty();
        int allocated = allocRepository.findByStationStockId(stationStockId).stream()
                .mapToInt(StationProjectInventoryAlloc::getAllocatedQty).sum();
        return stockQty - allocated;
    }

    private void recomputeDepth(Long parentId, int parentDepth) {
        for (StationProject child : projectRepository.findByParentId(parentId)) {
            child.setDepth(parentDepth + 1);
            child.setUpdatedAt(Instant.now());
            projectRepository.save(child);
            recomputeDepth(child.getId(), child.getDepth());
        }
    }

    private StationViews.StationProjectView toView(StationProject p) {
        return new StationViews.StationProjectView(
                p.getId(), p.getStationId(), p.getOwnerUserId(), p.getName(), p.getParentId(),
                p.getDepth(), p.getSortNo(), p.getStatus(), p.getCreatedAt(), p.getUpdatedAt());
    }

    private StationViews.StationProjectTreeNode toNode(StationProject p, Map<Long, List<StationProject>> childrenMap) {
        List<StationViews.StationProjectTreeNode> children = childrenMap.getOrDefault(p.getId(), List.of()).stream()
                .sorted(Comparator.comparingInt(StationProject::getSortNo))
                .map(c -> toNode(c, childrenMap))
                .toList();
        return new StationViews.StationProjectTreeNode(
                p.getId(), p.getStationId(), p.getOwnerUserId(), p.getName(), p.getParentId(),
                p.getDepth(), p.getSortNo(), p.getStatus(), p.getCreatedAt(), p.getUpdatedAt(), children);
    }

    private StationViews.StationProjectAllocView toAllocView(StationProjectInventoryAlloc a) {
        return new StationViews.StationProjectAllocView(
                a.getId(), a.getStationProjectId(), a.getStationStockId(), a.getSkuCode(),
                a.getAllocatedQty(), a.getNote(), availableQty(a.getStationStockId()),
                a.getCreatedAt(), a.getUpdatedAt());
    }

    /** 写操作越权校验（BC-5）。 */
    private void assertStationAllowed(Long stationId) {
        List<Long> allowed = scopeService.allowedStationIds(stationId);
        if (allowed != null && !allowed.contains(stationId)) {
            throw BizException.of(40301, "station.scope.forbidden");
        }
    }
}
