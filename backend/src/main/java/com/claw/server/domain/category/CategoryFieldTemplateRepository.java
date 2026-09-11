package com.claw.server.domain.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 品类级字段模板仓储（对应 claw.category_field_templates）。 */
@Repository
public interface CategoryFieldTemplateRepository extends JpaRepository<CategoryFieldTemplate, Long> {

    /** 按品类 + 排序号升序取模板字段（商品创建时按此顺序复制到 product_template_fields）。 */
    List<CategoryFieldTemplate> findByCategoryIdOrderBySortNoAsc(Long categoryId);
}
