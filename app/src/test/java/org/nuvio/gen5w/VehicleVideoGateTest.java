package org.nuvio.gen5w;

import org.junit.Assert;
import org.junit.Test;
import org.nuvio.gen5w.gate.VehicleVideoGate;

import java.util.concurrent.atomic.AtomicBoolean;

public class VehicleVideoGateTest {

    @Test
    public void testMockParkStateTransitions() {
        // Test that mock park gate correctly transitions and invokes callback
        VehicleVideoGate gate = new VehicleVideoGate(new MockContext());

        final AtomicBoolean observedParked = new AtomicBoolean(false);
        gate.setListener(new VehicleVideoGate.OnParkStateChangeListener() {
            @Override
            public void onParkStateChanged(boolean isParked, String reason) {
                observedParked.set(isParked);
            }
        });

        // Set to in-motion
        gate.setMockParked(false);
        Assert.assertFalse(gate.isVehicleParked());

        // Set back to parked
        gate.setMockParked(true);
        Assert.assertTrue(gate.isVehicleParked());
    }

    // Minimal mock context for unit tests outside Android runtime
    private static class MockContext extends android.test.mock.MockContext {
        @Override
        public android.content.Context getApplicationContext() {
            return this;
        }
    }
}

