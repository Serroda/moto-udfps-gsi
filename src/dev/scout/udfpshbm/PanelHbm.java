package dev.scout.udfpshbm;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/**
 * Client for Motorola's IDisplayPanel AIDL service.
 *
 * IDisplayPanel v1, transaction 5 (setMode):
 *   in : int32 mode
 *   out: AStatus header + int32 result
 *
 * Mode 4 = PANEL_MODE_HIGH_BRIGHT_FOD, mode 0 = PANEL_MODE_NORMAL.
 */
final class PanelHbm {

    private static final String TAG = "ScoutUdfpsHbm";
    private static final String INSTANCE =
            "com.motorola.hardware.display.panel.IDisplayPanel/default";
    private static final String DESCRIPTOR =
            "com.motorola.hardware.display.panel.IDisplayPanel";
    private static final int TX_SET_MODE = 5;

    /*
     * The stock Motorola proxy calls AIBinder_transact(..., 0x10000000).
     * That is IBinder::FLAG_PRIVATE_VENDOR. It is not required by the kernel,
     * but matching the vendor proxy avoids surprises on vendor services.
     */
    private static final int TX_FLAGS = 0x10000000;

    private static final int MODE_NORMAL = 0;
    private static final int MODE_HIGH_BRIGHT_FOD = 4;

    private static final long SAFETY_TIMEOUT_MS = 5000;

    private static final Object LOCK = new Object();
    private static int sGeneration;
    private static IBinder sBinder;
    private static Method sGetService;
    private static Handler sTimeoutHandler;
    private static boolean sMissingLogged;

    static {
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/os/ServiceManager;",
                    "Landroid/os/ServiceManager$1;");
        } catch (Throwable t) {
            // Not fatal: LSPosed may already have disabled hidden API checks.
            Log.w(TAG, "HiddenApiBypass unavailable", t);
        }
    }

    private PanelHbm() {
    }

    /**
     * Forces static initialization (the HiddenApiBypass setup) while LSPosed is
     * loading the module, instead of on the first finger-down event.
     */
    static void warmUp() {
        Log.d(TAG, "HBM client ready");
    }

    /** Enable HBM. Synchronous on purpose: must happen before the HAL capture. */
    static void on() {
        synchronized (LOCK) {
            sGeneration++;
            final int generation = sGeneration;

            setModeLocked(MODE_HIGH_BRIGHT_FOD);

            Handler handler = timeoutHandler();
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                synchronized (LOCK) {
                    if (sGeneration == generation) {
                        Log.w(TAG, "HBM safety timeout");
                        setModeLocked(MODE_NORMAL);
                        sGeneration++;
                    }
                }
            }, SAFETY_TIMEOUT_MS);
        }
    }

    /** Disable HBM. */
    static void off() {
        synchronized (LOCK) {
            sGeneration++;
            timeoutHandler().removeCallbacksAndMessages(null);
            setModeLocked(MODE_NORMAL);
        }
    }

    private static Handler timeoutHandler() {
        if (sTimeoutHandler == null) {
            HandlerThread thread = new HandlerThread("scout-udfps-hbm");
            thread.start();
            sTimeoutHandler = new Handler(thread.getLooper());
        }
        return sTimeoutHandler;
    }

    private static IBinder serviceLocked() {
        if (sBinder != null && sBinder.isBinderAlive()) {
            return sBinder;
        }

        try {
            if (sGetService == null) {
                Class<?> serviceManager = Class.forName("android.os.ServiceManager");
                sGetService = serviceManager.getMethod("getService", String.class);
            }
            sBinder = (IBinder) sGetService.invoke(null, INSTANCE);
            if (sBinder == null && !sMissingLogged) {
                sMissingLogged = true;
                Log.e(TAG, "Panel service not found: " + INSTANCE);
                XposedBridge.log(TAG + ": panel service not found");
            }
        } catch (Throwable t) {
            sBinder = null;
            Log.e(TAG, "Cannot get panel service", t);
            XposedBridge.log(t);
        }
        return sBinder;
    }

    /**
     * Must be called with LOCK held. The binder call blocks SystemUI's main
     * thread for a few milliseconds, exactly like the stock HBM path does.
     */
    private static void setModeLocked(int mode) {
        IBinder binder = serviceLocked();
        if (binder == null) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(mode);

            if (!binder.transact(TX_SET_MODE, data, reply, TX_FLAGS)) {
                Log.e(TAG, "setMode(" + mode + "): transact returned false");
                return;
            }

            reply.readException();
            int result = reply.readInt();
            if (result == 0) {
                Log.i(TAG, "setMode(" + mode + ") -> " + result);
            } else {
                Log.w(TAG, "setMode(" + mode + ") -> " + result);
            }
        } catch (Throwable t) {
            // DeadObjectException, SecurityException, ParcelableException, ...
            Log.e(TAG, "setMode(" + mode + ") failed", t);
            sBinder = null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
