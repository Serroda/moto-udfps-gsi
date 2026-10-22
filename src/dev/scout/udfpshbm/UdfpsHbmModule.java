package dev.scout.udfpshbm;

import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point.
 *
 * Primary path: hook FingerprintManager in SystemUI. UdfpsController calls
 * onPointerDown() immediately before asking system_server/HAL to start the
 * fingerprint capture, so enabling HBM here is early enough.
 *
 * FOD path: hook FingerprintAuthenticationClient in system_server, which is
 * exactly where the stock Motorola framework calls
 * MotBiometricFingerprint.notifyMotFingerprintHal() before
 * ISession.onPointerDownWithContext(). That JNI method is absent from a GSI, so
 * the module reproduces its IMotoFingerPrint.sendFodEvent() binder call.
 * Scope "System Framework" (package "android") for this part.
 *
 * The system_server hook also keeps the HBM fallback for devices where SELinux
 * does not let platform_app talk to the panel service.
 */
public final class UdfpsHbmModule implements IXposedHookLoadPackage {

    private static final String TAG = "ScoutUdfpsHbm";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String SYSTEM_SERVER_PACKAGE = "android";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": handleLoadPackage package="
                + lpparam.packageName + " process=" + lpparam.processName);
        if (SYSTEMUI_PACKAGE.equals(lpparam.packageName)) {
            hookSystemUi(lpparam);
        } else if (SYSTEM_SERVER_PACKAGE.equals(lpparam.packageName)) {
            /*
             * The system_server reports package "android" with process name
             * "system" on some builds and "android" on others. Do not filter
             * on processName: this package is only used by system_server.
             */
            hookSystemServer(lpparam);
        }
    }

    private void hookSystemUi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> fingerprintManager = XposedHelpers.findClass(
                    "android.hardware.fingerprint.FingerprintManager",
                    lpparam.classLoader);

            XposedBridge.hookAllMethods(fingerprintManager, "onPointerDown",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "SystemUI onPointerDown "
                                    + java.util.Arrays.toString(param.args));
                            sendFodDown(param);
                            enableHbmSafely();
                        }
                    });

            XposedBridge.hookAllMethods(fingerprintManager, "onPointerUp",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            MotoFingerprint.fingerUpSafely();
                            disableHbmSafely();
                        }
                    });
            MotoFingerprint.warmUp();
            PanelHbm.warmUp();
            XposedBridge.log(TAG
                    + ": SystemUI hooks installed (HBM); scope System Framework too for FOD");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook SystemUI");
            XposedBridge.log(t);
        }
    }

    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        hookProviderPointer(lpparam);
        try {
            Class<?> authenticationClient = XposedHelpers.findClass(
                    "com.android.server.biometrics.sensors.fingerprint.aidl."
                            + "FingerprintAuthenticationClient",
                    lpparam.classLoader);

            XposedBridge.hookAllMethods(authenticationClient, "onPointerDown",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            /*
                             * Stock order in this method:
                             *   1. MotBiometricFingerprint.notifyMotFingerprintHal()
                             *   2. ISession.onPointerDownWithContext()
                             *
                             * notifyMotFingerprintHal() is the missing JNI on
                             * a GSI. Reproduce it with the vendor AIDL call.
                             */
                            sendFodDown(param);
                            enableHbmSafely();
                        }
                    });

            XposedBridge.hookAllMethods(authenticationClient, "onPointerUp",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            MotoFingerprint.fingerUpSafely();
                            disableHbmSafely();
                        }
                    });

            PanelHbm.warmUp();
            MotoFingerprint.warmUp();
            XposedBridge.log(TAG + ": system_server hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook system_server");
            XposedBridge.log(t);
        }
    }

    /**
     * AOSP drops pointer events in FingerprintProvider when the vendor HAL
     * advertises SensorProps.halHandlesDisplayTouches. The Motorola Goodix HAL
     * sets that flag, but its own touch reader is not active under a GSI, so
     * enrollment never receives a finger-down. Re-dispatch the event to the
     * current Udfps client when the flag is set; when it is clear, the normal
     * provider path already dispatched it and doing it again would double the
     * events.
     */
    private void hookProviderPointer(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> provider = XposedHelpers.findClass(
                    "com.android.server.biometrics.sensors.fingerprint.aidl."
                            + "FingerprintProvider",
                    lpparam.classLoader);

            XposedBridge.hookAllMethods(provider, "onPointerDown", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    dispatchPointer(param, true);
                }
            });

            XposedBridge.hookAllMethods(provider, "onPointerUp", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    dispatchPointer(param, false);
                }
            });

            XposedBridge.log(TAG + ": FingerprintProvider pointer hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook FingerprintProvider");
            XposedBridge.log(t);
        }
    }

    private static void dispatchPointer(XC_MethodHook.MethodHookParam param,
            final boolean down) {
        try {
            if (param.args == null || param.args.length < 3) {
                return;
            }

            final long requestId = ((Number) param.args[0]).longValue();
            final int sensorId = ((Number) param.args[1]).intValue();
            final Object pointerContext = param.args[2];
            Object provider = param.thisObject;

            Object sensors = XposedHelpers.getObjectField(provider, "mFingerprintSensors");
            Object sensor = XposedHelpers.callMethod(sensors, "get", sensorId);
            if (sensor == null) {
                Log.w(TAG, "pointer: no sensor " + sensorId);
                return;
            }

            Object props = XposedHelpers.callMethod(sensor, "getSensorProperties");
            boolean halHandles = XposedHelpers.getBooleanField(props,
                    "halHandlesDisplayTouches");

            Log.i(TAG, "pointer " + (down ? "down" : "up")
                    + " requestId=" + requestId
                    + " sensor=" + sensorId
                    + " halHandlesDisplayTouches=" + halHandles);

            if (!halHandles) {
                // The provider already forwarded it; do not send it twice.
                return;
            }

            Object scheduler = XposedHelpers.callMethod(sensor, "getScheduler");
            if (scheduler == null) {
                return;
            }

            java.util.function.Consumer<Object> consumer =
                    new java.util.function.Consumer<Object>() {
                        @Override
                        public void accept(Object client) {
                            if (client == null) {
                                Log.w(TAG, "pointer " + (down ? "down" : "up")
                                        + " requestId=" + requestId
                                        + ": no matching client");
                                return;
                            }
                            /*
                             * Only enrollment needs the re-dispatch. Authentication
                             * is driven by the Goodix HAL's own touch detection and
                             * re-sending onPointerDown to the auth client duplicates
                             * FOD events and captures.
                             */
                            if (!client.getClass().getName()
                                    .contains("FingerprintEnrollClient")) {
                                return;
                            }
                            try {
                                Log.i(TAG, "pointer " + (down ? "down" : "up")
                                        + " -> " + client.getClass().getName());
                                XposedHelpers.callMethod(client,
                                        down ? "onPointerDown" : "onPointerUp",
                                        pointerContext);
                            } catch (Throwable t) {
                                Log.e(TAG, "pointer dispatch failed", t);
                                XposedBridge.log(t);
                            }
                        }
                    };

            Method method = scheduler.getClass().getMethod(
                    "getCurrentClientIfMatches", long.class,
                    java.util.function.Consumer.class);
            method.invoke(scheduler, requestId, consumer);
        } catch (Throwable t) {
            Log.e(TAG, "pointer hook failed", t);
            XposedBridge.log(t);
        }
    }

    /**
     * Mirrors the arguments that stock passes to notifyMotFingerprintHal():
     * onTapGesture = (mState == 12), isDeviceInteractive() and
     * isBiometricPrompt(). All reflection is best-effort; the normal unlock
     * case is interactive=true, which maps to the {3} flag vector.
     */
    private static void sendFodDown(XC_MethodHook.MethodHookParam param) {
        boolean interactive = true;
        boolean onTap = false;
        boolean biometricPrompt = false;

        try {
            Object value = XposedHelpers.callMethod(param.thisObject,
                    "isDeviceInteractive");
            if (value instanceof Boolean) {
                interactive = (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object value = XposedHelpers.callMethod(param.thisObject,
                    "isBiometricPrompt");
            if (value instanceof Boolean) {
                biometricPrompt = (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            onTap = XposedHelpers.getIntField(param.thisObject, "mState") == 12;
        } catch (Throwable ignored) {
        }

        MotoFingerprint.fingerDownSafely(interactive, onTap, biometricPrompt);
    }

    /*
     * Never let a module failure escape into the hooked method. A broken panel
     * call must not break fingerprint authentication itself.
     */
    private static void enableHbmSafely() {
        try {
            PanelHbm.on();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": enable HBM failed");
            XposedBridge.log(t);
        }
    }

    private static void disableHbmSafely() {
        try {
            PanelHbm.off();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": disable HBM failed");
            XposedBridge.log(t);
        }
    }
}
