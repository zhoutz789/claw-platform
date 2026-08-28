package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传（商品封面图 / 视频等）。
 *
 * <p>上传目录解析规则（须与 {@code WebFileConfig} 完全一致）：
 * 优先取系统属性 {@code claw.upload.dir}，否则默认 {@code <user.dir>/uploads}。
 * 文件落地后通过 {@code /files/<name>} 静态资源路径对外服务（见 WebFileConfig）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class UploadController {

    /** 解析上传根目录（与 WebFileConfig 保持同一逻辑）。 */
    public static File resolveUploadDir() {
        return new File(System.getProperty("claw.upload.dir",
                System.getProperty("user.dir") + "/uploads")).getAbsoluteFile();
    }

    @PostMapping("/upload")
    public ApiResult<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(40001, "upload.empty");
        }
        File dir = resolveUploadDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new BizException(50001, "upload.dir.unavailable");
        }
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        if (ext.isEmpty()) {
            ext = ".bin";
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
        try {
            file.transferTo(new File(dir, storedName));
        } catch (Exception e) {
            throw new BizException(50001, "upload.failed");
        }
        return ApiResult.ok(Map.of("url", "/files/" + storedName,
                "fileName", originalName == null ? "" : originalName));
    }
}
