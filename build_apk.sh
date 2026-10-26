#!/bin/bash
# Builds and signs OneLineage.apk without Gradle.
#
# Everything is overridable from the environment, so the script works on
# macOS/Linux with any SDK layout:
#   ANDROID_HOME       Android SDK root (default: ~/Library/Android/sdk on macOS,
#                      ~/Android/Sdk on Linux)
#   BUILD_TOOLS_VER    build-tools version to use (default: newest installed)
#   PLATFORM_API       android.jar API level to compile against (default: 36,
#                      falls back to the newest installed platform)
#   JAVA_HOME          JDK 17+ (default: whatever `javac` on PATH is)
#   OUT_APK            where to write the signed APK (default: ./build/OneLineage.apk)
#   KEYSTORE           signing keystore (default: ./keystore/debug.keystore,
#                      generated on first run)
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${BUILD_DIR:-$PROJECT_DIR/build}"
OUT_APK="${OUT_APK:-$BUILD_DIR/OneLineage.apk}"

if [ -n "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Library/Android/sdk" ]; then
        ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -n "$ANDROID_SDK_ROOT" ]; then
        ANDROID_HOME="$ANDROID_SDK_ROOT"
    else
        echo "ANDROID_HOME is not set and no SDK was found" >&2
        exit 1
    fi
fi

if [ -z "$BUILD_TOOLS_VER" ]; then
    BUILD_TOOLS_VER=$(ls "$ANDROID_HOME/build-tools" 2>/dev/null | sort -V | tail -n 1)
fi
SDK_TOOLS="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VER"
[ -x "$SDK_TOOLS/aapt2" ] || { echo "aapt2 not found in $SDK_TOOLS" >&2; exit 1; }

PLATFORM_API="${PLATFORM_API:-36}"
PLATFORM_JAR="$ANDROID_HOME/platforms/android-$PLATFORM_API/android.jar"
if [ ! -f "$PLATFORM_JAR" ]; then
    NEWEST=$(ls -d "$ANDROID_HOME"/platforms/android-* 2>/dev/null | sed 's/.*android-//' | sort -n | tail -n 1)
    [ -n "$NEWEST" ] || { echo "no android.jar under $ANDROID_HOME/platforms" >&2; exit 1; }
    echo "android-$PLATFORM_API not installed, compiling against android-$NEWEST instead"
    PLATFORM_API="$NEWEST"
    PLATFORM_JAR="$ANDROID_HOME/platforms/android-$PLATFORM_API/android.jar"
fi

echo "SDK: $ANDROID_HOME (build-tools $BUILD_TOOLS_VER, platform android-$PLATFORM_API)"

echo "=== 1. Cleaning & preparing build directories ==="
rm -rf "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/base.apk" "$BUILD_DIR/aligned.apk" "$BUILD_DIR/compiled_res.zip"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

cd "$PROJECT_DIR"

echo "=== 2. Compiling resources with aapt2 ==="
"$SDK_TOOLS/aapt2" compile --dir res -o "$BUILD_DIR/compiled_res.zip"

echo "=== 3. Linking resources with aapt2 ==="
"$SDK_TOOLS/aapt2" link \
    -I "$PLATFORM_JAR" \
    --manifest AndroidManifest.xml \
    -o "$BUILD_DIR/base.apk" \
    --java "$BUILD_DIR/gen" \
    "$BUILD_DIR/compiled_res.zip" \
    -A assets

echo "=== 4. Compiling Java sources ==="
javac -source 1.8 -target 1.8 \
    -cp "$PLATFORM_JAR" \
    -d "$BUILD_DIR/classes" \
    src/com/wewaffles/onelineage/*.java \
    "$BUILD_DIR/gen/com/wewaffles/onelineage/R.java"

echo "=== 5. Generating Dalvik Executable (classes.dex) with d8 ==="
"$SDK_TOOLS/d8" \
    --lib "$PLATFORM_JAR" \
    --output "$BUILD_DIR/dex" \
    "$BUILD_DIR/classes/com/wewaffles/onelineage"/*.class

echo "=== 6. Packaging classes.dex into APK ==="
cd "$BUILD_DIR/dex"
zip -u "$BUILD_DIR/base.apk" classes.dex
cd "$PROJECT_DIR"

echo "=== 7. Aligning APK with zipalign ==="
"$SDK_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/base.apk" "$BUILD_DIR/aligned.apk"

KEYSTORE="${KEYSTORE:-$PROJECT_DIR/keystore/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkey -v \
        -keystore "$KEYSTORE" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=OneLineage,O=WeWaffles,C=US"
fi

"$SDK_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    --out "$OUT_APK" \
    "$BUILD_DIR/aligned.apk"

echo "=== BUILD SUCCESSFUL ==="
echo "Output APK: $OUT_APK"
ls -la "$OUT_APK"
