package com.claw.server.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BizException#httpStatus()} 映射回归测试（V65）。
 *
 * <p><b>为什么需要这个测试：</b>本项目约定「业务码前三位 ≈ HTTP 状态码」，前端据此分支。
 * 但 {@code httpStatus()} 是一串 if 分支，<b>分支顺序写错不会报编译错误、也不会让单测变红</b>，
 * 只会让客户端拿到错误的状态码 —— 历史上真实发生过两次：
 * <ul>
 *   <li>404xx（资源不存在）被 {@code code >= 30000 → 409} 的分支先吃掉，返回 409；</li>
 *   <li>40100（未认证）被同一个 409 分支先吃掉，返回 409 而不是 401。</li>
 * </ul>
 * 真库端到端冒烟只覆盖「现存接口当前会抛的码」，改分支顺序时未必撞得上；
 * 这里把全段映射逐档锁死，任何顺序调整都会立刻变红。
 */
class BizExceptionTest {

    /** 40100 未认证 —— 必须排在 409 分支之前。 */
    @Test
    void httpStatus_mapsUnauthorizedTo401() {
        assertEquals(HttpStatus.UNAUTHORIZED, BizException.unauthorized("x").httpStatus());
        assertEquals(HttpStatus.UNAUTHORIZED,
                BizException.of(BizException.UNAUTHORIZED, "x").httpStatus());
    }

    /** 40301 权限不足 —— 必须排在 409 分支之前。 */
    @Test
    void httpStatus_mapsForbiddenTo403() {
        assertEquals(HttpStatus.FORBIDDEN, BizException.forbidden("x").httpStatus());
        assertEquals(HttpStatus.FORBIDDEN,
                BizException.of(BizException.FORBIDDEN, "x").httpStatus());
    }

    /**
     * 404xx 整段（资源不存在）必须是 404，不能被 {@code code >= 30000 → 409} 抢先。
     *
     * <p>逐档断言而不只测 40400：整段判定是 {@code code >= 40400 && code < 40500}，
     * 边界值（40400 / 40499）与段外值（40399 / 40500）都得覆盖，
     * 否则有人把上界写成 {@code <= 40500} 也发现不了。
     */
    @Test
    void httpStatus_mapsWhole404xxRangeTo404() {
        assertEquals(HttpStatus.NOT_FOUND, BizException.notFound("x").httpStatus());
        assertEquals(HttpStatus.NOT_FOUND, BizException.of(40401, "x").httpStatus());
        assertEquals(HttpStatus.NOT_FOUND, BizException.of(40450, "x").httpStatus());
        assertEquals(HttpStatus.NOT_FOUND, BizException.of(40461, "x").httpStatus());
        // 段内边界
        assertEquals(HttpStatus.NOT_FOUND, BizException.of(40499, "x").httpStatus());
    }

    /** 404xx 段外：40399 落 409，40500 也落 409（不属于「资源不存在」段）。 */
    @Test
    void httpStatus_outside404xxRangeFallsThroughTo409() {
        assertEquals(HttpStatus.CONFLICT, BizException.of(40399, "x").httpStatus());
        assertEquals(HttpStatus.CONFLICT, BizException.of(40500, "x").httpStatus());
        assertEquals(HttpStatus.CONFLICT, BizException.of(40940, "x").httpStatus());
        assertEquals(HttpStatus.CONFLICT,
                BizException.of(BizException.ILLEGAL_ASSET_STATUS, "x").httpStatus());
    }

    /** 资金类 2xxxx → 422（语义上「参数合法但业务规则不接受」）。 */
    @Test
    void httpStatus_mapsLedgerRangeTo422() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, BizException.dtiExceeded().httpStatus());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, BizException.of(20001, "x").httpStatus());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, BizException.of(29999, "x").httpStatus());
    }

    /**
     * 1xxxx（入参校验等）→ 400。
     *
     * <p>这一档最关键：入参错误若映射成 404，客户端会把「参数拼错」误判成
     * 「资源不存在」，掩盖真实原因（{@code PrincipalType} 就栽在这里）。
     */
    @Test
    void httpStatus_mapsInvalidParamTo400() {
        assertEquals(HttpStatus.BAD_REQUEST, BizException.invalidParam("x").httpStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                BizException.of(BizException.INVALID_PARAM, "x").httpStatus());
        assertEquals(HttpStatus.BAD_REQUEST, BizException.of(10000, "x").httpStatus());
        assertEquals(HttpStatus.BAD_REQUEST, BizException.of(10001, "x").httpStatus());
    }

    /** messageCode 与 args 原样传递（i18n 文案的 key 与占位符不能被改写）。 */
    @Test
    void messageCodeAndArgsArePreserved() {
        BizException e = BizException.invalidParam("some.key", "a", 1);
        assertEquals("some.key", e.getMessageCode());
        assertEquals(2, e.getArgs().length);
        assertEquals("a", e.getArgs()[0]);
        assertEquals(1, e.getArgs()[1]);
    }
}
