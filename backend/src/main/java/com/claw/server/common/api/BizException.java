package com.claw.server.common.api;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。messageCode 对应 i18n/messages_{locale}.properties 中的 key，
 * 由 GlobalExceptionHandler 统一翻译后返回给客户端。
 */
public class BizException extends RuntimeException {

    /** 默认通用业务错误码 */
    public static final int GENERIC = 10000;
    /** 参数校验失败 */
    public static final int INVALID_PARAM = 10001;
    /** 资金账本不平衡（复式记账借贷不平，属严重错误，须告警） */
    public static final int LEDGER_UNBALANCED = 20001;
    /** 押金余额不足 */
    public static final int DEPOSIT_INSUFFICIENT = 20002;
    /** DTI 超限（月供+换电预估 > 验证净收入 50%，负责任信贷强制校验） */
    public static final int DTI_EXCEEDED = 20003;
    /** 资产状态机非法迁移 */
    public static final int ILLEGAL_ASSET_STATUS = 30001;
    /** 资源不存在（角色/资产/用户等通用） */
    public static final int NOT_FOUND = 40400;
    /** 权限不足（无所需权限位，HTTP 403） */
    public static final int FORBIDDEN = 40301;

    private final int code;
    private final String messageCode;
    private final Object[] args;

    public BizException(int code, String messageCode, Object... args) {
        super(messageCode);
        this.code = code;
        this.messageCode = messageCode;
        this.args = args;
    }

    public static BizException invalidParam(String messageCode, Object... args) {
        return new BizException(INVALID_PARAM, messageCode, args);
    }

    public static BizException dtiExceeded() {
        return new BizException(DTI_EXCEEDED, "error.responsible.lending.dti");
    }

    public static BizException of(int code, String messageCode, Object... args) {
        return new BizException(code, messageCode, args);
    }

    public static BizException notFound(String messageCode, Object... args) {
        return new BizException(NOT_FOUND, messageCode, args);
    }

    /** 权限不足：HTTP 403。 */
    public static BizException forbidden(String messageCode, Object... args) {
        return new BizException(FORBIDDEN, messageCode, args);
    }

    public int getCode() {
        return code;
    }

    public String getMessageCode() {
        return messageCode;
    }

    public Object[] getArgs() {
        return args;
    }

    /** HTTP 状态映射：资金类（2xxxx）返回 422，权限类（40301）返回 403，其余 400，未知走 500 */
    public HttpStatus httpStatus() {
        if (code == FORBIDDEN) {
            return HttpStatus.FORBIDDEN;
        }
        if (code >= 20000 && code < 30000) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        if (code >= 30000) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
