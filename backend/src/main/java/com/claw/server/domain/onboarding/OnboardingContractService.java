package com.claw.server.domain.onboarding;

import com.claw.server.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 入驻说明与合同版本服务（增量 C · O1 / O2 / B5）。
 *
 * <p><b>发布即冻结</b>：修改内容必须发新版本（{@code v{major}.{minor}}），历史版本只读留存、
 * 可对比、可回滚。同一 {@code applicant_type + lang} 同时最多一个 PUBLISHED 版本
 * （由 {@code uq_onb_contract_published} 部分唯一索引兜底）。
 *
 * <p>申请单在签署时把版本号<b>快照</b>到 {@code onboarding_applications.contract_version}，
 * 争议以签署时版本为准（B5）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingContractService {

    private final OnboardingContractRepository contractRepository;

    /** 取某主体类型 + 语言的全部版本（新在前）。 */
    @Transactional(readOnly = true)
    public List<OnboardingContract> listVersions(String applicantType, String lang) {
        return contractRepository.findByApplicantTypeAndLangOrderByCreatedAtDesc(applicantType, lang);
    }

    /** 取当前生效版本（PUBLISHED）。 */
    @Transactional(readOnly = true)
    public Optional<OnboardingContract> currentPublished(String applicantType, String lang) {
        return contractRepository.findByApplicantTypeAndLangAndStatus(applicantType, lang, "PUBLISHED");
    }

    /**
     * 取当前生效版本（PUBLISHED），查不到则抛 404（供申请提交时锁定合同）。
     *
     * <p>这里必须用<b>独立</b>的 messageCode（{@code ...published.not.found}），
     * 不能复用 {@code onboarding.contract.not.published}：后者在
     * {@code OnboardingApplicationService} 里的语义是「合同存在但状态未发布」→ 40940
     * （状态冲突）。同一个 key 挂两个 HTTP 状态码（404 与 409），客户端按状态码分支
     * 就会错乱 —— 文案一样，一半场景 404、一半场景 409，且无法通过 key 区分。
     *
     * <p>本方法是「按 (主体, 语言) 查已发布版本，没查到」= 资源不存在 → 40401；
     * 那边是「按 id 查到了合同，但状态不是 PUBLISHED」= 状态冲突 → 40940。
     * 两者语义不同，key 也必须不同。
     */
    @Transactional(readOnly = true)
    public OnboardingContract requirePublished(String applicantType, String lang) {
        return currentPublished(applicantType, lang)
                .orElseThrow(() -> BizException.of(40401, "onboarding.contract.published.not.found"));
    }

    /**
     * 发布新版本：自动生成版本号并把同主体同语言的旧生效版本归档（ARCHIVED）。
     *
     * @param applicantType 主体类型
     * @param lang          语言
     * @param title         标题
     * @param contentHtml   富文本合作要点
     * @param contractFileUrl 公司签章合同扫描件（换扫描件即发新版本，O2）
     * @param major         true = 大版本（major+1, minor=0）；false = 小版本（minor+1）
     * @param operatorId    发布人
     * @return 新版本
     */
    @Transactional
    public OnboardingContract publishNewVersion(String applicantType, String lang, String title,
                                               String contentHtml, String contractFileUrl,
                                               boolean major, Long operatorId) {
        if (contentHtml == null || contentHtml.isBlank()) {
            throw BizException.of(10001, "onboarding.contract.content.required");
        }
        String nextVersion = nextVersion(applicantType, lang, major);
        // 旧生效版本归档：保证同一主体 + 语言同时只有一个 PUBLISHED
        currentPublished(applicantType, lang).ifPresent(old -> {
            old.setStatus("ARCHIVED");
            contractRepository.save(old);
        });
        OnboardingContract c = OnboardingContract.builder()
                .applicantType(applicantType)
                .lang(lang)
                .version(nextVersion)
                .title(title == null || title.isBlank() ? defaultTitle(applicantType) : title)
                .contentHtml(contentHtml)
                .contractFileUrl(contractFileUrl)
                .status("PUBLISHED")
                .publishedAt(Instant.now())
                .publishedBy(operatorId)
                .contentHash(sha256(contentHtml + "|" + (contractFileUrl == null ? "" : contractFileUrl)))
                .build();
        OnboardingContract saved = contractRepository.save(c);
        log.info("入驻说明发布新版本 type={} lang={} version={} operator={}", applicantType, lang, nextVersion, operatorId);
        return saved;
    }

    /** 回滚到指定历史版本：目标版本置 PUBLISHED，当前生效版本归档。 */
    @Transactional
    public OnboardingContract rollback(Long contractId, Long operatorId) {
        OnboardingContract target = contractRepository.findById(contractId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.contract.not.found"));
        currentPublished(target.getApplicantType(), target.getLang()).ifPresent(old -> {
            if (!old.getId().equals(target.getId())) {
                old.setStatus("ARCHIVED");
                contractRepository.save(old);
            }
        });
        target.setStatus("PUBLISHED");
        target.setPublishedAt(Instant.now());
        target.setPublishedBy(operatorId);
        target.setUpdatedAt(Instant.now());
        OnboardingContract saved = contractRepository.save(target);
        log.info("入驻说明回滚 type={} lang={} -> version={}", target.getApplicantType(), target.getLang(),
                target.getVersion());
        return saved;
    }

    /** 保存草稿（后台编辑态）；发布走 {@link #publishNewVersion}。 */
    @Transactional
    public OnboardingContract saveDraft(Long id, String applicantType, String lang, String title,
                                        String contentHtml, String contractFileUrl) {
        OnboardingContract c = (id == null)
                ? OnboardingContract.builder().applicantType(applicantType).lang(lang).build()
                : contractRepository.findById(id)
                        .orElseThrow(() -> BizException.of(40401, "onboarding.contract.not.found"));
        if (title != null) {
            c.setTitle(title);
        }
        if (contentHtml != null) {
            c.setContentHtml(contentHtml);
        }
        if (contractFileUrl != null) {
            c.setContractFileUrl(contractFileUrl);
            c.setContentHash(sha256(c.getContentHtml() + "|" + contractFileUrl));
        }
        if (c.getVersion() == null) {
            c.setVersion(nextVersion(c.getApplicantType(), c.getLang(), false));
        }
        c.setUpdatedAt(Instant.now());
        return contractRepository.save(c);
    }

    @Transactional
    public void archive(Long contractId) {
        OnboardingContract c = contractRepository.findById(contractId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.contract.not.found"));
        c.setStatus("ARCHIVED");
        c.setUpdatedAt(Instant.now());
        contractRepository.save(c);
    }

    /**
     * 计算下一个版本号。
     *
     * <p>解析现有全部版本号（{@code v{major}.{minor}}），取 major 最大者；
     * major 发布则 {@code major+1.0}，否则 {@code major.(minor+1)}。
     */
    private String nextVersion(String applicantType, String lang, boolean major) {
        List<int[]> parsed = new ArrayList<>();
        for (OnboardingContract c : listVersions(applicantType, lang)) {
            parseVersion(c.getVersion()).ifPresent(parsed::add);
        }
        if (parsed.isEmpty()) {
            return major ? "v2.0" : "v1.0";
        }
        int[] latest = parsed.stream()
                .max(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]))
                .orElse(new int[]{1, 0});
        return major ? ("v" + (latest[0] + 1) + ".0") : ("v" + latest[0] + "." + (latest[1] + 1));
    }

    /** 解析 {@code v1.3} → [1,3]；格式不符返回 empty。 */
    private static Optional<int[]> parseVersion(String version) {
        if (version == null) {
            return Optional.empty();
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        String[] parts = v.split("\\.");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())});
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String defaultTitle(String applicantType) {
        return switch (applicantType == null ? "" : applicantType.toUpperCase()) {
            case "MANUFACTURER" -> "厂家入驻合作说明";
            case "MERCHANT" -> "商家入驻合作说明";
            default -> "服务站入驻合作说明";
        };
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return null;
        }
    }
}
