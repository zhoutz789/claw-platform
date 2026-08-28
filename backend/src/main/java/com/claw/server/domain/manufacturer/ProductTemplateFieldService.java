package com.claw.server.domain.manufacturer;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ProductDtos.CreateProductTemplateFieldReq;
import com.claw.server.common.dto.ProductDtos.ProductTemplateFieldView;
import com.claw.server.common.dto.ProductDtos.UpdateProductTemplateFieldReq;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 产品模板字段服务（admin 级 schema 管理）。
 *
 * <p>强制 (product_id, field_key) 唯一；校验 type ∈ 允许集合。
 * 返回 common.dto 视图（domain 可依赖 common.dto，反之禁止）。
 */
@Service
@RequiredArgsConstructor
public class ProductTemplateFieldService {

    private static final Set<String> ALLOWED_TYPES = Set.of("number", "text", "select", "date", "boolean");

    private final ProductTemplateFieldRepository fieldRepository;

    @Transactional
    public ProductTemplateFieldView create(CreateProductTemplateFieldReq req) {
        if (req.productId() == null || req.fieldKey() == null || req.label() == null) {
            throw BizException.invalidParam("error.template.field.required");
        }
        if (!ALLOWED_TYPES.contains(req.type())) {
            throw BizException.invalidParam("error.template.field.type.invalid", req.type());
        }
        if (fieldRepository.existsByProductIdAndFieldKey(req.productId(), req.fieldKey())) {
            throw BizException.invalidParam("error.template.field.duplicate", req.productId(), req.fieldKey());
        }
        ProductTemplateField entity = ProductTemplateField.builder()
                .productId(req.productId())
                .fieldKey(req.fieldKey())
                .label(req.label())
                .type(req.type())
                .unit(req.unit())
                .optionsJson(req.optionsJson())
                .required(req.required() != null && req.required())
                .sortNo(req.sortNo() != null ? req.sortNo() : 0)
                .build();
        return toView(fieldRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<ProductTemplateFieldView> list(Long productId) {
        return fieldRepository.findByProductIdOrderBySortNoAsc(productId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ProductTemplateFieldView update(Long fieldId, UpdateProductTemplateFieldReq req) {
        ProductTemplateField entity = fieldRepository.findById(fieldId)
                .orElseThrow(() -> BizException.notFound("error.template.field.not.found"));
        if (req.label() != null) {
            entity.setLabel(req.label());
        }
        if (req.type() != null) {
            if (!ALLOWED_TYPES.contains(req.type())) {
                throw BizException.invalidParam("error.template.field.type.invalid", req.type());
            }
            entity.setType(req.type());
        }
        if (req.unit() != null) {
            entity.setUnit(req.unit());
        }
        if (req.optionsJson() != null) {
            entity.setOptionsJson(req.optionsJson());
        }
        if (req.required() != null) {
            entity.setRequired(req.required());
        }
        if (req.sortNo() != null) {
            entity.setSortNo(req.sortNo());
        }
        return toView(fieldRepository.save(entity));
    }

    @Transactional
    public void delete(Long fieldId) {
        if (!fieldRepository.existsById(fieldId)) {
            throw BizException.notFound("error.template.field.not.found");
        }
        fieldRepository.deleteById(fieldId);
    }

    private ProductTemplateFieldView toView(ProductTemplateField e) {
        return new ProductTemplateFieldView(e.getId(), e.getProductId(), e.getFieldKey(), e.getLabel(),
                e.getType(), e.getUnit(), e.getOptionsJson(), e.isRequired(), e.getSortNo());
    }
}
