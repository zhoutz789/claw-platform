package com.claw.server.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：BizException → 按 Accept-Language 翻译 messageCode；
 * 校验异常 → 400；未知异常 → 500（打日志、不外泄堆栈）。
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnknown(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ApiResult.error(-1, "internal error"));
    }
}
