package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.domain.asset.VehicleProductClass;
import com.claw.server.domain.asset.VehicleProductClassRepository;
import com.claw.server.domain.asset.VehicleProductClassService;
import com.claw.server.domain.asset.VehicleScenarioAttr;
import com.claw.server.domain.asset.VehicleScenarioAttrRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link VehicleProductClassController} 的接线测试（无 Spring 上下文，纯 Mockito）。
 * 重点覆盖：列表 / 种子返回、创建成功、代码重复→40961、车型不存在→40461（getByCode / addAttr）。
 */
@ExtendWith(MockitoExtension.class)
class VehicleProductClassControllerTest {

    @Mock
    private VehicleProductClassService service;
    @Mock
    private VehicleProductClassRepository classRepository;
    @Mock
    private VehicleScenarioAttrRepository attrRepository;

    @InjectMocks
    private VehicleProductClassController controller;

    @Test
    void listAll_returnsList() {
        List<VehicleProductClass> list = List.of(VehicleProductClass.builder()
                .code("A").nameZh("a").nameEn("a").nameKm("a").scenario("S").build());
        when(service.listAll()).thenReturn(list);

        ApiResult<List<VehicleProductClass>> result = controller.listAll();

        assertEquals(1, result.data().size());
    }

    @Test
    void seedDefaultProfiles_returnsInt() {
        when(service.seedDefaultProfiles()).thenReturn(8);

        ApiResult<Integer> result = controller.seedDefaultProfiles();

        assertEquals(8, result.data());
    }

    @Test
    void createClass_happyPath_returnsData() {
        VehicleProductClass saved = VehicleProductClass.builder()
                .code("X").nameZh("x").nameEn("x").nameKm("x").scenario("S").build();
        when(service.createClass(any(VehicleProductClass.class))).thenReturn(saved);

        ApiResult<VehicleProductClass> result = controller.createClass(
                new VehicleProductClassController.CreateVehicleProductClass(
                        "X", "x", "x", "x", "S", null, null, null, null, null, null));

        assertEquals("X", result.data().getCode());
    }

    @Test
    void createClass_duplicateCode_throws40961() {
        when(service.createClass(any(VehicleProductClass.class)))
                .thenThrow(new IllegalArgumentException("vehicle.product.class.code.exists:X"));

        BizException ex = assertThrows(BizException.class, () -> controller.createClass(
                new VehicleProductClassController.CreateVehicleProductClass(
                        "X", "x", "x", "x", "S", null, null, null, null, null, null)));

        assertEquals(40961, ex.getCode());
    }

    @Test
    void getByCode_missing_throws40461() {
        when(service.getByCode("MISSING"))
                .thenThrow(new IllegalArgumentException("vehicle.product.class.not.found:MISSING"));

        BizException ex = assertThrows(BizException.class, () -> controller.getByCode("MISSING"));

        assertEquals(40461, ex.getCode());
    }

    @Test
    void addAttr_missingClass_throws40461() {
        when(service.getByCode("MISSING"))
                .thenThrow(new IllegalArgumentException("vehicle.product.class.not.found:MISSING"));

        BizException ex = assertThrows(BizException.class, () -> controller.addAttr("MISSING",
                new VehicleProductClassController.CreateScenarioAttr(
                        "k", "STRING", null, false, "lz", "le", "lk", 0)));

        assertEquals(40461, ex.getCode());
    }
}
