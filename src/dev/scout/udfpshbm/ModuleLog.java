package dev.scout.udfpshbm;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Bridge to the libxposed log for the helper classes that are not part of the
 * module entry point.
 *
 * The Xposed interface is provided by the framework after the module entry is
 * loaded, so it is attached from onModuleLoaded().
 */
final class ModuleLog {

    private static final String TAG = "ScoutUdfpsHbm";

    private static volatile XposedInterface sXposed;

    private ModuleLog() {
    }

    static void attach(XposedInterface xposed) {
        sXposed = xposed;
    }

    static void log(Throwable throwable) {
        write(Log.ERROR, String.valueOf(throwable), throwable);
    }

    static void e(String message) {
        write(Log.ERROR, message, null);
    }

    static void e(String message, Throwable throwable) {
        write(Log.ERROR, message, throwable);
    }

    static void w(String message) {
        write(Log.WARN, message, null);
    }

    private static void write(int priority, String message, Throwable throwable) {
        XposedInterface xposed = sXposed;
        if (xposed == null) {
            return;
        }
        try {
            xposed.log(priority, TAG, message, throwable);
        } catch (Throwable ignored) {
            // Never let logging break the hooked method.
        }
    }
}
