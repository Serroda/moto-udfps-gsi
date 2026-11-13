#!/usr/bin/env bash
# Builds MotoUdfpsGsi.apk without Gradle, using the Android SDK
# command-line tools (aapt2, d8, apksigner).
#
# The module uses the modern libxposed API (io.github.libxposed:api), which is
# provided by the framework at runtime and only needed at compile time. If the
# API jar is not present it is extracted from the Maven Central AAR.
#
# On an aarch64 host the SDK's aapt2 is x86-64, so this script runs it through
# qemu-x86_64. Put an x86-64 glibc sysroot in $X86_64_ROOT (see README).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/root/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
PLATFORM="${PLATFORM:-$SDK/platforms/android-37.0/android.jar}"
LIBXPOSED_VERSION="${LIBXPOSED_VERSION:-102.0.0}"
LIBXPOSED_API="${LIBXPOSED_API:-$SDK/libxposed/api-$LIBXPOSED_VERSION.jar}"
HIDDEN_API="${HIDDEN_API:-$SDK/hiddenapibypass/classes.jar}"
X86_64_ROOT="${X86_64_ROOT:-/root/x86_64-root}"

cd "$(dirname "$0")"

# Keep the signing key outside build/ so that rebuilding produces an APK with
# the same signature and can be installed with "adb install -r".
KEYSTORE="${KEYSTORE:-$PWD/debug.keystore}"

if [ ! -e "$LIBXPOSED_API" ]; then
    echo "==> fetching libxposed api $LIBXPOSED_VERSION"
    if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
        echo "Missing: $LIBXPOSED_API (install curl and unzip to fetch it)" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$LIBXPOSED_API")"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL -o "$tmp/api.aar" \
        "https://repo1.maven.org/maven2/io/github/libxposed/api/$LIBXPOSED_VERSION/api-$LIBXPOSED_VERSION.aar"
    unzip -p "$tmp/api.aar" classes.jar > "$LIBXPOSED_API"
    rm -rf "$tmp"
    trap - EXIT
fi

for f in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" \
         "$BUILD_TOOLS/apksigner" "$PLATFORM" "$LIBXPOSED_API" "$HIDDEN_API"; do
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
    --min-sdk-version 29 \
    --target-sdk-version 36

echo "==> javac"
find src -name '*.java' > build/sources.list
if ! javac --release 8 -Xlint:-options \
        -classpath "$PLATFORM:$LIBXPOSED_API:$HIDDEN_API" \
        -d build/classes \
        @build/sources.list 2>build/javac.err; then
    cat build/javac.err >&2
    echo "Retrying with -source/-target 8" >&2
    javac -source 8 -target 8 -Xlint:-options \
        -bootclasspath "$PLATFORM" \
        -classpath "$LIBXPOSED_API:$HIDDEN_API" \
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

# libxposed module descriptors must live at the APK root. aapt2 only copies
# assets/, so they are added to the archive directly, preserving the path.
echo "==> package META-INF/xposed"
(cd resources && zip -q -r ../build/unsigned.apk META-INF)

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
    --out MotoUdfpsGsi.apk \
    build/unsigned.apk

echo "==> verify"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs MotoUdfpsGsi.apk

echo
echo "Built: $(pwd)/MotoUdfpsGsi.apk"
"${AAPT2[@]}" dump badging MotoUdfpsGsi.apk | head -5
unzip -l MotoUdfpsGsi.apk META-INF/xposed/*
