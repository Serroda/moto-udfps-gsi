# Scout UDFPS HBM + FOD + Enrollment

LSPosed/Vector module for the **Motorola Edge 60 Fusion ("scout")** running a
crDroid 12.12 GSI (Android 16) with the original Motorola `vendor` partition.

The module does three things:

1. **Panel HBM** while the finger is on the UDFPS sensor.
2. **Native Motorola FOD event** (`IMotoFingerPrint.sendFodEvent`) for
   authentication, which is missing on a GSI because the JNI
   `MotBiometricFingerprint.notifyMotFingerprintHal()` does not exist.
3. **Enrollment fix**: re-dispatches `onPointerDown` / `onPointerUp` to the
   `FingerprintEnrollClient` when the Goodix HAL advertises
   `halHandlesDisplayTouches = true`, which is what prevents AOSP from
   delivering those events.

Status: **tested on a real device; enrollment completes and the fingerprint is
stored** (`dumpsys fingerprint` shows `count: 1`, and the scheduler finishes with
`success: true`).

---

## 1. The problem

Symptoms on the GSI:

- Panel HBM works while touching the sensor (the module turns it on).
- Unlocking with an already-enrolled fingerprint works.
- When adding a new fingerprint, the enrollment screen stays at **0 %** and the
  HAL captures no image.
- Logcat shows no call to the HAL's `onPointerDownWithContext` during
  enrollment.
- `IMotoFingerPrint.sendFodEvent` and `sendCommand` reach the HAL but **do not
  start an enrollment capture**: they are telemetry/authentication FOD events
  (`0x600` / `0x601`), not the capture event used by enrollment.

The cause is in the AOSP framework:

```java
// FingerprintProvider.java (AOSP)
public void onPointerDown(long requestId, int sensorId, PointerContext ctx) {
    if (mFingerprintSensors.get(sensorId)
            .getSensorProperties().halHandlesDisplayTouches) {
        return; // <-- the HAL says it handles display touches itself
    }
    mFingerprintSensors.get(sensorId).getScheduler()
            .getCurrentClientIfMatches(requestId, client -> {
                if (client instanceof Udfps) {
                    ((Udfps) client).onPointerDown(ctx);
                }
            });
}
```

The Motorola Goodix HAL advertises `halHandlesDisplayTouches = true`, so AOSP
**drops** `onPointerDown` and never calls
`FingerprintEnrollClient.onPointerDown()`.

On the stock vendor this is fine because the HAL has its own touch reader for
authentication. Enrollment, however, needs the framework to call:

```
FingerprintEnrollClient.onPointerDown(ctx)
  -> AidlSession.getSession().onPointerDownWithContext(ctx)
  -> goodix_extCmd(0xf1)
  -> EventCenter.postEvent(2)
  -> FingerprintCore::onEvent
  -> onEnrollReceivedEvt / onEnrollDownEvt
  -> image capture and enrollment progress
```

Without that event the HAL never starts capturing, so progress stays at 0 %.

## 2. How the module works

### 2.1 Panel HBM (SystemUI)

Hooks, **before** the original method:

```
android.hardware.fingerprint.FingerprintManager.onPointerDown
android.hardware.fingerprint.FingerprintManager.onPointerUp
```

in **SystemUI** (`com.android.systemui`).

On `onPointerDown`:

- calls `IMotoFingerPrint.sendFodEvent(FINGER_DOWN, {3})`;
- calls `IDisplayPanel.setMode(4)` (`PANEL_MODE_HIGH_BRIGHT_FOD`).

On `onPointerUp`:

- calls `IMotoFingerPrint.sendFodEvent(FINGER_UP, {})`;
- calls `IDisplayPanel.setMode(0)` (`PANEL_MODE_NORMAL`).

`PanelHbm` has a **5-second safety timeout**: if `onPointerUp` never arrives
because SystemUI dies or the event is lost, it returns the panel to mode 0.

Panel binder details:

```
instance:   com.motorola.hardware.display.panel.IDisplayPanel/default
descriptor: com.motorola.hardware.display.panel.IDisplayPanel
setMode transaction = 5
flags:      0x10000000  (FLAG_PRIVATE_VENDOR, same as the stock proxy)
mode 4 = HBM, mode 0 = normal
```

### 2.2 Authentication FOD event (system_server and SystemUI)

The stock framework calls, before capture:

```
MotBiometricFingerprint.notifyMotFingerprintHal(event, state)
```

That JNI lives in Motorola's `libandroid_servers.so` and does not exist on the
GSI. The module reproduces the final AIDL call:

```
instance:   com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint/default
descriptor: com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint
sendFodEvent transaction = 4
  in : int32 eventType, byte[] optionalData
  out: AStatus + int32 result
flags: 0x10000000
```

Events:

| event | name          | data                                       | effect in the HAL      |
| ----- | ------------- | ------------------------------------------ | ---------------------- |
| `0`   | `FINGER_DOWN` | `{3}` if interactive, `{2}` if onTap, etc. | FOD down (`0x600` log) |
| `1`   | `FINGER_UP`   | `{}`                                       | FOD up (`0x601` log)   |
| `2`   | `CANCEL`      | `{}`                                       | log/telemetry only     |

`MotoFingerprint.downFlags()` reproduces the stock calculation:

```java
state = interactive ? 4 : (onTap ? 3 : 2);
if (biometricPrompt) state |= 8;
// state 4 -> {3}, 2 -> {2}, 3 -> {1}, 8 -> {}
```

**Important:** this event is for authentication/HBM. It does not start
enrollment. Enrollment is fixed by the provider hook (next section), not by
sending `sendFodEvent` or `sendCommand`.

### 2.3 Enrollment fix (system_server)

`UdfpsHbmModule.hookProviderPointer()` hooks **after** the original method:

```
com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider
  .onPointerDown(long requestId, int sensorId, PointerContext ctx)
  .onPointerUp(long requestId, int sensorId, PointerContext ctx)
```

The `after` hook runs even when the original method returned early because of
`halHandlesDisplayTouches`. The logic is:

1. Read `requestId`, `sensorId`, and `PointerContext` from the arguments.
2. Get `mFingerprintSensors.get(sensorId)`.
3. Read `getSensorProperties().halHandlesDisplayTouches`.
4. If it is `false`, do nothing: AOSP already forwarded the event, and sending
   it again would duplicate it.
5. If it is `true`, get the scheduler and call
   `getCurrentClientIfMatches(requestId, consumer)`.
6. In the `consumer`, if the client is a `FingerprintEnrollClient`, call
   `client.onPointerDown(pointerContext)` or `onPointerUp(pointerContext)`.

This reproduces exactly what AOSP would have done if the HAL flag were `false`.

Simplified code:

```java
Object props = sensor.getSensorProperties();
boolean halHandles = getBooleanField(props, "halHandlesDisplayTouches");
if (!halHandles) return;

scheduler.getCurrentClientIfMatches(requestId, client -> {
    if (client == null) return;
    if (!client.getClass().getName().contains("FingerprintEnrollClient")) {
        return; // do not touch authentication
    }
    client.onPointerDown(pointerContext); // or onPointerUp
});
```

The `FingerprintEnrollClient` filter is important: without it, the module also
re-dispatched touches to the `FingerprintAuthenticationClient`, duplicating FOD
events and captures during normal unlock. Authentication is already handled by
the Goodix HAL's own touch reader; the module must only intervene in
enrollment.

---

## 3. HAL chain evidence

Before the fix, uprobes in the HAL service received no calls during an
enrollment touch:

```
0xd200  onPointerDownWithContext (impl)
0x28b0  goodix_extCmd
0x356d0 FingerprintCore::onEvent
```

After the fix, a real enrollment produced:

```
ScoutUdfpsHbm: pointer down requestId=1 sensor=5 halHandlesDisplayTouches=true
ScoutUdfpsHbm: pointer down -> com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintEnrollClient

[GF_AIDL.hal] [goodix_extCmd]goodix_extCmd cmd=241
GF_AIDL.Engine: onPointerDownImpl out session state:2 p:0, x:597, y:2463

[GF_TA][gfp_enroll_add_image] en = 1 ... en = 20
GF_AIDL.Engine: onEnrollResult(fid=1503889017, gid=0, rem=0)
BiometricStateCallback: Enrollment state changed: true
BiometricScheduler: [Finishing] {FingerprintEnrollClient, requestId=1}, success: true
```

`cmd=241` is `0xf1`, the command that triggers `EventCenter` event 2 and ends in
`onEnrollDownEvt`.

Final verification:

```bash
adb shell dumpsys fingerprint
# {"service":"FingerprintProvider/default",
#  "prints":[{"id":0,"count":1,"accept":3,...}]}
```

---

## 4. Requirements

- Tested with Motorola Edge 60 Fusion `scout` but you can try with other modern Motorola devices.
- GSI/prebuilt Android 16 (tested with crDroid 12.12) with the Motorola
  `vendor` partition **intact**, [crDroid Doze-off](https://github.com/Doze-off/crdroid_gsi_treble).
- Root (KernelSU/Magisk) and **Vector 2.2** (or an LSPosed build compatible with
  Android 16).
- Vendor services present:
  - `com.motorola.hardware.display.panel.IDisplayPanel/default`
  - `com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint/default`
- **Do not use on the stock Motorola ROM**: the stock framework already performs
  HBM and the FOD event, and the module would duplicate those calls.

Quick service check:

```bash
adb shell service list | grep -iE \
  'motorola.hardware.display.panel|motorola.hardware.biometric.fingerprint'
```

---

## 5. Installation and scope

```bash
adb install --no-incremental -r ScoutUdfpsHbm.apk
```

`--no-incremental` avoids the problem where the APK is not readable by the
Vector daemon during the first boot (see section 7).

In Vector/LSPosed:

1. **Modules** → `Scout UDFPS HBM` → enable.
2. **Scope**:
   - **System UI** (`com.android.systemui`) → HBM and FOD event from SystemUI.
   - **System Framework** (`android`) → `FingerprintProvider` hook in
     `system_server`.
3. Reboot the device (the `system_server` hook is installed only at boot;
   killing SystemUI is not enough).

The manifest already declares both scopes:

```xml
<string-array name="xposedscope">
    <item>com.android.systemui</item>
    <item>android</item>
</string-array>
```

In Vector's database the `system_server` scope appears as `system` (not
`android`):

```
147|com.android.systemui|0
147|system|0
```

That is expected: the UI shows it as **System Framework**, and the module
receives `packageName = "android"`. Do not change the module condition to
`system`; that was exactly the bug.

Scope check via CLI:

```bash
adb shell /data/adb/lspd/cli scope ls dev.scout.udfpshbm
# com.android.systemui  0
# system                0

adb shell /data/adb/lspd/cli modules ls
# dev.scout.udfpshbm    10425  enabled
```

---

## 6. Building

The APK is built without Gradle, using the SDK command-line tools:

```bash
PLATFORM=~/Android/Sdk/platforms/android-37.0/android.jar \
XPOSED_API=~/Android/Sdk/api-82.jar \
HIDDEN_API=~/Android/Sdk/hiddenapibypass/classes.jar \
BUILD_TOOLS=~/Android/Sdk/build-tools/36.0.0 \
./build.sh
```

Adjust the paths if your SDK is installed elsewhere; `build.sh` defaults to
`/root/android-sdk`.

Dependencies:

- **build-tools 36.0.0**: `aapt2`, `d8`, `apksigner`.
- **platform android-37**: `android.jar` to compile against Android 16/17.
- **`api-82.jar`**: stubs for the legacy Xposed API
  (`de.robv.android.xposed.*`). At runtime Vector provides the implementation;
  the APK does **not** bundle those classes.
- **HiddenApiBypass 6.1** (`classes.jar`): bundled into the DEX so the module can
  call `ServiceManager.getService()` and access hidden framework fields.
- **`debug.keystore`**: debug signing key; kept outside `build/` so updates keep
  the same signature.

The script uses `javac --release 8`, `d8 --min-api 29`, `aapt2`, and `apksigner`
(v3). The output is `ScoutUdfpsHbm.apk`.

If the host is aarch64 and `aapt2` is x86-64, the script runs it through
`qemu-x86_64` with a sysroot at `X86_64_ROOT` (default `/root/x86_64-root`). On
the x86-64 host used for this build that is not needed.

---

## 7. The `system_server` loading issue (Vector legacy)

The module is a **legacy** Xposed module:

```
assets/xposed_init  ->  dev.scout.udfpshbm.UdfpsHbmModule
```

Vector loads it through `VectorLegacyBridge` in both SystemUI and
`system_server`. On the first boot after installing the APK we saw:

```
W/VectorConfigCache: PackageParser failed for .../dev.scout.udfpshbm.../base.apk
E/vectord: Failed to open APK ...: I/O error
```

The Vector daemon starts before PackageManager finishes scanning, and the APK
(installed in incremental mode) was not yet readable by the daemon. Because
`system_server` requests the list of legacy modules only once at boot, the
module was not loaded there; SystemUI, which starts a few seconds later, did
load it.

Symptoms:

- In SystemUI: `SystemUI hooks installed`.
- In `system_server`: **nothing**; no `Loading legacy module
dev.scout.udfpshbm` line in `/data/adb/lspd/log/modules_*.log`.
- `handleLoadPackage` is only seen for `com.android.systemui`.

Solution:

1. Install with `adb install --no-incremental -r`.
2. Reboot.
3. Verify that the module log shows the load in the `system_server` PID:

```bash
adb shell 'grep -E "Loading legacy module dev.scout.udfpshbm|ScoutUdfpsHbm" \
  /data/adb/lspd/log/modules_*.log | tail -20'
```

Expected output:

```
[ ... 3522: 3522 V/VectorLegacyBridge ] Loading legacy module dev.scout.udfpshbm from ...
[ ... 3522: 3522 V/VectorLegacyBridge ]   Loading class dev.scout.udfpshbm.UdfpsHbmModule
[ ... 3522: 3522 I/VectorLegacyBridge ] ScoutUdfpsHbm: handleLoadPackage package=android process=android
[ ... 3522: 3522 I/VectorLegacyBridge ] ScoutUdfpsHbm: FingerprintProvider pointer hooks installed
[ ... 3522: 3522 I/VectorLegacyBridge ] ScoutUdfpsHbm: system_server hooks installed
```

If only the SystemUI PID appears, the module is not in the `system_server`
scope or the APK was not readable at boot. Check the scope and reboot again.

---

## 8. Verification

### 8.1 Module logs

`XposedBridge.log()` writes to the Vector log, not logcat:

```bash
adb shell 'tail -f /data/adb/lspd/log/modules_*.log' | grep ScoutUdfpsHbm
```

`android.util.Log` writes to logcat with the `ScoutUdfpsHbm` tag:

```bash
adb logcat -s ScoutUdfpsHbm
```

During an enrollment touch you should see:

```
ScoutUdfpsHbm: pointer down requestId=1 sensor=5 halHandlesDisplayTouches=true
ScoutUdfpsHbm: pointer down -> ...FingerprintEnrollClient
ScoutUdfpsHbm: pointer up requestId=1 sensor=5 halHandlesDisplayTouches=true
ScoutUdfpsHbm: pointer up -> ...FingerprintEnrollClient
```

During authentication, with the final guard, only the provider log appears and
the event is **not** re-dispatched to `FingerprintAuthenticationClient`:

```
ScoutUdfpsHbm: pointer down requestId=3 sensor=5 halHandlesDisplayTouches=true
```

### 8.2 HAL logs

```bash
adb logcat | grep -iE \
  'goodix_extCmd|onPointerDownImpl|onEnrollResult|gfp_enroll_add_image|notifyEnrollProgress'
```

Signs that enrollment is capturing:

```
[goodix_extCmd]goodix_extCmd cmd=241
GF_AIDL.Engine: onPointerDownImpl out session state:2
gfp_enroll_add_image en = 1 ... 20
onEnrollResult(fid=..., rem=0)
```

### 8.3 Framework

```bash
adb shell dumpsys fingerprint
```

At least one fingerprint must be present:

```
"prints":[{"id":0,"count":1,"accept":3,...}]
```

And in logcat:

```
BiometricStateCallback: Enrollment state changed: true
BiometricScheduler: [Finishing] {FingerprintEnrollClient, requestId=1}, success: true
```

---

## 9. Troubleshooting

### The module loads in SystemUI but not in system_server

- Check the scope: **System Framework** must be enabled.
- Check that the APK is readable:

  ```bash
  adb shell 'ls -l $(pm path dev.scout.udfpshbm | cut -d: -f2)'
  ```

- Look for `Loading legacy module dev.scout.udfpshbm` in
  `/data/adb/lspd/log/modules_*.log`. If it is missing, `system_server` did not
  receive the module.
- Reinstall with `--no-incremental` and reboot.
- Do not filter on `processName == "system"`: on this ROM it is `"android"`.

### `halHandlesDisplayTouches=false`

The provider already forwards events through the normal path; the module does
nothing to avoid duplicates. If enrollment still fails with the flag `false`,
this hook is not the problem: check the AIDL session, the UDFPS overlay, and the
HAL.

### `no matching client`

The touch `requestId` does not match the scheduler's current client. This
usually happens after enrollment has finished or when the overlay is in a
different state. Check that `FingerprintEnrollClient` is active:

```bash
adb logcat | grep -E 'FingerprintEnrollClient|BiometricScheduler'
```

### Enrollment stays at 0 %

Check in this order:

1. `ScoutUdfpsHbm: pointer down -> ...FingerprintEnrollClient` in logcat.
2. `[goodix_extCmd] cmd=241` in the HAL.
3. `onPointerDownImpl out session state:2` in `GF_AIDL.Engine`.
4. `gfp_enroll_add_image` and `onEnrollResult` in the TEE.

If step 1 is missing, the provider hook was not installed or the `requestId`
does not match. If step 2 is missing, the HAL is not receiving
`onPointerDownWithContext`. If step 2 appears but step 4 does not, the problem
is in the HAL capture/calibration, not in the framework.

### HBM works but enrollment does not

It is not the FOD event. Enrollment does not use `sendFodEvent` or
`sendCommand`. The cause is the `FingerprintProvider` early return due to
`halHandlesDisplayTouches = true`; apply the provider hook.

### FOD events or captures are duplicated during authentication

The module must re-dispatch only to `FingerprintEnrollClient`. Check that the
installed build includes the guard:

```java
if (!client.getClass().getName().contains("FingerprintEnrollClient")) {
    return;
}
```

If your build is older, rebuild, reinstall, and reboot.

### `setIgnoreDisplayTouches set to false/true`

This is normal: the framework does it when showing/hiding the enrollment
overlay. It is not the flag that blocks touches; the flag that matters is
`halHandlesDisplayTouches` from `SensorProps`.

### `avc: denied` or `SecurityException`

The process running the hook does not have permission for the Motorola service.
On Scout, `platform_app_34_0` and `system_server_34_0` are in
`hal_fingerprint_client`, which can `find` `hal_fingerprint_test_service` and
`call` `hal_fingerprint_server`. If the GSI changed those rules, the module
cannot bypass them from Java.

### SystemUI does not start

Disable the module from LSPosed/Vector safe mode or from `adb` before
uninstalling. The HBM timeout does not run if the process dies while mode 4 is
active; turning the screen off and on usually recovers the panel.

---

## 10. Limitations

- **Tested on one specific device**: Motorola Edge 60 Fusion `scout`, crDroid
  12.12 GSI (Android 16), stock Motorola vendor, Vector 2.2 (versionCode 3080).
  Other vendors/versions may change signatures, offsets, or the
  `halHandlesDisplayTouches` flag.
- **Requires root and LSPosed/Vector**. It cannot be installed on a GSI without
  root.
- **Not for the stock ROM**: it would duplicate HBM and FOD events.
- **Enrollment only**: pointer re-dispatch is limited to
  `FingerprintEnrollClient`; authentication still works through the HAL's own
  touch reader.
- **The panel can stay in mode 4** if SystemUI dies while HBM is on; the
  5-second timeout does not run if the process dies.
- **It does not hook SurfaceFlinger or the HAL**: if `IDisplayPanel` does not
  exist or rejects `setMode`, there is no HBM possible from Java.
- **It does not reproduce all of `PanelsService`**: the stock flow also adjusts
  refresh rate, SVI, DC dimming, and color mode before `setMode(4)`. This module
  only performs the final call.
- **The APK in this folder includes the final guard** (enrollment only). The
  build validated on the device during development was the one before the
  guard; enrollment behavior is the same. To apply the guard, reinstall the APK
  and reboot.

---

## 11. Uninstall

```bash
adb uninstall dev.scout.udfpshbm
```

Disable the module in Vector/LSPosed before uninstalling. If SystemUI does not
start, use LSPosed/Vector safe mode.

---
