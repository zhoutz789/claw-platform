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
    /** 合规文案护栏拦截（营销/承诺类文案含禁止表述，HTTP 422 不可处理实体） */
    public static final int COMPLIANCE_TEXT_REJECTED = 42200;
    /** 资产状态机非法迁移 */
    public static final int ILLEGAL_ASSET_STATUS = 30001;
    /** 资源不存在（角色/资产/用户等通用） */
    public static final int NOT_FOUND = 40400;
    /** 未认证（无有效登录上下文，或上下文里的用户在库中不存在）：HTTP 401 */
    public static final int UNAUTHORIZED = 40100;
    /** 权限不足（无所需权限位，HTTP 403） */
    public static final int FORBIDDEN = 40301;
    /**
     * 无人机合规闸门拒绝（HTTP 403）。
     *
     * <p>与 {@link #FORBIDDEN}（权限位不足）区分开：这是「业务/合规层拒绝」（无有效许可、
     * 命中零容忍区等），鉴权已通过。单独给一个码并显式映射 403，避免落入
     * {@code code >= 30000 → 409} 的通用分支把「合规拒绝」误报成「状态冲突」。
     */
    public static final int COMPLIANCE_DENIED = 40305;

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

    /** 未认证：HTTP 401。 */
    public static BizException unauthorized(String messageCode, Object... args) {
        return new BizException(UNAUTHORIZED, messageCode, args);
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

    /**
     * HTTP 状态映射：未认证（40100）返回 401，权限类（40301）返回 403，
     * 资源不存在（40400）返回 404，资金类（2xxxx）返回 422，其余 4xxxx 返回 409，1xxxx 返回 400。
     *
     * <p>注意 40100 必须排在 {@code code >= 30000} 的 409 分支之前判断。
     *
     * <p>同理，404xx 整段必须排在 {@code code >= 30000} 的 409 分支之前判断：
     * 否则"资源不存在"会被兜成 409 Conflict，语义错误（资源不存在 ≠ 状态机冲突），
     * 且监控上把 404 与真实状态机冲突混为一类，掩盖真实信号。
     *
     * <p>为什么是整段 {@code 40400–40499} 而不只认 NOT_FOUND：
     * 项目约定「错误码前三位 ≈ HTTP 状态码」，404xx 全段都是"查不到资源"语义，
     * 已全量核实，共 4 个码、无一例外：
     * <ul>
     *   <li>40400 {@code BizException.notFound()}（49 处）</li>
     *   <li>40401 {@code *.not.found}（130 处，全部挂在 {@code .orElseThrow()} 上，
     *       如 station/custody/transfer.order/inventory/product/manufacturer 等）</li>
     *   <li>40450 {@code error.country.not.found}</li>
     *   <li>40461 {@code error.swap.battery.detail}（换电电池明细查不到）</li>
     * </ul>
     * 真正的状态机冲突自成一段（409xx、3xxxx 如 ILLEGAL_ASSET_STATUS 30001），
     * 不受本分支影响，仍返回 409。
     */
    public HttpStatus httpStatus() {
        if (code == UNAUTHORIZED) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code == FORBIDDEN) {
            return HttpStatus.FORBIDDEN;
        }
        if (code == COMPLIANCE_DENIED) {
            return HttpStatus.FORBIDDEN;
        }
        if (code >= 40400 && code < 40500) {
            return HttpStatus.NOT_FOUND;
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
