package dev.scout.udfpshbm;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/**
 * Client for Motorola's IMotoFingerPrint AIDL service.
 *
 * The stock Motorola framework calls
 *
 *   MotBiometricFingerprint.notifyMotFingerprintHal(event, state)
 *
 * immediately before asking the fingerprint HAL to start a capture. That JNI
 * method lives in Motorola's libandroid_servers.so and is absent on a GSI, so
 * the vendor Goodix HAL never receives the Motorola FOD event. The HAL can
 * then miss the touch even though the panel HBM is already on.
 *
 * The stock implementation does NOT use the generic sendCommand() transaction
 * for this. It calls IMotoFingerPrint.sendFodEvent(), which the vendor HAL
 * translates internally to goodix command 0x600 + event:
 *
 *   event 0 = FINGER_DOWN  -> cmd 0x600
 *   event 1 = FINGER_UP    -> cmd 0x601
 *   event 2 = CANCEL       -> no command (vendor HAL only logs it)
 *
 * AIDL interface (version 2, NDK backend):
 *   instance:   com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint/default
 *   descriptor: com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint
 *   sendFodEvent = transaction 4
 *     in : int32 eventType, byte[] optionalData
 *     out: AStatus header + int32 result
 *   sendCommand  = transaction 6 (not used by stock for FOD)
 *     in : int32 cmdId, byte[] data
 *     out: AStatus header + int32 result
 *
 * The stock framework uses an optional byte vector. For a normal interactive
 * finger-down it sends [0x03]; for finger-up it sends an engaged empty vector.
 * Do not send a null array: the vendor implementation dereferences the optional.
 */
final class MotoFingerprint {

    private static final String TAG = "ScoutUdfpsHbm";
    private static final String INSTANCE =
            "com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint/default";
    private static final String DESCRIPTOR =
            "com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint";

    private static final int TX_SEND_FOD_EVENT = 4;
    private static final int TX_SEND_COMMAND = 6;

    /*
     * The stock Motorola proxy uses AIBinder_transact(..., 0x10000000).
     * Keep the same flags as the vendor proxy.
     */
    private static final int TX_FLAGS = 0x10000000;

    private static final int EVENT_FINGER_DOWN = 0;
    private static final int EVENT_FINGER_UP = 1;
    private static final int EVENT_CANCEL = 2;

    private static final byte[] NO_FLAGS = new byte[0];

    private static final Object LOCK = new Object();
    private static IBinder sBinder;
    private static Method sGetService;
    private static boolean sMissingLogged;

    static {
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/os/ServiceManager;",
                    "Landroid/os/ServiceManager$1;");
        } catch (Throwable t) {
            // Not fatal: LSPosed may already have disabled hidden API checks.
            Log.w(TAG, "HiddenApiBypass unavailable for fingerprint", t);
        }
    }

    private MotoFingerprint() {
    }

    static void warmUp() {
        synchronized (LOCK) {
            serviceLocked();
        }
        Log.d(TAG, "Moto fingerprint client ready");
    }

    static void fingerDownSafely() {
        fingerDownSafely(true, false, false);
    }

    /**
     * Reproduces the state calculation in MotBiometricFingerprint:
     *
     *   state = interactive ? 4 : (onTap ? 3 : 2);
     *   if (biometricPrompt) state |= 8;
     *
     * The Motorola JNI then converts that state to a small flag vector:
     *   4 -> {3}, 2 -> {2}, 3 -> {1}, 8 -> {}.
     */
    static void fingerDownSafely(boolean interactive, boolean onTap, boolean biometricPrompt) {
        try {
            sendFodEvent(EVENT_FINGER_DOWN,
                    downFlags(interactive, onTap, biometricPrompt));
        } catch (Throwable t) {
            Log.e(TAG, "sendFodEvent(FINGER_DOWN) failed", t);
            XposedBridge.log(t);
        }
    }

    private static byte[] downFlags(boolean interactive, boolean onTap,
            boolean biometricPrompt) {
        int state = interactive ? 4 : (onTap ? 3 : 2);
        if (biometricPrompt) {
            state |= 8;
        }

        if ((state & 4) != 0) {
            return new byte[] { 3 };
        }
        if ((state & 2) != 0) {
            return new byte[] { 2 };
        }
        if ((state & 1) != 0) {
            return new byte[] { 1 };
        }
        return NO_FLAGS;
    }

    static void fingerUpSafely() {
        try {
            sendFodEvent(EVENT_FINGER_UP, NO_FLAGS);
        } catch (Throwable t) {
            Log.e(TAG, "sendFodEvent(FINGER_UP) failed", t);
            XposedBridge.log(t);
        }
    }

    static void cancelSafely() {
        try {
            sendFodEvent(EVENT_CANCEL, NO_FLAGS);
        } catch (Throwable t) {
            Log.e(TAG, "sendFodEvent(CANCEL) failed", t);
            XposedBridge.log(t);
        }
    }

    /**
     * Low-level escape hatch. The stock framework uses sendFodEvent() instead;
     * this is kept for diagnostics or for a future vendor variant.
     */
    static int sendCommandSafely(int cmdId, byte[] data) {
        try {
            return sendCommand(cmdId, data);
        } catch (Throwable t) {
            Log.e(TAG, "sendCommand(" + cmdId + ") failed", t);
            XposedBridge.log(t);
            return -1;
        }
    }

    private static int sendFodEvent(int event, byte[] data) {
        Parcel reply = Parcel.obtain();
        try {
            Parcel request = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                request.writeInt(event);
                request.writeByteArray(data);

                int result = transact(TX_SEND_FOD_EVENT, request, reply);
                if (result == 0) {
                    Log.i(TAG, "sendFodEvent(" + eventName(event) + ") -> 0");
                } else {
                    Log.w(TAG, "sendFodEvent(" + eventName(event) + ") -> " + result);
                }
                return result;
            } finally {
                request.recycle();
            }
        } finally {
            reply.recycle();
        }
    }

    private static int sendCommand(int cmdId, byte[] data) {
        Parcel reply = Parcel.obtain();
        try {
            Parcel request = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                request.writeInt(cmdId);
                request.writeByteArray(data == null ? NO_FLAGS : data);

                int result = transact(TX_SEND_COMMAND, request, reply);
                Log.i(TAG, "sendCommand(" + cmdId + ") -> " + result);
                return result;
            } finally {
                request.recycle();
            }
        } finally {
            reply.recycle();
        }
    }

    /**
     * Must be called with LOCK held. Returns the vendor result code, or -1 if
     * the service is unavailable / the call was rejected.
     */
    private static int transact(int code, Parcel request, Parcel reply) {
        synchronized (LOCK) {
            IBinder binder = serviceLocked();
            if (binder == null) {
                return -1;
            }

            try {
                if (!binder.transact(code, request, reply, TX_FLAGS)) {
                    Log.e(TAG, "fingerprint transact(" + code + ") returned false");
                    return -1;
                }

                reply.readException();
                return reply.readInt();
            } catch (Throwable t) {
                // DeadObjectException, SecurityException, ParcelableException, ...
                Log.e(TAG, "fingerprint transact(" + code + ") failed", t);
                sBinder = null;
                return -1;
            }
        }
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
                Log.e(TAG, "Motorola fingerprint service not found: " + INSTANCE);
                XposedBridge.log(TAG + ": fingerprint service not found");
            }
        } catch (Throwable t) {
            sBinder = null;
            Log.e(TAG, "Cannot get Motorola fingerprint service", t);
            XposedBridge.log(t);
        }
        return sBinder;
    }

    private static String eventName(int event) {
        switch (event) {
            case EVENT_FINGER_DOWN:
                return "FINGER_DOWN";
            case EVENT_FINGER_UP:
                return "FINGER_UP";
            case EVENT_CANCEL:
                return "CANCEL";
            default:
                return Integer.toString(event);
        }
    }
}
