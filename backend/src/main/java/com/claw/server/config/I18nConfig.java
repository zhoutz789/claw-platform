package com.claw.server.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * 三语 i18n 框架（英/柬/中）。
 * 客户端通过 Accept-Language 头指定语言；未指定时默认英语。
 * 资源文件：classpath:i18n/messages_{en|km|zh}.properties
 */
@Configuration
public class I18nConfig {

    /**
     * 支持的语言列表必须逐个显式登记：{@link AcceptHeaderLocaleResolver} 只在
     * {@code supportedLocales} 里做<b>精确匹配</b>，匹配不上就静默回落
     * {@code defaultLocale}（英语）—— 不会因为语言相同（zh vs zh-CN）就兜底。
     *
     * <p>此前只登记了 {@code zh_CN}，而浏览器/前端最常发的恰恰是<b>裸 {@code zh}</b>，
     * 于是中文客户端（{@code Accept-Language: zh}）一路拿到英文文案，
     * 且完全没有告警，只能靠人工点开页面才发现。这里把真实会出现的中文 tag 都登记上：
     * <ul>
     *   <li>{@code zh} —— 裸语言标签，最常见；</li>
     *   <li>{@code zh-CN} —— 带国家，中国大陆；</li>
     *   <li>{@code zh-Hans-CN} —— 带文字标签，部分浏览器/移动端会发。</li>
     * </ul>
     * 三者最终都解析到 {@code messages_zh.properties}（按 language → language_country
     * 逐级回退），不会重复维护三份文案。
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(
                Locale.ENGLISH,
                Locale.forLanguageTag("zh"),
                Locale.SIMPLIFIED_CHINESE,
                Locale.forLanguageTag("zh-Hans-CN"),
                Locale.forLanguageTag("km")));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }
}
