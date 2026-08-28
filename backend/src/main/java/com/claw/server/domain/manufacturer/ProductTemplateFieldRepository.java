package com.claw.server.domain.manufacturer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 产品模板字段仓储（对应 claw.product_template_fields）。 */
public interface ProductTemplateFieldRepository extends JpaRepository<ProductTemplateField, Long> {

    /** 按产品 + 排序号升序取模板字段。 */
    List<ProductTemplateField> findByProductIdOrderBySortNoAsc(Long productId);

    /** 判定 (product_id, field_key) 是否已存在（强制唯一）。 */
    boolean existsByProductIdAndFieldKey(Long productId, String fieldKey);
}
