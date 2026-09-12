package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.domain.asset.VehicleProductClass;
import com.claw.server.domain.asset.VehicleProductClassRepository;
import com.claw.server.domain.asset.VehicleProductClassService;
import com.claw.server.domain.asset.VehicleScenarioAttr;
import com.claw.server.domain.asset.VehicleScenarioAttrRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车型产品类（配置驱动多车型）管理端口（T8）。
 *
 * <p>车型代码重复 / 车型不存在时，原服务抛裸 {@code IllegalArgumentException}，
 * 全局异常处理器无对应 handler → 500 + "internal error"。这里转为语义化业务码：
 * <ul>
 *   <li>40961 → HTTP 409，车型代码已存在（状态冲突）；</li>
 *   <li>40461 → HTTP 404，车型不存在。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/vehicle-product-classes")
@RequiredArgsConstructor
public class VehicleProductClassController {

    private final VehicleProductClassService service;
    private final VehicleProductClassRepository classRepository;
    private final VehicleScenarioAttrRepository attrRepository;

    /** 种子 8 类车型配置（幂等，仅当表为空时插入）。 */
    @PostMapping("/seed")
    public ApiResult<Integer> seedDefaultProfiles() {
        return ApiResult.ok(service.seedDefaultProfiles());
    }

    /** 列出全部车型产品类。 */
    @GetMapping
    public ApiResult<List<VehicleProductClass>> listAll() {
        return ApiResult.ok(service.listAll());
    }

    /**
     * 新建车型产品类。
     *
     * @throws BizException 40961 error.vehicle.product.class.code.exists（代码重复）
     */
    @PostMapping
    public ApiResult<VehicleProductClass> createClass(@RequestBody CreateVehicleProductClass req) {
        VehicleProductClass pc = VehicleProductClass.builder()
                .code(req.code())
                .nameZh(req.nameZh())
                .nameEn(req.nameEn())
                .nameKm(req.nameKm())
                .scenario(req.scenario())
                .autonomyLevel(req.autonomyLevel())
                .capabilityTags(req.capabilityTags())
                .defaultDeviceTypes(req.defaultDeviceTypes())
                .attrSchema(req.attrSchema())
                .requiredCerts(req.requiredCerts())
                .geofencePreset(req.geofencePreset())
                .build();
        try {
            return ApiResult.ok(service.createClass(pc));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("vehicle.product.class.code.exists")) {
                throw BizException.of(40961, "error.vehicle.product.class.code.exists", req.code());
            }
            throw ex;
        }
    }

    /**
     * 按代码取车型产品类。
     *
     * @throws BizException 40461 error.vehicle.product.class.not.found（车型不存在）
     */
    @GetMapping("/{code}")
    public ApiResult<VehicleProductClass> getByCode(@PathVariable String code) {
        try {
            return ApiResult.ok(service.getByCode(code));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("vehicle.product.class.not.found")) {
                throw BizException.of(40461, "error.vehicle.product.class.not.found", code);
            }
            throw ex;
        }
    }

    /**
     * 取某车型的场景属性定义列表。
     *
     * @throws BizException 40461 error.vehicle.product.class.not.found（车型不存在）
     */
    @GetMapping("/{code}/attrs")
    public ApiResult<List<VehicleScenarioAttr>> listAttrs(@PathVariable String code) {
        try {
            Long id = service.getByCode(code).getId();
            return ApiResult.ok(service.listAttrs(id));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("vehicle.product.class.not.found")) {
                throw BizException.of(40461, "error.vehicle.product.class.not.found", code);
            }
            throw ex;
        }
    }

    /**
     * 给某车型追加一条场景属性定义。
     *
     * @throws BizException 40461 error.vehicle.product.class.not.found（车型不存在）
     */
    @PostMapping("/{code}/attrs")
    public ApiResult<VehicleScenarioAttr> addAttr(@PathVariable String code,
                                                 @RequestBody CreateScenarioAttr req) {
        try {
            Long productClassId = service.getByCode(code).getId();
            VehicleScenarioAttr attr = VehicleScenarioAttr.builder()
                    .productClassId(productClassId)
                    .attrKey(req.attrKey())
                    .attrType(req.attrType())
                    .unit(req.unit())
                    .required(req.required() != null ? req.required() : false)
                    .labelZh(req.labelZh())
                    .labelEn(req.labelEn())
                    .labelKm(req.labelKm())
                    .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                    .build();
            return ApiResult.ok(service.addAttr(productClassId, attr));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("vehicle.product.class.not.found")) {
                throw BizException.of(40461, "error.vehicle.product.class.not.found", code);
            }
            throw ex;
        }
    }

    public record CreateVehicleProductClass(String code, String nameZh, String nameEn, String nameKm,
                                            String scenario, String autonomyLevel, String capabilityTags,
                                            String defaultDeviceTypes, String attrSchema, String requiredCerts,
                                            String geofencePreset) {
    }

    public record CreateScenarioAttr(String attrKey, String attrType, String unit, Boolean required,
                                     String labelZh, String labelEn, String labelKm, Integer sortOrder) {
    }
}
