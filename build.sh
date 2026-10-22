#!/usr/bin/env bash
# Builds ScoutUdfpsHbm.apk without Gradle, using the Android SDK
# command-line tools (aapt2, d8, apksigner).
#
# On an aarch64 host the SDK's aapt2 is x86-64, so this script runs it through
# qemu-x86_64. Put an x86-64 glibc sysroot in $X86_64_ROOT (see README).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/root/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
PLATFORM="${PLATFORM:-$SDK/platforms/android-36/android.jar}"
XPOSED_API="${XPOSED_API:-$SDK/api-82.jar}"
HIDDEN_API="${HIDDEN_API:-$SDK/hiddenapibypass/classes.jar}"
X86_64_ROOT="${X86_64_ROOT:-/root/x86_64-root}"

cd "$(dirname "$0")"

# Keep the signing key outside build/ so that rebuilding produces an APK with
# the same signature and can be installed with "adb install -r".
KEYSTORE="${KEYSTORE:-$PWD/debug.keystore}"

for f in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" \
         "$BUILD_TOOLS/apksigner" "$PLATFORM" "$XPOSED_API" "$HIDDEN_API"; do
    if [ ! -e "$f" ]; then
        echo "Missing: $f" >&2
        exit 1
    fi
done

if [ "$(uname -m)" = "aarch64" ] && file "$BUILD_TOOLS/aapt2" | grep -q 'x86-64'; then
    if ! command -v qemu-x86_64 >/dev/null 2>&1; then
        echo "aapt2 is x86-64 and qemu-x86_64 is not installed" >&2
        exit 1
    fi
    AAPT2=(qemu-x86_64 -L "$X86_64_ROOT" "$BUILD_TOOLS/aapt2")
else
    AAPT2=("$BUILD_TOOLS/aapt2")
fi

rm -rf build
mkdir -p build/classes build/dex

echo "==> aapt2 compile"
"${AAPT2[@]}" compile --dir res -o build/res.zip

echo "==> aapt2 link"
"${AAPT2[@]}" link \
    -o build/unsigned.apk \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    --auto-add-overlay \
    -A assets \
    --min-sdk-version 29 \
    --target-sdk-version 36

echo "==> javac"
find src -name '*.java' > build/sources.list
if ! javac --release 8 -Xlint:-options \
        -classpath "$PLATFORM:$XPOSED_API:$HIDDEN_API" \
        -d build/classes \
        @build/sources.list 2>build/javac.err; then
    cat build/javac.err >&2
    echo "Retrying with -source/-target 8" >&2
    javac -source 8 -target 8 -Xlint:-options \
        -bootclasspath "$PLATFORM" \
        -classpath "$XPOSED_API:$HIDDEN_API" \
        -d build/classes \
        @build/sources.list
fi

echo "==> d8"
find build/classes -name '*.class' > build/classes.list
"$BUILD_TOOLS/d8" \
    --lib "$PLATFORM" \
    --min-api 29 \
    --output build/dex \
    $(cat build/classes.list) \
    "$HIDDEN_API"

echo "==> package classes.dex"
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)

if [ ! -f "$KEYSTORE" ]; then
    echo "==> generate debug keystore: $KEYSTORE"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" \
        >/dev/null 2>&1
fi

echo "==> sign"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --min-sdk-version 29 \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out ScoutUdfpsHbm.apk \
    build/unsigned.apk

echo "==> verify"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs ScoutUdfpsHbm.apk

echo
echo "Built: $(pwd)/ScoutUdfpsHbm.apk"
"${AAPT2[@]}" dump badging ScoutUdfpsHbm.apk | head -5
