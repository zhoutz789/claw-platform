package com.claw.server.common.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 入驻申请材料码（增量 C · §2.5）。
 *
 * <p>落 {@code onboarding_material_requirements.material_code} 与
 * {@code onboarding_attachments.attach_type}。具体是否必填、数量上下限、排序由
 * 材料清单配置表按 {@code applicant_type} 决定，本枚举只定义码与默认展示名。
 */
public enum MaterialCode {

    APPLICANT_NAME("姓名", InputType.TEXT),
    CONTACT("联系方式", InputType.TEXT),
    HOME_ADDRESS("家庭地址", InputType.TEXTAREA),
    ID_CARD("身份证编号", InputType.TEXT),
    BUSINESS_LICENSE("营业执照", InputType.IMAGE),
    BUSINESS_SCOPE("经营范围", InputType.TEXTAREA),
    LEGAL_REP_CERT("法人证明", InputType.IMAGE),
    LAND_CERT("土地证明", InputType.IMAGE),
    OWNERSHIP_LEASE("所有权/租赁证明", InputType.IMAGE),
    SIGNBOARD("门头照片", InputType.IMAGE),
    SITE_PHOTO("场地照片", InputType.IMAGES),
    LAND_INTRO("土地介绍", InputType.TEXTAREA),
    COOPERATION_PLAN("合作申请计划", InputType.TEXTAREA),
    LOCATION("场地定位", InputType.LOCATION),
    SIGNED_AGREEMENT("用户签署协议", InputType.IMAGE),
    MFG_QUALIFICATION("生产资质", InputType.IMAGE),
    BRAND_AUTH("品牌授权", InputType.IMAGE),
    MERCH_CATEGORY("经营品类", InputType.TEXT),
    OTHER("其他附件", InputType.IMAGES);

    /** 材料项的输入控件类型。 */
    public enum InputType {
        TEXT,
        TEXTAREA,
        IMAGE,
        IMAGES,
        FILE,
        LOCATION
    }

    private final String defaultName;
    private final InputType inputType;

    MaterialCode(String defaultName, InputType inputType) {
        this.defaultName = defaultName;
        this.inputType = inputType;
    }

    public String defaultName() {
        return defaultName;
    }

    public InputType inputType() {
        return inputType;
    }

    /**
     * 安全解析材料码（未知码返回 empty，不抛异常 —— 材料清单是配置数据，
     * 允许平台后续扩展未收录进枚举的自定义码）。
     *
     * @param code 材料码字符串
     * @return 匹配到的枚举值
     */
    public static Optional<MaterialCode> parse(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(m -> m.name().equals(code.trim().toUpperCase()))
                .findFirst();
    }

    /** 多图类材料（需校验数量上下限）。 */
    public boolean multiImage() {
        return inputType == InputType.IMAGES;
    }
}
