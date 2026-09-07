package com.claw.server.domain.certificate;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.CertificateDtos.CertificateTemplateFieldView;
import com.claw.server.common.dto.CertificateDtos.CreateCertificateTemplateFieldReq;
import com.claw.server.common.dto.CertificateDtos.UpdateCertificateTemplateFieldReq;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 合格证模板字段服务（admin 级 schema 管理，全局单模板）。
 *
 * <p>强制 field_key 唯一；校验 type ∈ 允许集合。返回 common.dto 视图
 * （domain 可依赖 common.dto，反之禁止）。
 */
@Service
@RequiredArgsConstructor
public class CertificateTemplateService {

    private static final Set<String> ALLOWED_TYPES = Set.of("number", "text", "select", "date", "boolean");

    private final CertificateTemplateFieldRepository fieldRepository;

    @Transactional
    public CertificateTemplateFieldView create(CreateCertificateTemplateFieldReq req) {
        if (req.fieldKey() == null || req.label() == null) {
            throw BizException.invalidParam("error.cert.template.field.required");
        }
        if (!ALLOWED_TYPES.contains(req.type())) {
            throw BizException.invalidParam("error.cert.template.field.type.invalid", req.type());
        }
        if (fieldRepository.existsByFieldKey(req.fieldKey())) {
            throw BizException.invalidParam("error.cert.template.field.duplicate", req.fieldKey());
        }
        CertificateTemplateField entity = CertificateTemplateField.builder()
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
    public List<CertificateTemplateFieldView> list() {
        return fieldRepository.findByOrderBySortNoAsc().stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public CertificateTemplateFieldView update(Long id, UpdateCertificateTemplateFieldReq req) {
        CertificateTemplateField entity = fieldRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.cert.template.field.not.found"));
        if (req.label() != null) {
            entity.setLabel(req.label());
        }
        if (req.type() != null) {
            if (!ALLOWED_TYPES.contains(req.type())) {
                throw BizException.invalidParam("error.cert.template.field.type.invalid", req.type());
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
    public void delete(Long id) {
        if (!fieldRepository.existsById(id)) {
            throw BizException.notFound("error.cert.template.field.not.found");
        }
        fieldRepository.deleteById(id);
    }

    private CertificateTemplateFieldView toView(CertificateTemplateField e) {
        return new CertificateTemplateFieldView(e.getId(), e.getFieldKey(), e.getLabel(),
                e.getType(), e.getUnit(), e.getOptionsJson(), e.isRequired(), e.getSortNo());
    }
}
