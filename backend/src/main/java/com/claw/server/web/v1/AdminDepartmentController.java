package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.domain.user.Department;
import com.claw.server.domain.user.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.claw.server.common.security.RequirePermission;

/**
 * 部门管理（C3 数据范围维度锚点）：列出 / 建树 / 创建 / 更名改父。
 * 仅需登录即可访问（与 admin 其它接口同口径）。
 *
 * <p>GET    /departments          扁平列表（按 tenantId）
 * GET    /departments/tree     嵌套树（按 parent_id 在内存组装）
 * POST   /departments          新建部门（name 必填，parentId 可空）
 * PUT    /departments/{id}     更名 / 改父部门
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminDepartmentController {

    private final DepartmentRepository departmentRepository;

    @GetMapping("/departments")
    public ApiResult<List<Department>> listDepartments() {
        return ApiResult.ok(departmentRepository.findByTenantId(1L));
    }

    /**
     * 部门嵌套树：拉取全部部门后按 parent_id 在内存组装为多层结构。
     * 树根（parent_id = NULL）挂在哨兵键 -1L 下；org_code 一并下发给前端展示层级码。
     */
    @GetMapping("/departments/tree")
    public ApiResult<List<DepartmentTree>> departmentTree() {
        List<Department> all = departmentRepository.findAll();
        Map<Long, List<Department>> byParent = all.stream()
                .collect(Collectors.groupingBy(d -> d.getParentId() == null ? -1L : d.getParentId()));
        return ApiResult.ok(buildTree(byParent, -1L));
    }

    private List<DepartmentTree> buildTree(Map<Long, List<Department>> byParent, Long parentKey) {
        return byParent.getOrDefault(parentKey, List.of()).stream()
                .map(d -> new DepartmentTree(
                        d.getId(), d.getName(), d.getParentId(), d.getOrgCode(),
                        buildTree(byParent, d.getId())))
                .collect(Collectors.toList());
    }

    @Transactional
    @PostMapping("/departments")
    @RequirePermission("department:create")
    public ApiResult<Department> createDepartment(@RequestBody DepartmentReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(40001, "department.name.required");
        }
        Long parentId = req.parentId();
        if (parentId != null) {
            departmentRepository.findById(parentId)
                    .orElseThrow(() -> new BizException(40401, "department.parent.not.found"));
        }
        Department dept = departmentRepository.save(Department.builder()
                .name(req.name().trim())
                .tenantId(1L)
                .parentId(parentId)
                .orgCode(computeOrgCode(parentId))
                .build());
        return ApiResult.ok(dept);
    }

    @Transactional
    @PutMapping("/departments/{id}")
    @RequirePermission("department:update")
    public ApiResult<Department> updateDepartment(@PathVariable Long id, @RequestBody DepartmentReq req) {
        Department d = departmentRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "department.not.found"));
        if (req.name() != null && !req.name().isBlank()) {
            d.setName(req.name().trim());
        }
        if (req.parentId() != null) {
            if (req.parentId().equals(id)) {
                throw new BizException(40001, "department.parent.self");
            }
            departmentRepository.findById(req.parentId())
                    .orElseThrow(() -> new BizException(40401, "department.parent.not.found"));
            d.setParentId(req.parentId());
        }
        return ApiResult.ok(departmentRepository.save(d));
    }

    /** 邮编式层级码：父部门 org_code 前缀 + 本级两位序号（如 'A01' / 'A01B02'）。 */
    private String computeOrgCode(Long parentId) {
        String prefix = "";
        if (parentId != null) {
            Department parent = departmentRepository.findById(parentId).orElse(null);
            if (parent != null && parent.getOrgCode() != null) {
                prefix = parent.getOrgCode();
            }
        }
        List<Department> siblings = parentId == null
                ? departmentRepository.findByTenantId(1L).stream()
                    .filter(d -> d.getParentId() == null).collect(Collectors.toList())
                : departmentRepository.findByParentId(parentId);
        int seq = siblings.size() + 1;
        char level = (char) ('A' + (parentId == null ? 0 : 1));
        return prefix + level + String.format("%02d", seq);
    }

    public record DepartmentReq(String name, Long parentId) {
    }

    /** 部门树节点（递归嵌套）。 */
    public record DepartmentTree(Long id, String name, Long parentId, String orgCode,
                                 List<DepartmentTree> children) {
    }
}
