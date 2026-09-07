package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.category.Category;
import com.claw.server.domain.category.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 类别管理（② 通用多级商品分类树）：列出 / 建树 / 创建 / 更名改父 / 软删除。
 * 仅登录即可访问（与 admin 其它接口同口径）；写操作受 category:* 权限位控制。
 *
 * <p>GET    /categories                扁平列表（按 tenantId，未删除）
 * GET    /categories/tree           嵌套树（按 parent_id 在内存组装）
 * POST   /categories                新建分类（name 必填，parentId 可空；自动算 level/code）
 * PUT    /categories/{id}           更名 / 改父分类
 * DELETE /categories/{id}           软删除（含子分类时拒绝，需先删子节点）
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryRepository categoryRepository;

    @GetMapping("/categories")
    public ApiResult<List<Category>> listCategories() {
        return ApiResult.ok(categoryRepository.findByTenantIdAndDeletedFalseOrderBySortNoAsc(1L));
    }

    /**
     * 分类嵌套树：拉取全部未删除分类后按 parent_id 在内存组装为多层结构。
     * 树根（parent_id = NULL）挂在哨兵键 -1L 下；level / code 一并下发供前端展示。
     */
    @GetMapping("/categories/tree")
    public ApiResult<List<CategoryTree>> categoryTree() {
        List<Category> all = categoryRepository.findByTenantIdAndDeletedFalseOrderBySortNoAsc(1L);
        Map<Long, List<Category>> byParent = all.stream()
                .collect(Collectors.groupingBy(c -> c.getParentId() == null ? -1L : c.getParentId()));
        return ApiResult.ok(buildTree(byParent, -1L));
    }

    private List<CategoryTree> buildTree(Map<Long, List<Category>> byParent, Long parentKey) {
        return byParent.getOrDefault(parentKey, List.of()).stream()
                .map(c -> new CategoryTree(
                        c.getId(), c.getName(), c.getParentId(), c.getLevel(), c.getSortNo(), c.getCode(),
                        buildTree(byParent, c.getId())))
                .collect(Collectors.toList());
    }

    @Transactional
    @PostMapping("/categories")
    @RequirePermission("category:create")
    public ApiResult<Category> createCategory(@RequestBody CategoryReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(40001, "category.name.required");
        }
        Long parentId = req.parentId();
        int level = 0;
        if (parentId != null) {
            Category parent = categoryRepository.findByIdAndDeletedFalse(parentId)
                    .orElseThrow(() -> new BizException(40401, "category.parent.not.found"));
            level = parent.getLevel() + 1;
        }
        Category cat = categoryRepository.save(Category.builder()
                .name(req.name().trim())
                .tenantId(1L)
                .parentId(parentId)
                .level(level)
                .sortNo(req.sortNo() == null ? 0 : req.sortNo())
                .code(computeCode(parentId, level))
                .build());
        return ApiResult.ok(cat);
    }

    @Transactional
    @PutMapping("/categories/{id}")
    @RequirePermission("category:update")
    public ApiResult<Category> updateCategory(@PathVariable Long id, @RequestBody CategoryReq req) {
        Category c = categoryRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(40401, "category.not.found"));
        if (req.name() != null && !req.name().isBlank()) {
            c.setName(req.name().trim());
        }
        if (req.sortNo() != null) {
            c.setSortNo(req.sortNo());
        }
        if (req.parentId() != null) {
            if (req.parentId().equals(id)) {
                throw new BizException(40001, "category.parent.self");
            }
            // 不能挂到自己的子孙下（防止成环）：检查目标父是否已是自己的后代
            Category target = categoryRepository.findByIdAndDeletedFalse(req.parentId())
                    .orElseThrow(() -> new BizException(40401, "category.parent.not.found"));
            if (isDescendant(req.parentId(), id)) {
                throw new BizException(40001, "category.parent.cycle");
            }
            c.setParentId(req.parentId());
            c.setLevel(target.getLevel() + 1);
            c.setCode(computeCode(req.parentId(), c.getLevel()));
        }
        c.setUpdatedAt(Instant.now());
        return ApiResult.ok(categoryRepository.save(c));
    }

    @Transactional
    @DeleteMapping("/categories/{id}")
    @RequirePermission("category:delete")
    public ApiResult<Void> deleteCategory(@PathVariable Long id) {
        Category c = categoryRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(40401, "category.not.found"));
        if (categoryRepository.countByParentIdAndDeletedFalse(id) > 0) {
            throw new BizException(40001, "category.has.children");
        }
        c.setDeleted(true);
        c.setUpdatedAt(Instant.now());
        categoryRepository.save(c);
        return ApiResult.ok(null);
    }

    /** 判断 ancestorId 是否为 nodeId 的后代（含间接），用于防止移动成环。 */
    private boolean isDescendant(Long ancestorId, Long nodeId) {
        Category cur = categoryRepository.findByIdAndDeletedFalse(ancestorId).orElse(null);
        while (cur != null && cur.getParentId() != null) {
            if (cur.getParentId().equals(nodeId)) return true;
            cur = categoryRepository.findByIdAndDeletedFalse(cur.getParentId()).orElse(null);
        }
        return false;
    }

    /** 邮编式层级码：父 code 前缀 + 本级两位序号（如 'A01' / 'A01B02'）。 */
    private String computeCode(Long parentId, int level) {
        String prefix = "";
        if (parentId != null) {
            Category parent = categoryRepository.findByIdAndDeletedFalse(parentId).orElse(null);
            if (parent != null && parent.getCode() != null) {
                prefix = parent.getCode();
            }
        }
        List<Category> siblings = parentId == null
                ? categoryRepository.findByTenantIdAndDeletedFalseOrderBySortNoAsc(1L).stream()
                    .filter(c -> c.getParentId() == null).collect(Collectors.toList())
                : categoryRepository.findByParentIdAndDeletedFalseOrderBySortNoAsc(parentId);
        int seq = siblings.size() + 1;
        char levelChar = (char) ('A' + Math.min(level, 25));
        return prefix + levelChar + String.format("%02d", seq);
    }

    public record CategoryReq(String name, Long parentId, Integer sortNo) {
    }

    /** 分类树节点（递归嵌套）。 */
    public record CategoryTree(Long id, String name, Long parentId, int level, int sortNo, String code,
                               List<CategoryTree> children) {
    }
}
