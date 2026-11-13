package dev.scout.udfpshbm;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * libxposed (modern Xposed API) entry point.
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
 * Scope "system" (system_server) for this part.
 *
 * The system_server hook also keeps the HBM fallback for devices where SELinux
 * does not let platform_app talk to the panel service.
 */
public final class UdfpsHbmModule extends XposedModule {

    private static final String TAG = "ScoutUdfpsHbm";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private final AtomicBoolean mSystemUiHooked = new AtomicBoolean();
    private final AtomicBoolean mSystemServerHooked = new AtomicBoolean();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        ModuleLog.attach(this);
        log(Log.INFO, TAG, "module loaded package process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " framework=" + getFrameworkName()
                + " " + getFrameworkVersionCode()
                + " api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        log(Log.INFO, TAG, "package loaded package=" + param.getPackageName());
        if (SYSTEMUI_PACKAGE.equals(param.getPackageName())) {
            hookSystemUi(param);
        }
    }

    /*
     * In system_server the first package callback is replaced by
     * onSystemServerStarting(), so "android" never arrives through
     * onPackageLoaded(). The scope entry for system_server is the special
     * virtual package "system".
     */
    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        if (!mSystemServerHooked.compareAndSet(false, true)) {
            return;
        }
        hookSystemServer(param.getClassLoader());
    }

    private void hookSystemUi(PackageLoadedParam param) {
        if (!mSystemUiHooked.compareAndSet(false, true)) {
            return;
        }
        try {
            ClassLoader classLoader = param.getDefaultClassLoader();
            Class<?> fingerprintManager = Reflect.findClass(
                    "android.hardware.fingerprint.FingerprintManager",
                    classLoader);

            hookAllMethods(fingerprintManager, "onPointerDown", new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Log.i(TAG, "SystemUI onPointerDown " + chain.getArgs());
                    sendFodDown(chain);
                    enableHbmSafely();
                    return chain.proceed();
                }
            });

            hookAllMethods(fingerprintManager, "onPointerUp", new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    MotoFingerprint.fingerUpSafely();
                    disableHbmSafely();
                    return chain.proceed();
                }
            });
            MotoFingerprint.warmUp();
            PanelHbm.warmUp();
            log(Log.INFO, TAG,
                    ": SystemUI hooks installed (HBM); scope System Framework too for FOD");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, ": failed to hook SystemUI", t);
        }
    }

    private void hookSystemServer(ClassLoader classLoader) {
        hookProviderPointer(classLoader);
        try {
            Class<?> authenticationClient = Reflect.findClass(
                    "com.android.server.biometrics.sensors.fingerprint.aidl."
                            + "FingerprintAuthenticationClient",
                    classLoader);

            hookAllMethods(authenticationClient, "onPointerDown", new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    /*
                     * Stock order in this method:
                     *   1. MotBiometricFingerprint.notifyMotFingerprintHal()
                     *   2. ISession.onPointerDownWithContext()
                     *
                     * notifyMotFingerprintHal() is the missing JNI on
                     * a GSI. Reproduce it with the vendor AIDL call.
                     */
                    sendFodDown(chain);
                    enableHbmSafely();
                    return chain.proceed();
                }
            });

            hookAllMethods(authenticationClient, "onPointerUp", new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    MotoFingerprint.fingerUpSafely();
                    disableHbmSafely();
                    return chain.proceed();
                }
            });

            PanelHbm.warmUp();
            MotoFingerprint.warmUp();
            log(Log.INFO, TAG, ": system_server hooks installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, ": failed to hook system_server", t);
        }
    }

    /**
     * AOSP drops pointer events in FingerprintProvider when the vendor HAL
     * advertises SensorProps.halHandlesDisplayTouches. The Motorola Goodix HAL
     * sets that flag, but its own touch reader is not enough under a GSI, so
     * authentication and enrollment never receive a finger-down. Re-dispatch
     * the event to the current authentication or enrollment client when the
     * flag is set; when it is clear, the normal provider path already
     * dispatched it and doing it again would double the events.
     */
    private void hookProviderPointer(ClassLoader classLoader) {
        try {
            Class<?> provider = Reflect.findClass(
                    "com.android.server.biometrics.sensors.fingerprint.aidl."
                            + "FingerprintProvider",
                    classLoader);

            hookAllMethods(provider, "onPointerDown", new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    try {
                        return chain.proceed();
                    } finally {
                        dispatchPointer(chain, true);
                    }
                }
            });

            hookAllMethods(provider, "onPointerUp", new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    try {
                        return chain.proceed();
                    } finally {
                        dispatchPointer(chain, false);
                    }
                }
            });

            log(Log.INFO, TAG, ": FingerprintProvider pointer hooks installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, ": failed to hook FingerprintProvider", t);
        }
    }

    /**
     * Hooks every method with the given name in the class and its superclasses,
     * matching the behaviour of the legacy XposedBridge.hookAllMethods(). The
     * legacy API is not available to modules targeting libxposed API 102.
     */
    private void hookAllMethods(Class<?> hookClass, String methodName, Hooker hooker) {
        int count = 0;
        for (Class<?> clazz = hookClass;
                clazz != null && clazz != Object.class;
                clazz = clazz.getSuperclass()) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(hooker);
                count++;
            }
        }
        log(Log.DEBUG, TAG, ": hooked " + count + " method(s) " + hookClass.getName()
                + "." + methodName);
    }

    private static void dispatchPointer(Chain chain, final boolean down) {
        try {
            if (chain.getArgs().size() < 3) {
                return;
            }

            final long requestId = ((Number) chain.getArg(0)).longValue();
            final int sensorId = ((Number) chain.getArg(1)).intValue();
            final Object pointerContext = chain.getArg(2);
            Object provider = chain.getThisObject();

            Object sensors = Reflect.getObjectField(provider, "mFingerprintSensors");
            Object sensor = Reflect.callMethod(sensors, "get", sensorId);
            if (sensor == null) {
                Log.w(TAG, "pointer: no sensor " + sensorId);
                return;
            }

            Object props = Reflect.callMethod(sensor, "getSensorProperties");
            boolean halHandles = Reflect.getBooleanField(props,
                    "halHandlesDisplayTouches");

            Log.i(TAG, "pointer " + (down ? "down" : "up")
                    + " requestId=" + requestId
                    + " sensor=" + sensorId
                    + " halHandlesDisplayTouches=" + halHandles);

            if (!halHandles) {
                // The provider already forwarded it; do not send it twice.
                return;
            }

            Object scheduler = Reflect.callMethod(sensor, "getScheduler");
            if (scheduler == null) {
                return;
            }

            Consumer<Object> consumer = new Consumer<Object>() {
                @Override
                public void accept(Object client) {
                    if (client == null) {
                        Log.w(TAG, "pointer " + (down ? "down" : "up")
                                + " requestId=" + requestId
                                + ": no matching client");
                        return;
                    }
                    /*
                     * Both authentication and enrollment need the
                     * re-dispatch on this device. The Goodix HAL's own
                     * touch reader alone is not enough: authentication
                     * also needs onPointerDownWithContext to start
                     * capturing. Limit the re-dispatch to the two AIDL
                     * clients so unrelated clients are never called.
                     */
                    String name = client.getClass().getName();
                    boolean isAuth = name.contains(
                            "FingerprintAuthenticationClient");
                    boolean isEnroll = name.contains(
                            "FingerprintEnrollClient");
                    if (!isAuth && !isEnroll) {
                        return;
                    }
                    try {
                        Log.i(TAG, "pointer " + (down ? "down" : "up")
                                + " -> " + client.getClass().getName());
                        Reflect.callMethod(client,
                                down ? "onPointerDown" : "onPointerUp",
                                pointerContext);
                    } catch (Throwable t) {
                        Log.e(TAG, "pointer dispatch failed", t);
                        ModuleLog.log(t);
                    }
                }
            };

            Reflect.callMethod(scheduler, "getCurrentClientIfMatches",
                    requestId, consumer);
        } catch (Throwable t) {
            Log.e(TAG, "pointer hook failed", t);
            ModuleLog.log(t);
        }
    }

    /**
     * Mirrors the arguments that stock passes to notifyMotFingerprintHal():
     * onTapGesture = (mState == 12), isDeviceInteractive() and
     * isBiometricPrompt(). All reflection is best-effort; the normal unlock
     * case is interactive=true, which maps to the {3} flag vector.
     */
    private static void sendFodDown(Chain chain) {
        boolean interactive = true;
        boolean onTap = false;
        boolean biometricPrompt = false;
        Object client = chain.getThisObject();

        try {
            Object value = Reflect.callMethod(client, "isDeviceInteractive");
            if (value instanceof Boolean) {
                interactive = (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object value = Reflect.callMethod(client, "isBiometricPrompt");
            if (value instanceof Boolean) {
                biometricPrompt = (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            onTap = Reflect.getIntField(client, "mState") == 12;
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
            ModuleLog.e("enable HBM failed", t);
        }
    }

    private static void disableHbmSafely() {
        try {
            PanelHbm.off();
        } catch (Throwable t) {
            ModuleLog.e("disable HBM failed", t);
        }
    }
}
