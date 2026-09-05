package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 低成本合规文案护栏（S2 合规闸门补充）。
 *
 * <p>对面向用户的营销/承诺类文案做关键词扫描：命中「禁止表述」（如保本、稳赚、零风险、guaranteed return、
 * 高额返利等）即拦截，防止构成非法募资或误导宣传。规则由 {@code system_config} 驱动
 * （{@code COMPLIANCE_PROHIBITED_KEYWORDS}，JSON 数组），运营可维护；配置缺失/非法时回退内置最小集，
 * 保证护栏始终在线。
 *
 * <p>低成本：纯内存包含匹配，无外部模型调用、无额外存储（仅读配置）。与 DtiCheckService 同属 compliance
 * 域，但本服务只读取配置、不写库，属无副作用的纯校验。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceTextService {

    private static final String KEYWORDS_KEY = "COMPLIANCE_PROHIBITED_KEYWORDS";

    /** 内置最小禁止集（配置缺失时兜底，确保护栏不下线）。 */
    private static final String FALLBACK_KEYWORDS = """
            [
              {"pattern":"保本","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},
              {"pattern":"稳赚","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},
              {"pattern":"零风险","severity":"REJECT","category":"MISLEADING"},
              {"pattern":" guaranteed return","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},
              {"pattern":"high return","severity":"WARN","category":"MISLEADING"}
            ]
            """;

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 扫描文案，返回是否放行与命中明细（不抛异常，由调用方决定软/硬拦截）。 */
    public TextCheckResult scan(String text) {
        if (text == null || text.isBlank()) {
            return new TextCheckResult(true, List.of());
        }
        List<KeywordRule> rules = loadRules();
        List<TextHit> hits = new ArrayList<>();
        String lower = text.toLowerCase();
        for (KeywordRule r : rules) {
            if (lower.contains(r.pattern().toLowerCase())) {
                hits.add(new TextHit(r.pattern(), r.severity(), r.category(),
                        text.length() > 64 ? text.substring(0, 64) + "…" : text));
            }
        }
        boolean allowed = hits.stream().noneMatch(h -> "REJECT".equals(h.severity()));
        return new TextCheckResult(allowed, hits);
    }

    /** 硬拦截：命中 REJECT 级表述直接抛 BizException（用于提交文案的前置校验）。 */
    public void assertAllowed(String text, String scene) {
        TextCheckResult r = scan(text);
        if (!r.allowed()) {
            throw BizException.of(42200, "error.compliance.text.rejected", scene == null ? "" : scene);
        }
    }

    private List<KeywordRule> loadRules() {
        String json = systemConfigRepository.findByConfigKeyAndDeletedFalse(KEYWORDS_KEY)
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(FALLBACK_KEYWORDS);
        try {
            return objectMapper.readValue(json, new TypeReference<List<KeywordRule>>() {});
        } catch (Exception e) {
            log.warn("COMPLIANCE_PROHIBITED_KEYWORDS 解析失败，回退内置最小集: {}", e.getMessage());
            try {
                return objectMapper.readValue(FALLBACK_KEYWORDS, new TypeReference<List<KeywordRule>>() {});
            } catch (Exception ex) {
                return List.of();
            }
        }
    }

    /** 禁止表述规则（JSON 反序列化目标）。 */
    public record KeywordRule(String pattern, String severity, String category) {
    }

    /** 单条命中明细。 */
    public record TextHit(String pattern, String severity, String category, String excerpt) {
    }

    /** 扫描结果。 */
    public record TextCheckResult(boolean allowed, List<TextHit> hits) {
    }
}
