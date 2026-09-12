package com.claw.server.domain.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 车辆产品类服务（T1 薄层）：配置驱动多车型的核心读写。
 * 新增车型 = createClass + addAttr，零代码；能力/设备/证件以 CSV 表达，
 * 直接对接既有资产类型注册表与 RBAC 品类管理方向。
 */
@Service
@RequiredArgsConstructor
public class VehicleProductClassService {

    private final VehicleProductClassRepository classRepository;
    private final VehicleScenarioAttrRepository attrRepository;

    @Transactional
    public VehicleProductClass createClass(VehicleProductClass productClass) {
        if (classRepository.findByCode(productClass.getCode()).isPresent()) {
            throw new IllegalArgumentException("vehicle.product.class.code.exists:" + productClass.getCode());
        }
        Instant now = Instant.now();
        productClass.setCreatedAt(now);
        productClass.setUpdatedAt(now);
        return classRepository.save(productClass);
    }

    public List<VehicleProductClass> listAll() {
        return classRepository.findAll();
    }

    public VehicleProductClass getByCode(String code) {
        return classRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("vehicle.product.class.not.found:" + code));
    }

    @Transactional
    public VehicleScenarioAttr addAttr(Long productClassId, VehicleScenarioAttr attr) {
        if (!classRepository.existsById(productClassId)) {
            throw new IllegalArgumentException("vehicle.product.class.not.found:" + productClassId);
        }
        attr.setProductClassId(productClassId);
        return attrRepository.save(attr);
    }

    public List<VehicleScenarioAttr> listAttrs(Long productClassId) {
        return attrRepository.findByProductClassId(productClassId);
    }

    /**
     * 种子 8 类车型配置（T4）：冷链 / 移动售卖 / 物流 / 客运出租 / 环卫 / 消防 / 观光 / 巡逻。
     * 幂等：仅当 {@code vehicle_product_classes} 为空时插入，重复调用安全。
     * 新增车型 = 在此追加一行配置（或插库），零代码。
     *
     * @return 实际插入的产品类数量（已存在则返回 0）
     */
    @Transactional
    public int seedDefaultProfiles() {
        if (classRepository.count() > 0) {
            return 0;
        }
        int inserted = 0;
        for (ProfileSpec spec : DEFAULT_PROFILES) {
            VehicleProductClass pc = classRepository.save(VehicleProductClass.builder()
                    .code(spec.code)
                    .nameZh(spec.nameZh).nameEn(spec.nameEn).nameKm(spec.nameKm)
                    .scenario(spec.scenario)
                    .capabilityTags(String.join(",", spec.capabilities))
                    .defaultDeviceTypes(String.join(",", spec.devices))
                    .requiredCerts(String.join(",", spec.certs))
                    .build());
            for (AttrSpec a : spec.attrs) {
                attrRepository.save(VehicleScenarioAttr.builder()
                        .productClassId(pc.getId())
                        .attrKey(a.key).attrType(a.type).unit(a.unit)
                        .required(a.required)
                        .labelZh(a.labelZh).labelEn(a.labelEn).labelKm(a.labelKm)
                        .sortOrder(a.order)
                        .build());
            }
            inserted++;
        }
        return inserted;
    }

    /** 车型配置规格（T4 种子数据）。 */
    private static final class ProfileSpec {
        final String code, nameZh, nameEn, nameKm, scenario;
        final String[] capabilities, devices, certs;
        final AttrSpec[] attrs;

        ProfileSpec(String code, String nameZh, String nameEn, String nameKm, String scenario,
                   String[] capabilities, String[] devices, String[] certs, AttrSpec[] attrs) {
            this.code = code; this.nameZh = nameZh; this.nameEn = nameEn; this.nameKm = nameKm;
            this.scenario = scenario; this.capabilities = capabilities; this.devices = devices;
            this.certs = certs; this.attrs = attrs;
        }
    }

    private static final class AttrSpec {
        final String key, type, unit, labelZh, labelEn, labelKm;
        final boolean required;
        final int order;

        AttrSpec(String key, String type, String unit, boolean required, String labelZh, String labelEn, String labelKm, int order) {
            this.key = key; this.type = type; this.unit = unit; this.required = required;
            this.labelZh = labelZh; this.labelEn = labelEn; this.labelKm = labelKm; this.order = order;
        }
    }

    private static final ProfileSpec[] DEFAULT_PROFILES = {
        new ProfileSpec("COLD_CHAIN_TRUCK", "冷链运输车", "Cold-chain Truck", "រថយន្តត្រជាក់", "COLD_CHAIN",
                new String[]{"COLD_CHAIN", "AD_DISPLAY"},
                new String[]{"VEHICLE_TCU", "BMS", "CAMERA"},
                new String[]{"营运证"},
                new AttrSpec[]{
                        new AttrSpec("boxTempRange", "STRING", "°C", true, "厢温范围", "Box Temp Range", "ជួរសីតុណ្ហភាពប្រអប់", 1),
                        new AttrSpec("loadKg", "NUMBER", "kg", true, "额定载重", "Rated Load", "ទម្ងន់ផ្ទុក", 2)
                }),
        new ProfileSpec("MOBILE_VENDING", "移动售卖车", "Mobile Vending Van", "រថយន្តលក់ដូរចល័ត", "VENDING",
                new String[]{"VENDING", "AD_DISPLAY"},
                new String[]{"VEHICLE_TCU", "BMS", "AD_SCREEN"},
                new String[]{"经营许可"},
                new AttrSpec[]{
                        new AttrSpec("boxVolumeL", "NUMBER", "L", true, "货箱容积", "Box Volume", "មាឌប្រអប់", 1)
                }),
        new ProfileSpec("LOGISTICS_VAN", "物流快递车", "Logistics Van", "រថយន្តឡូស្ទិក", "LOGISTICS",
                new String[]{"LOGISTICS", "AD_DISPLAY"},
                new String[]{"VEHICLE_TCU", "BMS", "CAMERA"},
                new String[]{"营运证"},
                new AttrSpec[]{
                        new AttrSpec("loadKg", "NUMBER", "kg", true, "额定载重", "Rated Load", "ទម្ងន់ផ្ទុក", 1),
                        new AttrSpec("boxVolumeL", "NUMBER", "L", false, "货箱容积", "Box Volume", "មាឌប្រអប់", 2)
                }),
        new ProfileSpec("RIDE_HAIL_CAR", "客运出租汽车", "Ride-hail / Taxi", "រថយន្តតាក់ស៊ី", "RIDE_HAIL",
                new String[]{"RIDE_HAIL", "TAXI", "AD_DISPLAY"},
                new String[]{"VEHICLE_TCU", "BMS", "CAMERA", "AD_SCREEN"},
                new String[]{"营运证"},
                new AttrSpec[]{
                        new AttrSpec("seats", "NUMBER", "座", true, "座位数", "Seats", "ចំនួនកៅអី", 1)
                }),
        new ProfileSpec("SANITATION_TRUCK", "环卫作业车", "Sanitation Truck", "រថយន្តសម្អាត", "SANITATION",
                new String[]{"SANITATION"},
                new String[]{"VEHICLE_TCU", "BMS"},
                new String[]{"市政批文"},
                new AttrSpec[]{
                        new AttrSpec("workHours", "NUMBER", "h", true, "作业计时", "Work Hours", "ម៉ោងធ្វើការ", 1)
                }),
        new ProfileSpec("FIRE_TRUCK", "消防应急车", "Fire / Emergency Truck", "រថយន្តពន្លត់អគ្គិភ័យ", "EMERGENCY",
                new String[]{"GOV", "EMERGENCY"},
                new String[]{"VEHICLE_TCU", "BMS"},
                new String[]{"政府资产"},
                new AttrSpec[]{
                        new AttrSpec("specialEquip", "STRING", null, true, "专用设备", "Special Equipment", "ឧបករណ៍ពិសេស", 1)
                }),
        new ProfileSpec("TOURIST_SHUTTLE", "观光接驳车", "Tourist Shuttle", "រថយន្តទេសចរណ៍", "TOURISM",
                new String[]{"TOURISM", "TAXI"},
                new String[]{"VEHICLE_TCU", "BMS", "CAMERA"},
                new String[]{"旅游许可"},
                new AttrSpec[]{
                        new AttrSpec("seats", "NUMBER", "座", true, "座位数", "Seats", "ចំនួនកៅអី", 1)
                }),
        new ProfileSpec("PATROL_CAR", "巡逻安保车", "Patrol / Security Car", "រថយន្តល្បាត", "PATROL",
                new String[]{"PATROL", "SECURITY"},
                new String[]{"VEHICLE_TCU", "BMS", "CAMERA"},
                new String[]{"安保资质"},
                new AttrSpec[]{
                        new AttrSpec("recResolution", "STRING", null, false, "录像分辨率", "Recording Resolution", "គុណភាពវីដេអូ", 1)
                })
    };
}
