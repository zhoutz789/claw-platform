package com.claw.server.domain.onboarding;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 入驻申请表单提交 / 草稿保存请求（增量 C · O8 / O9）。
 *
 * <p>表单由 {@code onboarding_material_requirements} 按 {@code applicant_type} <b>动态渲染</b>，
 * 故文本类与附件类材料分别以 map 形式提交（key = material_code），避免为每一类主体写一套 DTO。
 *
 * @param applicationId 为空则新建草稿；非空则更新指定草稿
 * @param applicantType STATION / MANUFACTURER / MERCHANT
 * @param lang          合同语言（zh / km / en），默认 zh
 * @param fields        文本类材料值：materialCode → 值
 *                      （APPLICANT_NAME / CONTACT / HOME_ADDRESS / ID_CARD / BUSINESS_SCOPE /
 *                        LAND_INTRO / COOPERATION_PLAN / MERCH_CATEGORY）
 * @param attachments   附件类材料值：materialCode → 已上传文件 URL 列表
 *                      （BUSINESS_LICENSE / LEGAL_REP_CERT / LAND_CERT / OWNERSHIP_LEASE /
 *                        SIGNBOARD / SITE_PHOTO / SIGNED_AGREEMENT / MFG_QUALIFICATION /
 *                        BRAND_AUTH / OTHER）
 * @param ownershipType OWNED 自有 / LEASED 租赁（对应 OWNERSHIP_LEASE 材料项）
 * @param lat           场地定位纬度（O12）
 * @param lng           场地定位经度
 * @param geoAddress    定位反查地址
 * @param depositTierId 所选保证金档位（提交时必填）
 * @param contractId    签署时锁定的合同版本；为空则自动取当前生效版本
 * @param agreed        是否已勾选同意协议（提交时必为 true，O3）
 */
public record OnboardingFormReq(
        Long applicationId,
        String applicantType,
        String lang,
        Map<String, String> fields,
        Map<String, List<String>> attachments,
        String ownershipType,
        BigDecimal lat,
        BigDecimal lng,
        String geoAddress,
        Long depositTierId,
        Long contractId,
        Boolean agreed) {

    /** 空 map 兜底，避免调用方大量判空。 */
    public Map<String, String> safeFields() {
        return fields == null ? Map.of() : fields;
    }

    /** 空 map 兜底。 */
    public Map<String, List<String>> safeAttachments() {
        return attachments == null ? Map.of() : attachments;
    }

    /** 取某文本类材料的值（去空白后可能为空串）。 */
    public String field(String materialCode) {
        String v = safeFields().get(materialCode);
        return v == null ? null : v.trim();
    }

    /** 取某附件类材料的 URL 列表（过滤空白项）。 */
    public List<String> files(String materialCode) {
        List<String> list = safeAttachments().get(materialCode);
        if (list == null) {
            return List.of();
        }
        return list.stream().filter(s -> s != null && !s.isBlank()).toList();
    }
}
