package com.claw.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「代码引用覆盖率」防回归测试。
 *
 * <p><b>为什么需要这个测试：</b>{@link I18nKeyParityTest} 只保证三个语言包的 <b>key 集合互相对齐</b>
 * （zh == en == km），却<b>不保证</b>这些 key 就是代码真正在用的那一批。当代码里写了一个三语都
 * 没有的 {@code messageCode} 时，{@link I18nConfig#messageSource()} 的
 * {@code setUseCodeAsDefaultMessage(true)} 会让 Spring <b>静默</b>把 key 原样返回 —— 页面上出现
 * {@code certificate.not.found} 这种裸 key，包括中文在内的<b>所有</b>语言都一样，服务端毫无告警。
 *
 * <p>云端真机复现（平台管理员 token，{@code Accept-Language} 任意）：
 * <pre>
 *   GET /api/v1/admin/production/certificates/device/999999
 *   -&gt; {"code":40401,"message":"certificate.not.found","data":null}   ← 裸 key，中文也一样
 * </pre>
 *
 * <p>本测试静态扫描 {@code src/main/java} 下全部 {@code .java}，把代码里<b>当作 message key 使用</b>
 * 的字符串字面量提取出来，逐一断言它在 <b>zh / en / km</b> 三个 bundle 里都存在。识别三类调用写法：
 * <ol>
 *   <li>{@code new BizException(code, "key")}（含全限定名 {@code com.claw.server.common.api.BizException}）；</li>
 *   <li>{@code BizException.of(code, "key")} / {@code notFound("key")} / {@code forbidden(...)}
 *       / {@code unauthorized(...)} / {@code invalidParam(...)}；</li>
 *   <li>{@code messageSource.getMessage("key", ...)}。</li>
 * </ol>
 * 以 {@code http.} / {@code java.} / {@code com.} / {@code org.} 开头的匹配一律排除（非文案 key）。
 *
 * <p>失败信息会<b>列出缺失的 key 及其被引用的位置（文件:行号）</b>，避免下次定位成本。
 * 另有「防空跑」探针：扫描到的 key 数必须大于 {@value #MIN_EXPECTED_KEYS}，若正则写错导致 0 命中，
 * 测试会明确报错而不是假过。
 */
class I18nCodeCoverageTest {

    /** 与 {@link I18nConfig#messageSource()} 的 basename 对应：classpath:i18n/messages_{lang}.properties */
    private static final String BASENAME = "i18n/messages";

    /** Surefire 的工作目录即模块根目录 {@code backend/}，故此处用相对路径定位源码。 */
    private static final Path SRC_MAIN = Path.of("src/main/java");

    /** 探针 key：仅用于确认 bundle 真的被读到（路径写错时能立刻发现）。 */
    private static final String PROBE_KEY = "error.permission.denied";

    /**
     * 防空跑阈值：当前静态扫描命中 270+ 个 key（业务异常文案远多于 200），
     * 若这里降到 200 以下，说明扫描逻辑（正则/路径）已失效，必须报错而非静默通过。
     */
    private static final int MIN_EXPECTED_KEYS = 200;

    /** 三类 message key 使用写法的提取正则（捕获组 1 为 key 本体）。 */
    private static final List<Pattern> KEY_PATTERNS = List.of(
            Pattern.compile("new\\s+(?:com\\.claw\\.server\\.common\\.api\\.)?BizException\\s*\\("
                    + "\\s*(?:\\d+|[A-Z][A-Z0-9_.]*)\\s*,\\s*\"([a-z][A-Za-z0-9_.\\-]*)\""),
            Pattern.compile("BizException\\.(?:of|notFound|forbidden|unauthorized|invalidParam)\\s*\\("
                    + "\\s*(?:\\d+\\s*,\\s*)?\"([a-z][A-Za-z0-9_.\\-]*)\""),
            Pattern.compile("messageSource\\.getMessage\\s*\\(\\s*\"([A-Za-z][A-Za-z0-9_.\\-]*)\""));

    /** 这些前缀的匹配不是文案 key（国际化文件路径 / 类名 / 域名等），一律排除。 */
    private static final List<String> EXCLUDED_PREFIXES = List.of("http.", "java.", "com.", "org.");

    /**
     * 代码里引用的每一个 message key，都必须在 zh / en / km 三个 bundle 里存在。
     *
     * @throws IOException 读取源码或 classpath 失败
     */
    @Test
    void everyMessageKeyReferencedInCodeExistsInAllBundles() throws IOException {
        Map<String, Set<String>> usages = scanMessageKeys();

        // 防「空跑」：扫描逻辑必须真的命中足够多的 key。
        assertTrue(usages.size() > MIN_EXPECTED_KEYS,
                "扫描 " + SRC_MAIN + " 只得到 " + usages.size() + " 个 message key（预期 > " + MIN_EXPECTED_KEYS
                        + "），提取正则或源码路径可能已失效，本测试将失去意义，请检查 I18nCodeCoverageTest 的配置");

        Set<String> zhKeys = keySet(load("zh"));
        Set<String> enKeys = keySet(load("en"));
        Set<String> kmKeys = keySet(load("km"));

        // 再次确认 bundle 真的读到（否则下面会因「全部缺失」而误报）。
        assertTrue(zhKeys.contains(PROBE_KEY),
                "messages_zh.properties 缺少探针 key " + PROBE_KEY + "，可能读错了文件");

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : usages.entrySet()) {
            String key = entry.getKey();
            List<String> missing = new ArrayList<>();
            if (!zhKeys.contains(key)) {
                missing.add("zh");
            }
            if (!enKeys.contains(key)) {
                missing.add("en");
            }
            if (!kmKeys.contains(key)) {
                missing.add("km");
            }
            if (!missing.isEmpty()) {
                problems.add("  " + key + "  [缺 " + String.join("/", missing) + "]  引用处："
                        + String.join(", ", entry.getValue()));
            }
        }

        assertTrue(problems.isEmpty(),
                "以下 message key 被代码引用，但在语言包里缺失（useCodeAsDefaultMessage=true 会把 key 原样"
                        + "返回给客户端，任何语言下都显示裸 key）。共 " + problems.size() + " 个：\n"
                        + String.join("\n", problems));
    }

    /**
     * 静态扫描 {@code src/main/java}，收集「被当作 message key 使用」的字符串字面量及其引用位置。
     *
     * @return key → 引用位置集合（{@code 文件:行号}）的有序映射
     * @throws IOException 读取源码根目录失败
     */
    private static Map<String, Set<String>> scanMessageKeys() throws IOException {
        Map<String, Set<String>> usages = new TreeMap<>();
        if (!Files.exists(SRC_MAIN)) {
            return usages;
        }
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(SRC_MAIN)) {
            javaFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (Pattern pattern : KEY_PATTERNS) {
                    Matcher matcher = pattern.matcher(line);
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        if (isExcluded(key)) {
                            continue;
                        }
                        usages.computeIfAbsent(key, k -> new TreeSet<>())
                                .add(file.toString() + ":" + (i + 1));
                    }
                }
            }
        }
        return usages;
    }

    /**
     * 判断某个匹配是否属于「非 message key」的噪声。
     *
     * @param key 匹配到的字符串
     * @return 命中排除前缀则返回 {@code true}
     */
    private static boolean isExcluded(String key) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
