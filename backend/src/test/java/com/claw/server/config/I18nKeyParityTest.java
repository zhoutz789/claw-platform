package com.claw.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * en / km 语言包与 zh <b>逐 key 对齐</b>的防回归测试。
 *
 * <p><b>为什么需要这个测试：</b>{@link I18nConfig#messageSource()} 里
 * {@code setUseCodeAsDefaultMessage(true)}，key 缺失时 Spring
 * <b>不抛异常、不打日志</b>，直接把 {@code messageCode} 原样当文案返回。同时
 * {@code localeResolver()} 的默认语言是英语。于是任何一个只有 zh 有、en/km 没有的 key，
 * 在柬埔寨（金边，默认英语）真机上会渲染成 {@code error.permission.denied} 这种裸 key ——
 * 页面上看得见、服务端完全无感，只能靠人工点开页面才发现。
 *
 * <p>云端真机复现（DRIVER 调 {@code PUT /api/v1/admin/users/1/department}）：
 * <pre>
 *   Accept-Language: en    -&gt; {"code":40301,"message":"error.permission.denied"}   ← 裸 key
 *   Accept-Language: zh-CN -&gt; {"code":40301,"message":"权限不足，无法执行该操作"}   ← 正常
 *   Accept-Language: km    -&gt; {"code":40301,"message":"error.permission.denied"}   ← 裸 key
 * </pre>
 *
 * <p>本测试从 classpath 直接读 {@code .properties}（不依赖 Spring 容器），把三条不变量钉死：
 * <ol>
 *   <li>三个 bundle 的 <b>key 集合完全一致</b>（差集为空，失败信息列出具体 key 便于定位）；</li>
 *   <li>每个 key 在 en / km 下的 value <b>非空</b>，且 <b>不等于 key 本身</b>
 *       —— 后者专门抓 {@code useCodeAsDefaultMessage} 的静默兜底，
 *       只断言「非空」是抓不住的，因为缺 key 时返回的正是 key 本身；</li>
 *   <li>每个 key 在 en / km 下的 <b>MessageFormat 占位符集合与 zh 完全一致</b>
 *       —— 防止漏翻或改动 {@code {0}} / {@code {1}}，导致报文拼接错位。</li>
 * </ol>
 *
 * <p>读取时强制使用 UTF-8 {@link Reader}：{@code Properties.load(InputStream)} 默认按
 * ISO-8859-1 解释，会把汉字与高棉字母读成乱码。
 */
class I18nKeyParityTest {

    /** 与 {@link I18nConfig#messageSource()} 的 basename 对应：classpath:i18n/messages_{lang}.properties */
    private static final String BASENAME = "i18n/messages";

    private static final String ZH = "zh";
    private static final String EN = "en";
    private static final String KM = "km";

    /** 真机复现的那条 key：作为「资源确实被读到」的探针，避免路径写错导致测试空跑。 */
    private static final String PROBE_KEY = "error.permission.denied";

    private final Properties zh = load(ZH);
    private final Properties en = load(EN);
    private final Properties km = load(KM);

    /** 三语 key 集合必须完全一致。 */
    @Test
    void allBundlesShareExactlyTheSameKeySet() {
        Set<String> zhKeys = keySet(zh);

        // 防「空跑」：资源必须真的读到，且含探针 key。
        assertFalse(zhKeys.isEmpty(), "messages_zh.properties 未读到任何 key，请检查 classpath 路径");
        assertTrue(zhKeys.contains(PROBE_KEY),
                "messages_zh.properties 缺少探针 key " + PROBE_KEY + "，测试可能读错了文件");

        assertEquals(zhKeys, keySet(en), diffReport(EN, zhKeys, keySet(en)));
        assertEquals(zhKeys, keySet(km), diffReport(KM, zhKeys, keySet(km)));
    }

    /** en 每个 key 都必须有非空、且不等于 key 本身的文案。 */
    @Test
    void englishValuesArePresentAndNotBareKeys() {
        assertNoBlankOrBareKeyValues(EN, en);
    }

    /** km 每个 key 都必须有非空、且不等于 key 本身的文案。 */
    @Test
    void khmerValuesArePresentAndNotBareKeys() {
        assertNoBlankOrBareKeyValues(KM, km);
    }

    /** en / km 每个 key 的占位符集合必须与 zh 一致（数量与编号都不许变）。 */
    @Test
    void placeholdersMatchChineseForEveryKey() {
        assertPlaceholdersMatch(ZH, zh, EN, en);
        assertPlaceholdersMatch(ZH, zh, KM, km);
    }

    /**
     * 断言某个语言包不存在「空文案」或「裸 key」。
     *
     * @param lang   语言标签，仅用于失败信息
     * @param bundle 已加载的语言包
     */
    private static void assertNoBlankOrBareKeyValues(String lang, Properties bundle) {
        Set<String> offenders = new TreeSet<>();
        for (String key : keySet(bundle)) {
            String value = bundle.getProperty(key);
            if (value == null || value.trim().isEmpty() || value.trim().equals(key)) {
                offenders.add(key);
            }
        }
        assertTrue(offenders.isEmpty(),
                "messages_" + lang + ".properties 存在空文案或裸 key（useCodeAsDefaultMessage=true 会把裸 key 直接透给客户端），"
                        + "共 " + offenders.size() + " 个：" + offenders);
    }

    /**
     * 断言目标语言包每个 key 的 MessageFormat 占位符集合与参考语言包一致。
     *
     * @param refLang 参考语言（zh）
     * @param ref     参考语言包
     * @param lang    目标语言
     * @param target  目标语言包
     */
    private static void assertPlaceholdersMatch(String refLang, Properties ref, String lang, Properties target) {
        Set<String> mismatched = new TreeSet<>();
        for (String key : keySet(target)) {
            String refValue = ref.getProperty(key);
            if (refValue == null) {
                continue; // key 缺失已由 keySet 一致性测试覆盖，这里不重复报
            }
            if (!placeholders(refValue).equals(placeholders(target.getProperty(key)))) {
                mismatched.add(key);
            }
        }
        assertTrue(mismatched.isEmpty(),
                "messages_" + lang + ".properties 的占位符与 " + refLang + " 不一致（{0}/{1}... 的数量或编号被改动），"
                        + "共 " + mismatched.size() + " 个：" + mismatched);
    }

    /**
     * 提取文案里的 MessageFormat 占位符编号集合，如 {@code "SKU {0}: {1}/{2}"} → {@code [0, 1, 2]}。
     *
     * @param value 文案
     * @return 占位符编号集合
     */
    private static Set<Integer> placeholders(String value) {
        Set<Integer> indices = new TreeSet<>();
        if (value == null) {
            return indices;
        }
        int i = 0;
        while (i < value.length()) {
            int open = value.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int close = value.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }
            String token = value.substring(open + 1, close).trim();
            if (token.matches("\\d+")) {
                indices.add(Integer.parseInt(token));
            }
            i = close + 1;
        }
        return indices;
    }

    /**
     * 生成三语 key 集合不一致时的可读差集报告。
     *
     * @param lang      被比对的语言标签
     * @param reference 参考 key 集合（zh）
     * @param actual    实际 key 集合
     * @return 失败信息，明确列出「缺少」与「多出」的 key
     */
    private static String diffReport(String lang, Set<String> reference, Set<String> actual) {
        Set<String> missing = new TreeSet<>(reference);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(reference);
        return "messages_" + lang + ".properties 与 messages_zh.properties 的 key 集合不一致："
                + "缺少 " + missing.size() + " 个 " + missing
                + "；多出 " + extra.size() + " 个 " + extra;
    }

    /**
     * 从 classpath 读取指定语言的 {@code .properties}，强制 UTF-8。
     *
     * @param lang 语言标签，如 {@code en} / {@code km} / {@code zh}
     * @return 已加载的语言包
     */
    private static Properties load(String lang) {
        String path = BASENAME + "_" + lang + ".properties";
        Properties props = new Properties();
        ClassPathResource resource = new ClassPathResource(path);
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("无法从 classpath 读取 " + path, e);
        }
        return props;
    }

    /**
     * 返回语言包的有序 key 集合。
     *
     * @param props 语言包
     * @return 升序排列的 key 集合
     */
    private static Set<String> keySet(Properties props) {
        return new TreeSet<>(props.stringPropertyNames());
    }
}
