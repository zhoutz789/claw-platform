package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AssetStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资产状态机流转校验（纯逻辑，无 Spring / 无 DB）。
 */
class AssetStateMachineTest {

    @Test
    void inStock_can_go_inUse() {
        assertDoesNotThrow(() -> AssetStateMachine.assertTransition(AssetStatus.IN_STOCK, AssetStatus.IN_USE));
    }

    @Test
    void inUse_can_go_shared() {
        assertDoesNotThrow(() -> AssetStateMachine.assertTransition(AssetStatus.IN_USE, AssetStatus.SHARED));
    }

    @Test
    void shared_can_return_inUse() {
        assertDoesNotThrow(() -> AssetStateMachine.assertTransition(AssetStatus.SHARED, AssetStatus.IN_USE));
    }

    @Test
    void scrap_is_terminal() {
        assertThrows(BizException.class,
                () -> AssetStateMachine.assertTransition(AssetStatus.SCRAPPED, AssetStatus.IN_STOCK));
    }

    @Test
    void inStock_cannot_skip_to_shared() {
        assertThrows(BizException.class,
                () -> AssetStateMachine.assertTransition(AssetStatus.IN_STOCK, AssetStatus.SHARED));
    }

    @Test
    void same_status_is_noop() {
        assertDoesNotThrow(() -> AssetStateMachine.assertTransition(AssetStatus.IN_USE, AssetStatus.IN_USE));
    }

    @Test
    void repair_can_go_scrapped() {
        assertTrue(AssetStateMachine.canTransition(AssetStatus.REPAIR, AssetStatus.SCRAPPED));
    }
}
