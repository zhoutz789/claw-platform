package com.claw.server.web;

import com.claw.server.web.v1.UploadController;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 静态资源服务：把上传目录映射为 {@code /files/**}，使 {@link com.claw.server.web.v1.UploadController}
 * 落盘的文件可通过 {@code http://<host>:<port>/files/<name>} 直接访问。
 *
 * <p>上传目录解析规则与 {@code UploadController} 完全一致：
 * 优先取系统属性 {@code claw.upload.dir}，否则默认 {@code <user.dir>/uploads}。
 */
@Configuration
public class WebFileConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String dir = UploadController.resolveUploadDir().getAbsolutePath();
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:" + dir + "/");
    }
}
