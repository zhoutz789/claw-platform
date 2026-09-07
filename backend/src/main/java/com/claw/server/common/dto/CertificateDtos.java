package com.claw.server.common.dto;

import java.time.Instant;

/**
 * 合格证域出入参（common 层：不得 import 任何 domain 类，只用基础类型 / String / Instant）。
 * 对应 ③：合格证可编辑识别信息 + 全局 EAV 模板字段。
 */
public final class CertificateDtos {

    private CertificateDtos() {
    }

    /** 设备合格证视图（含不可变快照 spec_json 与可编辑 data_json）。 */
    public record CertificateDto(
            Long id,
            Long deviceId,
            String certNo,
            Long manufacturerId,
            Long productId,
            String specJson,
            String dataJson,
            Long issuedBy,
            Instant issuedAt) {
    }

    /** 合格证模板字段视图（全局 EAV）。 */
    public record CertificateTemplateFieldView(
            Long id,
            String fieldKey,
            String label,
            String type,
            String unit,
            String optionsJson,
            boolean required,
            int sortNo) {
    }

    /** 创建模板字段请求。 */
    public record CreateCertificateTemplateFieldReq(
            String fieldKey,
            String label,
            String type,
            String unit,
            String optionsJson,
            Boolean required,
            Integer sortNo) {
    }

    /** 更新模板字段请求（仅覆盖非空项）。 */
    public record UpdateCertificateTemplateFieldReq(
            String label,
            String type,
            String unit,
            String optionsJson,
            Boolean required,
            Integer sortNo) {
    }

    /** 更新设备合格证识别信息请求（dataJson 为 JSON 字符串）。 */
    public record UpdateCertificateDataReq(String dataJson) {
    }
}
