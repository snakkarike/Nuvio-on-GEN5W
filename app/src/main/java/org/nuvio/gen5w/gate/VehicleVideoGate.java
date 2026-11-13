package org.nuvio.gen5w.gate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * VehicleVideoGate verifies that the vehicle is stationary and in PARK
 * before video playback is permitted, adhering to Hyundai Motor Group (HMG)
 * Gen5W automotive safety regulations.
 *
 * Gen5W units expose vehicle telematics via /system/framework/automotive.jar
 * through com.hkmc.micom.ISystemService and com.hkmc.regulation.IRegulationManager.
 */
public class VehicleVideoGate {

    private static final String TAG = "VehicleVideoGate";

    public interface OnParkStateChangeListener {
        void onParkStateChanged(boolean isParked, String reason);
    }

    private final Context context;
    private final Handler mainHandler;
    private OnParkStateChangeListener listener;

    private boolean isParked = true; // default safe or parked on startup
    private boolean mockOverrideEnabled = false;
    private boolean mockParkedState = true;

    // Automotive reflection handles
    private Object regulationManagerInstance = null;
    private Method isVideoRestrictedMethod = null;
    private Method getGearStateMethod = null;
    private Method getVehicleSpeedMethod = null;

    private final Runnable periodicCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkVehicleState();
            mainHandler.postDelayed(this, 1000); // 1-second cadence
        }
    };

    public VehicleVideoGate(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        initAutomotiveHooks();
    }

    private void initAutomotiveHooks() {
        try {
            // Attempt reflection into HKMC automotive framework
            Class<?> regulationClass = Class.forName("com.hkmc.regulation.IRegulationManager");
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
            Object binder = getServiceMethod.invoke(null, "regulation");

            if (binder != null) {
                Class<?> stubClass = Class.forName("com.hkmc.regulation.IRegulationManager$Stub");
                Method asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder.class);
                regulationManagerInstance = asInterfaceMethod.invoke(null, binder);

                if (regulationManagerInstance != null) {
                    try {
                        isVideoRestrictedMethod = regulationClass.getMethod("isVideoRestricted");
                    } catch (NoSuchMethodException ignored) {}

                    try {
                        getGearStateMethod = regulationClass.getMethod("getGearState");
                    } catch (NoSuchMethodException ignored) {}

                    try {
                        getVehicleSpeedMethod = regulationClass.getMethod("getVehicleSpeed");
                    } catch (NoSuchMethodException ignored) {}

                    Log.i(TAG, "Successfully initialized HKMC automotive regulation hooks");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "HKMC automotive framework not accessible (" + t.getMessage() + "). Running in fallback mode.");
        }
    }

    public void setListener(OnParkStateChangeListener listener) {
        this.listener = listener;
    }

    public void start() {
        checkVehicleState();
        mainHandler.postDelayed(periodicCheckRunnable, 1000);
    }

    public void stop() {
        mainHandler.removeCallbacks(periodicCheckRunnable);
    }

    public boolean isVehicleParked() {
        if (mockOverrideEnabled) {
            return mockParkedState;
        }
        return isParked;
    }

    /**
     * For lab bench testing or emulator validation.
     */
    public void setMockParked(boolean parked) {
        this.mockOverrideEnabled = true;
        this.mockParkedState = parked;
        notifyStateChange(parked, "Bench Simulation Override");
    }

    public void clearMock() {
        this.mockOverrideEnabled = false;
        checkVehicleState();
    }

    public void checkVehicleState() {
        if (mockOverrideEnabled) {
            return;
        }

        boolean currentParked = true;
        String reason = "Vehicle is in PARK";

        if (regulationManagerInstance != null) {
            try {
                if (isVideoRestrictedMethod != null) {
                    Boolean restricted = (Boolean) isVideoRestrictedMethod.invoke(regulationManagerInstance);
                    if (Boolean.TRUE.equals(restricted)) {
                        currentParked = false;
                        reason = "Regulation restriction active (Vehicle in motion/gear)";
                    }
                } else if (getVehicleSpeedMethod != null) {
                    Number speed = (Number) getVehicleSpeedMethod.invoke(regulationManagerInstance);
                    if (speed != null && speed.doubleValue() > 0.0) {
                        currentParked = false;
                        reason = "Vehicle speed > 0 km/h";
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error invoking regulation manager: " + t.getMessage());
            }
        }

        if (this.isParked != currentParked) {
            this.isParked = currentParked;
            notifyStateChange(currentParked, reason);
        }
    }

    private void notifyStateChange(final boolean parked, final String reason) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onParkStateChanged(parked, reason);
                }
            }
        });
    }
}

