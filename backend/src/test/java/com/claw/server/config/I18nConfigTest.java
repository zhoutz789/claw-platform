package com.claw.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 三语（英/柬/中）语言协商回归测试（V65）。
 *
 * <p><b>为什么需要这个测试：</b>{@code AcceptHeaderLocaleResolver} 只在
 * {@code supportedLocales} 里做<b>精确匹配</b>，匹配不上就静默回落默认语言（英语），
 * 既不打日志也不报错。此前只登记了 {@code zh_CN}，而浏览器与前端最常发的恰恰是
 * <b>裸 {@code zh}</b>，于是中文客户端一路拿到英文文案 —— 页面上看得见、
 * 服务端完全无感，只能靠人工点开页面才发现。
 *
 * <p>这里同时锁住两件事：
 * <ol>
 *   <li>真实会出现的中文 tag（{@code zh} / {@code zh-CN} / {@code zh-Hans-CN}）都能被解析；</li>
 *   <li>解析出来的 locale 确实能查到中文文案（防止「locale 解析对了、
 *       资源文件名对不上，结果还是回落英文」这种半截修复）。</li>
 * </ol>
 */
class I18nConfigTest {

    private static final String PROBE = "error.auth.unauthenticated";

    private final I18nConfig config = new I18nConfig();
    private final LocaleResolver resolver = config.localeResolver();
    private final MessageSource messageSource = config.messageSource();

    /** 中文：裸 zh / zh-CN / zh-Hans-CN 三种写法都必须解析成中文，不能回落英语。 */
    @Test
    void resolvesAllChineseTagsToChinese() {
        assertLocalized("zh", "未登录或登录已失效，请先登录");
        assertLocalized("zh-CN", "未登录或登录已失效，请先登录");
        assertLocalized("zh-Hans-CN", "未登录或登录已失效，请先登录");
    }

    /** 高棉语与英语。 */
    @Test
    void resolvesKhmerAndEnglish() {
        assertLocalized("km", null);   // 柬文文案由母语同事维护，只断言「不是英文、不是裸 key」
        assertLocalized("en", "Not authenticated, please sign in first");
    }

    /** 未登记的语言（法语）回落英语，且不会返回裸 key。 */
    @Test
    void unsupportedLanguageFallsBackToEnglish() {
        assertLocalized("fr", "Not authenticated, please sign in first");
    }

    /** 完全不带 Accept-Language 头时回落英语（默认语言）。 */
    @Test
    void missingAcceptLanguageHeaderFallsBackToEnglish() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(Locale.ENGLISH, resolver.resolveLocale(request));
    }

    /**
     * V82 服务站寄售入库：新增的 6 个业务码必须有中文与英文文案。
     *
     * <p><b>为什么加这个断言：</b>{@link I18nConfig#messageSource()} 开了
     * {@code useCodeAsDefaultMessage(true)}，缺文案时<b>不报错也不告警</b>，直接把裸
     * {@code messageCode} 原样返回 —— 操作员看到的是
     * {@code inventory.custody.held.by.other.station} 这种天书。新接口的错误码尤其容易漏配，
     * 这里把「每个码都能取到非裸 key 的文案」钉成回归。
     */
    @Test
    void stationConsignmentInboundCodesHaveCopy() {
        List<String> codes = List.of(
                "station.consignment.inbound.station_required",   // 40301 非服务站主体
                "station.consignment.inbound.device_required",    // 10001 缺 deviceId
                "inventory.custody.duplicate.inbound",            // 40945 本站重复入库
                "inventory.custody.held.by.other.station",        // 40944 他站已占有
                "inventory.owner_manufacturer.missing",           // 40943 台账缺货权方
                "inventory.not.found");                           // 40401 设备不在台账

        for (String code : codes) {
            assertNotEquals(code, messageSource.getMessage(code, null, Locale.forLanguageTag("zh")),
                    "messages_zh.properties 缺少 " + code + " 的中文文案（客户端会看到裸 key）");
            assertNotEquals(code, messageSource.getMessage(code, null, Locale.ENGLISH),
                    "messages_en.properties 缺少 " + code + " 的英文兜底文案");
        }
    }

    /**
     * 断言：给定 {@code Accept-Language} 解析出的 locale 能取到预期文案。
     *
     * @param tag             语言标签，如 {@code zh} / {@code zh-CN}
     * @param expectedMessage 预期文案；传 {@code null} 表示只断言「不是英文、不是裸 key」
     */
    private void assertLocalized(String tag, String expectedMessage) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setPreferredLocales(List.of(Locale.forLanguageTag(tag)));
        Locale resolved = resolver.resolveLocale(request);
        String actual = messageSource.getMessage(PROBE, null, resolved);

        // 永远不该把裸 key 透给客户端（useCodeAsDefaultMessage=true 时的失败形态）
        assertNotEquals(PROBE, actual, "Accept-Language=" + tag + " 未命中任何语言包");

        if (expectedMessage != null) {
            assertEquals(expectedMessage, actual, "Accept-Language=" + tag + " 文案不匹配");
        } else {
            assertNotEquals("Not authenticated, please sign in first", actual,
                    "Accept-Language=" + tag + " 意外回落到了英语");
        }
    }
}
