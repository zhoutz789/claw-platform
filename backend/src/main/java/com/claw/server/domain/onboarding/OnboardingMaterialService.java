package com.claw.server.domain.onboarding;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.MaterialCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 入驻材料清单配置服务（增量 C · O7 / B1）。
 *
 * <p>材料清单按 {@code applicant_type} 配置：是否必填、数量上下限、排序、说明文案。
 * 前端据此<b>动态渲染</b>申请表单 —— 服务站要土地/场地照，厂家要生产资质/品牌授权，
 * 商家要经营品类且不需要土地证明（Q9 独立主体推论）。
 *
 * <p>这是「新增主体类型 / 调整材料要求」唯一的改动点，无需改代码。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingMaterialService {

    private final OnboardingMaterialRequirementRepository requirementRepository;

    /** 取某主体类型的清单（仅启用项，按 sort_no 升序）。 */
    @Transactional(readOnly = true)
    public List<OnboardingMaterialRequirement> listEnabled(String applicantType) {
        return requirementRepository.findByApplicantTypeAndEnabledTrueOrderBySortNoAsc(applicantType);
    }

    /** 取某主体类型的全部清单（含停用项，供后台配置页使用）。 */
    @Transactional(readOnly = true)
    public List<OnboardingMaterialRequirement> listAll(String applicantType) {
        return requirementRepository.findByApplicantTypeOrderBySortNoAsc(applicantType);
    }

    /** 取单项配置。 */
    @Transactional(readOnly = true)
    public OnboardingMaterialRequirement get(Long id) {
        return requirementRepository.findById(id)
                .orElseThrow(() -> BizException.of(40401, "onboarding.material.not.found"));
    }

    /**
     * 新增 / 更新一项材料要求。
     *
     * @param id           为 null 时新增，否则更新
     * @param applicantType 主体类型
     * @param materialCode  材料码
     * @return 保存后的配置
     */
    @Transactional
    public OnboardingMaterialRequirement upsert(Long id, String applicantType, String materialCode,
                                               String materialName, String inputType, Boolean required,
                                               Integer minCount, Integer maxCount, String hint, Integer sortNo) {
        OnboardingMaterialRequirement req = (id == null)
                ? OnboardingMaterialRequirement.builder()
                        .applicantType(applicantType)
                        .materialCode(materialCode)
                        .build()
                : get(id);
        if (id != null) {
            if (applicantType != null) {
                req.setApplicantType(applicantType);
            }
            if (materialCode != null) {
                req.setMaterialCode(materialCode);
            }
        }
        if (req.getApplicantType() == null || req.getMaterialCode() == null) {
            throw BizException.of(10001, "onboarding.material.code.required");
        }
        // 名称回退到枚举默认名，避免配置页漏填导致前端显示空白
        if (materialName != null && !materialName.isBlank()) {
            req.setMaterialName(materialName);
        } else if (req.getMaterialName() == null) {
            req.setMaterialName(MaterialCode.parse(req.getMaterialCode())
                    .map(MaterialCode::defaultName)
                    .orElse(req.getMaterialCode()));
        }
        if (inputType != null) {
            req.setInputType(inputType);
        } else if (req.getInputType() == null) {
            req.setInputType(MaterialCode.parse(req.getMaterialCode())
                    .map(m -> m.inputType().name())
                    .orElse("TEXT"));
        }
        if (required != null) {
            req.setRequired(required);
        }
        if (minCount != null) {
            req.setMinCount(minCount);
        }
        if (maxCount != null) {
            req.setMaxCount(maxCount);
        }
        if (hint != null) {
            req.setHint(hint);
        }
        if (sortNo != null) {
            req.setSortNo(sortNo);
        }
        validateCountRange(req);
        return requirementRepository.save(req);
    }

    /** 启用 / 停用某项材料要求。 */
    @Transactional
    public OnboardingMaterialRequirement setEnabled(Long id, boolean enabled) {
        OnboardingMaterialRequirement req = get(id);
        req.setEnabled(enabled);
        return requirementRepository.save(req);
    }

    @Transactional
    public void delete(Long id) {
        requirementRepository.delete(get(id));
        log.info("入驻材料要求已删除 id={}", id);
    }

    /**
     * 校验某项材料已上传的附件数量是否满足清单要求（提交时全量校验用）。
     *
     * @param requirement 材料要求
     * @param count       已上传数量
     * @return 不满足时返回错误说明，满足返回 empty
     */
    public Optional<String> validateCount(OnboardingMaterialRequirement requirement, int count) {
        if (!Boolean.TRUE.equals(requirement.getRequired()) && count == 0) {
            return Optional.empty();
        }
        Integer min = requirement.getMinCount();
        Integer max = requirement.getMaxCount();
        String name = requirement.getMaterialName();
        if (Boolean.TRUE.equals(requirement.getRequired()) && count <= 0) {
            return Optional.of(name + "：必填");
        }
        if (min != null && count < min) {
            return Optional.of(name + "：至少 " + min + " 项");
        }
        if (max != null && count > max) {
            return Optional.of(name + "：最多 " + max + " 项");
        }
        return Optional.empty();
    }

    private void validateCountRange(OnboardingMaterialRequirement req) {
        if (req.getMinCount() != null && req.getMaxCount() != null
                && req.getMinCount() > req.getMaxCount()) {
            throw BizException.of(10001, "onboarding.material.count.range.invalid");
        }
        if (req.getMinCount() != null && req.getMinCount() < 0) {
            throw BizException.of(10001, "onboarding.material.count.negative");
        }
    }
}
