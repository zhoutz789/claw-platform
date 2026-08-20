package com.claw.server.config;

import com.claw.server.common.security.CountryInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册法域拦截器，使每个请求都落入对应的国家上下文。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CountryInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/ping");
    }
}
