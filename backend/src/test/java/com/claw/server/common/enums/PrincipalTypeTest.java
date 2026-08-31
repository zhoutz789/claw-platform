package com.claw.server.common.enums;

import com.claw.server.common.api.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PrincipalType#of(String)} 错误码语义回归测试（V65）。
 *
 * <p><b>为什么需要这个测试：</b>{@code of()} 只做枚举字面量校验（trim + toUpperCase + 匹配），
 * 完全不查库，因此入参非法的正确语义是「参数值非法」——{@code BizException.INVALID_PARAM}
 * （10001）→ HTTP 400。此前误标成 {@code 40401}（{@code *.not.found}），客户端拼错
 * {@code applicantType} 时会收到 404 Not Found，被误导成「资源不存在」，掩盖真实原因，
 * 误导性比更早前的 409 Conflict 更强。
 *
 * <p>这类「错误码语义」改动极易被后续重构静默改回（比如下次有人顺手把
 * {@code BizException.invalidParam(...)} 改回 {@code BizException.of(40401, ...)}），
 * 而端到端冒烟只覆盖现存接口，未必抓得到。这里把 code 与 HTTP 映射双双锁死。
 */
class PrincipalTypeTest {

    /**
     * 非法入参（null / 空白 / 非枚举字面量）必须抛 10001，且映射为 HTTP 400。
     *
     * <p>断言 HTTP 映射而不只是 code：code 正确但 {@code BizException.httpStatus()}
     * 分支顺序被改坏时（例如 404xx 的 404 分支被挪到 409 分支之后），客户端拿到的
     * 状态码照样是错的，单看 code 发现不了。
     */
    @Test
    void of_rejectsUnknownValue_withInvalidParamAndBadRequest() {
        assertInvalidParam(() -> PrincipalType.of("BOGUS"));
        assertInvalidParam(() -> PrincipalType.of(null));
        assertInvalidParam(() -> PrincipalType.of(""));
        assertInvalidParam(() -> PrincipalType.of("   "));
        assertInvalidParam(() -> PrincipalType.of("1"));
    }

    /** 数字型入参（前端常把 principal_type 当成字典 id 传）同样按参数非法处理。 */
    @Test
    void of_rejectsNumericValue_withInvalidParam() {
        assertInvalidParam(() -> PrincipalType.of("0"));
    }

    /** 合法值：大小写与首尾空白容忍，结果必须是正确枚举。 */
    @Test
    void of_acceptsLegalValue_caseAndWhitespaceTolerant() {
        assertSame(PrincipalType.STATION, PrincipalType.of("STATION"));
        assertSame(PrincipalType.STATION, PrincipalType.of("station"));
        assertSame(PrincipalType.STATION, PrincipalType.of("Station"));
        assertSame(PrincipalType.STATION, PrincipalType.of(" STATION "));
        assertSame(PrincipalType.STATION, PrincipalType.of("\tSTATION\n"));
    }

    /** 三个枚举值都必须可解析（防止新增枚举时漏改匹配逻辑）。 */
    @Test
    void of_coversAllEnumValues() {
        assertSame(PrincipalType.STATION, PrincipalType.of("STATION"));
        assertSame(PrincipalType.MANUFACTURER, PrincipalType.of("MANUFACTURER"));
        assertSame(PrincipalType.MERCHANT, PrincipalType.of("MERCHANT"));
    }

    /**
     * 异常携带的 messageCode 与 args 必须稳定：messageCode 决定 i18n 文案 key，
     * args 决定文案里的占位符取值，改了会直接导致前端拿到裸 key。
     */
    @Test
    void of_throwsWithStableMessageCodeAndArgs() {
        BizException ex = assertThrows(BizException.class, () -> PrincipalType.of("BOGUS"));
        assertEquals("onboarding.principal.type.unknown", ex.getMessageCode());
        assertEquals(1, ex.getArgs().length);
        assertEquals("BOGUS", ex.getArgs()[0]);
    }

    /** 表名映射（组织治理写操作据此定位主体表），顺带锁住防止被误改。 */
    @Test
    void tableName_matchesPhysicalTables() {
        assertEquals("stations", PrincipalType.STATION.tableName());
        assertEquals("manufacturers", PrincipalType.MANUFACTURER.tableName());
        assertEquals("merchants", PrincipalType.MERCHANT.tableName());
    }

    /**
     * 校验入参非法时抛出的异常确实是「参数非法」语义：code == 10001 且 HTTP 400。
     *
     * <p>显式断言不是 404 / 409 —— 这两个是历史上真实误标过的值，回归时最容易复发。
     *
     * @param action 预期抛异常的调用
     */
    private static void assertInvalidParam(Runnable action) {
        BizException ex = assertThrows(BizException.class, action::run);
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus());
    }
}
