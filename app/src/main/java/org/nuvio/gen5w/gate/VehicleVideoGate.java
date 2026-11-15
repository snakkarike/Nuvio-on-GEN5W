package org.nuvio.gen5w.gate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

/**
 * VehicleVideoGate verifies that the vehicle is stationary and in PARK
 * before video playback is permitted, adhering to Hyundai Motor Group (HMG)
 * Gen5W automotive safety regulations.
 *
 * Telemetry sources on Gen5W:
 * 1. /system/framework/automotive.jar:
 *    - com.hkmc.micom.ISystemService -> getGearPStatus() (1 = Park)
 *    - com.hkmc.regulation.IRegulationManager -> getStatus() (0 = Unrestricted)
 * 2. /system/framework/com.hkmc.ext.hcan.jar:
 *    - com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService ->
 *        getCarSpeed((byte) 0) (CAN wheel speed in km/h)
 *        IsCarMoving() (CAN movement flag)
 *
 * Implements a fail-closed policy with a 1500ms safe debounce to prevent
 * playback stutter during stop-and-go maneuvers.
 */
public class VehicleVideoGate {

    private static final String TAG = "VehicleVideoGate";

    public static final String AUTOMOTIVE_JAR = "/system/framework/automotive.jar";
    public static final String HCAN_JAR = "/system/framework/com.hkmc.ext.hcan.jar";
    private static final String PATH_CLASS_LOADER = "dalvik.system.PathClassLoader";
    private static final String SERVICE_MANAGER = "android.os.ServiceManager";
    private static final String IBINDER = "android.os.IBinder";

    private static final long SAFE_DEBOUNCE_MS = 1500L;
    private static final double MAX_STATIONARY_SPEED_KMH = 0.5d;

    public interface OnParkStateChangeListener {
        void onParkStateChanged(boolean isParked, String reason);
    }

    private final Context context;
    private final Handler mainHandler;
    private OnParkStateChangeListener listener;

    private boolean isParked = true;
    private String lastReason = "Initialized";
    private boolean mockOverrideEnabled = false;
    private boolean mockParkedState = true;

    private boolean automotiveFrameworkPresent = false;
    private boolean hooksInitialized = false;

    // Reflection objects
    private Object systemServiceInstance = null;
    private Method getGearPStatusMethod = null;

    private Object hscanServiceInstance = null;
    private Method getCarSpeedMethod = null;
    private Method isCarMovingMethod = null;

    private Object regulationManagerInstance = null;
    private Method getRegulationStatusMethod = null;

    // Debounce tracking
    private long safeCandidateTimestamp = 0;
    private boolean candidateIsSafe = false;

    // Cached telemetry status for UI display
    private String lastTelemetrySummary = "Initializing telemetry...";

    private final Runnable periodicCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkVehicleState();
            mainHandler.postDelayed(this, 500); // 500ms evaluation cadence
        }
    };

    public VehicleVideoGate(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        initAutomotiveHooks();
    }

    private void initAutomotiveHooks() {
        File autoJar = new File(AUTOMOTIVE_JAR);
        if (!autoJar.canRead()) {
            automotiveFrameworkPresent = false;
            if (isEmulatorOrTestDevice()) {
                Log.i(TAG, "Non-automotive hardware / Emulator detected. Vehicle gate defaulting to ALLOWED.");
            } else {
                Log.i(TAG, "Automotive jar not found. Defaulting to ALLOWED for bench testing.");
            }
            this.isParked = true;
            this.lastReason = "Bench / Non-automotive hardware";
            this.lastTelemetrySummary = "Platform: Non-automotive (Bypass)";
            return;
        }

        automotiveFrameworkPresent = true;
        try {
            ClassLoader parent = getClass().getClassLoader();
            Class<?> pclClass = Class.forName(PATH_CLASS_LOADER);
            ClassLoader autoLoader = (ClassLoader) pclClass
                    .getConstructor(String.class, ClassLoader.class)
                    .newInstance(AUTOMOTIVE_JAR, parent);

            ClassLoader hscanLoader = autoLoader;
            File hscanFile = new File(HCAN_JAR);
            if (hscanFile.canRead()) {
                try {
                    hscanLoader = (ClassLoader) pclClass
                            .getConstructor(String.class, ClassLoader.class)
                            .newInstance(HCAN_JAR, autoLoader);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to load " + HCAN_JAR + ": " + t.getMessage());
                }
            }

            Class<?> binderClass = Class.forName(IBINDER);
            Class<?> serviceManagerClass = Class.forName(SERVICE_MANAGER);
            Method getService = serviceManagerClass.getMethod("getService", String.class);

            // 1. HKMC Micom System Service (Gear P)
            try {
                String sysConst = (String) autoLoader.loadClass("com.hkmc.Service")
                        .getField("SYSTEM_SERVICE")
                        .get(null);
                Object sysBinder = getService.invoke(null, sysConst);
                if (sysBinder != null && Boolean.TRUE.equals(binderClass.getMethod("isBinderAlive").invoke(sysBinder))) {
                    systemServiceInstance = autoLoader.loadClass("com.hkmc.micom.ISystemService$Stub")
                            .getMethod("asInterface", binderClass)
                            .invoke(null, sysBinder);
                    if (systemServiceInstance != null) {
                        getGearPStatusMethod = autoLoader.loadClass("com.hkmc.micom.ISystemService")
                                .getMethod("getGearPStatus");
                        Log.i(TAG, "Bound to HKMC ISystemService (getGearPStatus)");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not bind ISystemService: " + t.getMessage());
            }

            // 2. HKMC HSCAN Telematics Service (Car Speed & IsCarMoving)
            try {
                String hscanConst = (String) hscanLoader.loadClass("com.hkmc.telematics.hscan.tm_interface.TM_HSCANAdapter")
                        .getField("TM_HSCAN_SERVICE")
                        .get(null);
                Object hscanBinder = getService.invoke(null, hscanConst);
                if (hscanBinder != null && Boolean.TRUE.equals(binderClass.getMethod("isBinderAlive").invoke(hscanBinder))) {
                    hscanServiceInstance = hscanLoader.loadClass("com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService$Stub")
                            .getMethod("asInterface", binderClass)
                            .invoke(null, hscanBinder);
                    if (hscanServiceInstance != null) {
                        Class<?> hscanClass = hscanLoader.loadClass("com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService");
                        getCarSpeedMethod = hscanClass.getMethod("getCarSpeed", byte.class);
                        isCarMovingMethod = hscanClass.getMethod("IsCarMoving");
                        Log.i(TAG, "Bound to HKMC ITM_HSCANService (getCarSpeed, IsCarMoving)");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not bind ITM_HSCANService: " + t.getMessage());
            }

            // 3. HKMC Regulation Service (Driving Restriction Status)
            try {
                String regConst = (String) autoLoader.loadClass("com.hkmc.Service")
                        .getField("REGULATION_SERVICE")
                        .get(null);
                Object regBinder = getService.invoke(null, regConst);
                if (regBinder != null && Boolean.TRUE.equals(binderClass.getMethod("isBinderAlive").invoke(regBinder))) {
                    regulationManagerInstance = autoLoader.loadClass("com.hkmc.regulation.IRegulationManager$Stub")
                            .getMethod("asInterface", binderClass)
                            .invoke(null, regBinder);
                    if (regulationManagerInstance != null) {
                        getRegulationStatusMethod = autoLoader.loadClass("com.hkmc.regulation.IRegulationManager")
                                .getMethod("getStatus");
                        Log.i(TAG, "Bound to HKMC IRegulationManager (getStatus)");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not bind IRegulationManager: " + t.getMessage());
            }

            hooksInitialized = (systemServiceInstance != null
                    || hscanServiceInstance != null
                    || regulationManagerInstance != null);

            Log.i(TAG, "Automotive hooks initialization result: " + hooksInitialized);
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error initializing automotive hooks", t);
        }
    }

    private boolean isEmulatorOrTestDevice() {
        String fingerprint = android.os.Build.FINGERPRINT != null ? android.os.Build.FINGERPRINT : "";
        String model = android.os.Build.MODEL != null ? android.os.Build.MODEL : "";
        String hardware = android.os.Build.HARDWARE != null ? android.os.Build.HARDWARE : "";
        String product = android.os.Build.PRODUCT != null ? android.os.Build.PRODUCT : "";

        return fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || model.contains("google_sdk")
                || model.contains("Emulator")
                || model.contains("Android SDK built for x86")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || product.contains("sdk")
                || product.contains("google_sdk")
                || product.contains("sdk_x86")
                || product.contains("vbox86p");
    }

    public void setListener(OnParkStateChangeListener listener) {
        this.listener = listener;
    }

    public void start() {
        checkVehicleState();
        mainHandler.removeCallbacks(periodicCheckRunnable);
        mainHandler.postDelayed(periodicCheckRunnable, 500);
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

    public String getLastReason() {
        return lastReason;
    }

    public String getTelemetryStatus() {
        return lastTelemetrySummary;
    }

    public void setMockParked(boolean parked) {
        this.mockOverrideEnabled = true;
        this.mockParkedState = parked;
        notifyStateChange(parked, "Bench Simulation Override");
    }

    public void clearMock() {
        this.mockOverrideEnabled = false;
        checkVehicleState();
    }

    public synchronized void checkVehicleState() {
        if (mockOverrideEnabled) {
            return;
        }

        if (!automotiveFrameworkPresent || !hooksInitialized) {
            // Non-automotive hardware
            if (!this.isParked) {
                this.isParked = true;
                this.lastReason = "Bench / Non-automotive hardware";
                notifyStateChange(true, this.lastReason);
            }
            return;
        }

        Integer gearP = null;
        Float speedKmh = null;
        Boolean moving = null;
        Integer regStatus = null;

        // Poll Micom Gear P
        if (systemServiceInstance != null && getGearPStatusMethod != null) {
            try {
                gearP = (Integer) getGearPStatusMethod.invoke(systemServiceInstance);
            } catch (Throwable t) {
                Log.w(TAG, "Error invoking getGearPStatus: " + t.getMessage());
            }
        }

        // Poll HSCAN Speed & Movement
        if (hscanServiceInstance != null) {
            if (getCarSpeedMethod != null) {
                try {
                    speedKmh = (Float) getCarSpeedMethod.invoke(hscanServiceInstance, (byte) 0);
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking getCarSpeed: " + t.getMessage());
                }
            }
            if (isCarMovingMethod != null) {
                try {
                    moving = (Boolean) isCarMovingMethod.invoke(hscanServiceInstance);
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking IsCarMoving: " + t.getMessage());
                }
            }
        }

        // Poll Regulation Manager
        if (regulationManagerInstance != null && getRegulationStatusMethod != null) {
            try {
                regStatus = (Integer) getRegulationStatusMethod.invoke(regulationManagerInstance);
            } catch (Throwable t) {
                Log.w(TAG, "Error invoking getRegulationStatus: " + t.getMessage());
            }
        }

        // Format telemetry summary for diagnostics
        StringBuilder sb = new StringBuilder();
        sb.append("Gear: ").append(gearP != null ? (gearP == 1 ? "PARK (1)" : "IN GEAR (" + gearP + ")") : "N/A");
        sb.append(" • Spd: ").append(speedKmh != null ? String.format(java.util.Locale.US, "%.1f km/h", speedKmh) : "N/A");
        sb.append(" • Mov: ").append(moving != null ? (moving ? "YES" : "NO") : "N/A");
        sb.append(" • Reg: ").append(regStatus != null ? (regStatus == 0 ? "OK (0)" : "RESTRICTED (" + regStatus + ")") : "N/A");
        lastTelemetrySummary = sb.toString();

        // Evaluate Safety Policy (Fail-Closed)
        boolean sampleSafe = true;
        String reason = "Vehicle is parked";

        if (gearP != null && gearP != 1) {
            sampleSafe = false;
            reason = "Vehicle shifted out of PARK (Gear: " + gearP + ")";
        } else if (moving != null && Boolean.TRUE.equals(moving)) {
            sampleSafe = false;
            reason = "Vehicle CAN bus reports movement";
        } else if (speedKmh != null && Math.abs(speedKmh) > MAX_STATIONARY_SPEED_KMH) {
            sampleSafe = false;
            reason = String.format(java.util.Locale.US, "Vehicle speed > 0.5 km/h (%.1f km/h)", speedKmh);
        } else if (regStatus != null && regStatus != 0) {
            sampleSafe = false;
            reason = "Automotive regulation restriction active (status: " + regStatus + ")";
        }

        long now = SystemClock.elapsedRealtime();

        if (!sampleSafe) {
            // Immediate trip on any unsafe signal!
            candidateIsSafe = false;
            safeCandidateTimestamp = 0;
            if (this.isParked) {
                this.isParked = false;
                this.lastReason = reason;
                notifyStateChange(false, reason);
            }
        } else {
            // Unsafe -> Safe requires 1500ms debounce
            if (!candidateIsSafe) {
                candidateIsSafe = true;
                safeCandidateTimestamp = now;
            } else {
                if (now - safeCandidateTimestamp >= SAFE_DEBOUNCE_MS) {
                    if (!this.isParked) {
                        this.isParked = true;
                        this.lastReason = "Vehicle stationary in PARK";
                        notifyStateChange(true, this.lastReason);
                    }
                }
            }
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
