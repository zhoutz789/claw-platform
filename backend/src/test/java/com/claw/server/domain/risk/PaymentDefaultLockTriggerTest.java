package com.claw.server.domain.risk;

import com.claw.server.domain.asset.VehicleCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentDefaultLockTriggerTest {

    @Mock
    private VehicleCommandService vehicleCommandService;

    @InjectMocks
    private PaymentDefaultLockTrigger trigger;

    @Test
    void onPaymentDefault_delegatesToVehicleLock() {
        trigger.onPaymentDefault(7L);
        verify(vehicleCommandService).lockForPaymentDefault(7L);
    }

    @Test
    void riskMonitorService_entrypoint_delegatesToTrigger() {
        PaymentDefaultLockTrigger trigger = mock(PaymentDefaultLockTrigger.class);
        RiskMonitorService risk = new RiskMonitorService(null, null, trigger);
        risk.triggerVehiclePaymentDefaultLock(7L);
        verify(trigger).onPaymentDefault(7L);
    }
}
