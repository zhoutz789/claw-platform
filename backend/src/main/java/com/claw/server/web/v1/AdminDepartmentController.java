package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.domain.user.Department;
import com.claw.server.domain.user.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门管理（C3 数据范围维度锚点）：列出/创建部门。
 * 仅需登录即可访问（与 admin 其它接口同口径）。
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

    @Transactional
    @PostMapping("/departments")
    public ApiResult<Department> createDepartment(@RequestBody DepartmentReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(40001, "department.name.required");
        }
        Department dept = departmentRepository.save(Department.builder()
                .name(req.name().trim())
                .tenantId(1L)
                .build());
        return ApiResult.ok(dept);
    }

    public record DepartmentReq(String name) {
    }
}
