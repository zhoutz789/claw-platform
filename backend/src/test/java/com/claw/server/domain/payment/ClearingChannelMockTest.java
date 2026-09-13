package com.claw.server.domain.payment;

import com.claw.server.common.enums.ClearingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClearingChannelMock} 单元测试（纯 JUnit，无 Docker/PG）。
 *
 * <p>覆盖 T06 验收点：通道号与设计文档词表一致、清分时机支持矩阵、可配置 {@code supported}
 * 打开/关闭 at source 能力（验证降级路径 B/C）、按收款方返回逐腿回执、调用流水可断言。
 */
class ClearingChannelMockTest {

    private ClearingChannelMock mock;

    @BeforeEach
    void setUp() {
        mock = new ClearingChannelMock();
        mock.reset();
    }

    private static ClearingChannelGateway.ChannelPayee payee(String type, String ref, String amount, int shard) {
        return new ClearingChannelGateway.ChannelPayee(type, ref, new BigDecimal(amount), shard);
    }

    @Test
    void channelCode_isAbaPayway() {
        assertEquals("ABA_PAYWAY", mock.channelCode());
        assertEquals(ClearingChannelMock.CHANNEL_CODE, mock.channelCode());
    }

    @Test
    void supports_atSourceAndOnArrival_butNotBatch() {
        assertTrue(mock.supports(ClearingMode.AT_SOURCE));
        assertTrue(mock.supports(ClearingMode.ON_ARRIVAL));
        assertFalse(mock.supports(ClearingMode.BATCH));
    }

    @Test
    void splitAtSource_returnsOneUniqueRefPerPayee_andRecordsInvocation() {
        List<ClearingChannelGateway.ChannelPayee> payees = List.of(
                payee("PLATFORM", null, "5.00", 1),
                payee("STATION", "ABA-STATION", "10.00", 1),
                payee("LOGISTICS", "ABA-LOGISTICS", "5.00", 1),
                payee("MANUFACTURER", "ABA-MFG", "80.00", 1));

        ClearingChannelGateway.SplitResult result =
                mock.splitAtSource("ORD-100", new BigDecimal("100.00"), payees, "USD");

        assertTrue(result.supported());
        assertEquals(4, result.institutionRefs().size());
        assertEquals(4, new HashSet<>(result.institutionRefs()).size(), "回执号必须逐腿唯一");

        assertEquals(1, mock.invocations().size());
        ClearingChannelMock.Invocation invocation = mock.invocations().get(0);
        assertEquals("splitAtSource", invocation.operation());
        assertEquals("ORD-100", invocation.ref());
        assertEquals(4, invocation.payees().size());
        assertEquals("USD", invocation.currency());
    }

    @Test
    void splitAtSource_returnsUnsupported_whenCapabilityDisabled() {
        mock.setSupported(false);

        assertFalse(mock.isSupported());
        assertFalse(mock.supports(ClearingMode.AT_SOURCE));

        ClearingChannelGateway.SplitResult result = mock.splitAtSource("ORD-1", new BigDecimal("100.00"),
                List.of(payee("MANUFACTURER", "ABA-MFG", "100.00", 1)), "USD");

        assertFalse(result.supported(), "关闭能力后必须返回 unsupported，由调用方降级走路径 B/C");
        assertTrue(result.institutionRefs().isEmpty());
    }

    @Test
    void payoutToPayee_returnsRef_andRecordsInvocation() {
        String ref = mock.payoutToPayee("CI-R1-1-AAAAAA", new BigDecimal("10.00"),
                "{\"bank\":\"ABA_BANK\"}", "USD");

        assertTrue(ref.startsWith("MOCK-ABA-"));
        assertEquals(1, mock.invocations().size());
        assertEquals("payoutToPayee", mock.invocations().get(0).operation());
        assertEquals("CI-R1-1-AAAAAA", mock.invocations().get(0).ref());
    }

    @Test
    void reset_clearsInvocationsAndSequence() {
        mock.splitAtSource("ORD-1", new BigDecimal("1.00"),
                List.of(payee("PLATFORM", null, "1.00", 1)), "USD");
        assertEquals(1, mock.invocations().size());

        mock.reset();

        assertTrue(mock.invocations().isEmpty());
        assertEquals("MOCK-ABA-1", mock.payoutToPayee("CI-1", new BigDecimal("1.00"), "{}", "USD"),
                "序号应重置，保证测试隔离");
    }
}
