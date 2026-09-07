package com.claw.server.domain.certificate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 合格证模板字段仓储（对应 claw.certificate_template_fields）。 */
public interface CertificateTemplateFieldRepository extends JpaRepository<CertificateTemplateField, Long> {

    /** 按排序号升序取全部模板字段。 */
    List<CertificateTemplateField> findByOrderBySortNoAsc();

    /** 判定 field_key 是否已存在（强制唯一）。 */
    boolean existsByFieldKey(String fieldKey);
}
