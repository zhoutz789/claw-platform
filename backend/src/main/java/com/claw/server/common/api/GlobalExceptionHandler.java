package com.claw.server.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：BizException → 按 Accept-Language 翻译 messageCode；
 * 参数/校验类异常 → 400；Spring MVC 框架级客户端错误 → 沿用框架自带状态码；
 * 未知异常 → 500（打日志、不外泄堆栈）。
 *
 * <p>Spring MVC 的参数绑定异常（缺参、类型不匹配、请求体不可读）本质是客户端错误，
 * 此前全部落到 {@code handleUnknown} 变成 500，既污染告警（全是 5xx），
 * 前端也拿不到有意义的提示。这里统一兜成 400，沿用 INVALID_PARAM 错误码。
 *
 * <p>同理，框架级客户端错误（{@code NoResourceFoundException} → 404、
 * {@code HttpRequestMethodNotSupportedException} → 405、
 * {@code HttpMediaTypeNotSupportedException} → 415 ……）也一律实现
 * {@link ErrorResponse} 并自带正确状态码。但 {@code @ExceptionHandler(Exception.class)}
 * 的优先级高于 Spring 内建的 {@code DefaultHandlerExceptionResolver}，
 * 会把它们全部吞成 500 —— 前端一个路径拼错就收到 500，既误导排查
 * （看起来像服务端炸了），也污染 5xx 告警（真库冒烟以「5xx = 真 bug」为门禁口径）。
 * 这里在兜底分支里先把这类异常让回框架给的状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBiz(BizException e) {
        String localized = messageSource.getMessage(
                e.getMessageCode(), e.getArgs(), e.getMessageCode(), LocaleContextHolder.getLocale());
        return ResponseEntity.status(e.httpStatus())
                .body(ApiResult.error(e.getCode(), localized));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("invalid request");
        return ResponseEntity.badRequest()
                .body(ApiResult.error(BizException.INVALID_PARAM, detail));
    }

    /** 必填 query/form 参数缺失 → 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String detail = "missing required parameter: " + e.getParameterName()
                + " (expected type: " + e.getParameterType() + ")";
        log.warn("bad request: {}", detail);
        return ResponseEntity.badRequest()
                .body(ApiResult.error(BizException.INVALID_PARAM, detail));
    }

    /**
     * 参数类型不匹配 → 400。典型场景：路径/query 参数是 UUID、Long、枚举，
     * 客户端传了非法字面量（如 {@code Invalid UUID string: 1}）。
     * 取最内层 cause，避免把 ConversionFailedException 的包装噪声透给前端。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String detail = "parameter '" + e.getName() + "' has invalid value: "
                + String.valueOf(e.getValue()) + " (" + root.getMessage() + ")";
        log.warn("bad request: {}", detail);
        return ResponseEntity.badRequest()
                .body(ApiResult.error(BizException.INVALID_PARAM, detail));
    }

    /** 请求体缺失或 JSON 不可解析 → 400（@RequestBody 解析失败）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("bad request: unreadable request body: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResult.error(BizException.INVALID_PARAM, "request body is missing or malformed"));
    }

    /**
     * 兜底分支。两类情况必须分开：
     *
     * <ol>
     *   <li><b>Spring MVC 框架级客户端错误</b>（实现 {@link ErrorResponse} 且状态码是 4xx）：
     *       沿用框架自带状态码（404/405/415/406…），只记 WARN。典型是
     *       {@code NoResourceFoundException}（请求路径没有任何 handler 命中）——
     *       前端路径拼错、接口版本下线的残留调用都会走到这里，属于客户端问题；</li>
     *   <li><b>真正的未知异常</b>：记 ERROR 并返回 500。</li>
     * </ol>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnknown(Exception e) {
        if (e instanceof ErrorResponse response) {
            // resolve() 而不是 valueOf()：自定义 ErrorResponse 可能带非标准状态码
            // （如 499），valueOf() 会抛 IllegalArgumentException —— 那反而把
            // 一个客户端错误变成了真正的 500，正是这里要避免的事。
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                String detail = status.getReasonPhrase() + ": " + rootMessage(e);
                log.warn("client error {}: {}", status.value(), detail);
                int code = status == HttpStatus.NOT_FOUND
                        ? BizException.NOT_FOUND
                        : BizException.INVALID_PARAM;
                return ResponseEntity.status(status).body(ApiResult.error(code, detail));
            }
        }
        log.error("unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ApiResult.error(-1, "internal error"));
    }

    /** 取最内层异常消息，避免把包装异常的噪声透给前端。 */
    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
