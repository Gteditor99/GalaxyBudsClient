#!/bin/bash
set -e

BUILD_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$BUILD_DIR"

SDK="/usr/lib/android-sdk"
PLATFORM="$SDK/platforms/android-23/android.jar"
BUILD_TOOLS="/usr/lib/android-sdk/build-tools/debian"
AAPT="aapt"
AAPT2="aapt2"
DX_JAR="/tmp/dx.jar"
APKSIGNER="apksigner"
ZIPALIGN="zipalign"
KOTLINC="kotlinc"

OUT="$BUILD_DIR/output"
# Clean output but preserve .gitignore
find "$OUT" -mindepth 1 ! -name '.gitignore' -delete 2>/dev/null || true
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex" "$OUT/apk"

echo "=== Step 1: Generate R.java ==="
$AAPT package -f -m \
    -S res \
    -J "$OUT/gen" \
    -M AndroidManifest.xml \
    -I "$PLATFORM"

echo "=== Step 2: Compile Kotlin + R.java ==="
$KOTLINC \
    -cp "$PLATFORM" \
    -d "$OUT/classes" \
    -jvm-target 1.8 \
    -nowarn \
    src/*.kt "$OUT/gen/com/galaxybuds/firmwareupdater/R.java" \
    2>&1

echo "=== Step 3: Convert to DEX ==="
# Find all class files
find "$OUT/classes" -name "*.class" > "$OUT/classlist.txt"

# d8 requires input as a jar or class files
cd "$OUT/classes"
jar cf "$OUT/classes.jar" .
cd "$BUILD_DIR"

java -cp "$DX_JAR" com.android.dx.command.Main --dex --output="$OUT/dex/classes.dex" "$OUT/classes.jar"

echo "=== Step 4: Package APK ==="
$AAPT package -f \
    -S res \
    -M AndroidManifest.xml \
    -I "$PLATFORM" \
    -F "$OUT/apk/unsigned.apk"

# Add DEX
cd "$OUT/dex"
$AAPT add "$OUT/apk/unsigned.apk" classes.dex
cd "$BUILD_DIR"

echo "=== Step 5: Align ==="
$ZIPALIGN -f 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

echo "=== Step 6: Sign ==="
# Generate debug keystore if missing
KEYSTORE="$OUT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
        -dname "CN=Debug,O=Debug,L=Debug,S=Debug,C=US" \
        -keystore "$KEYSTORE" \
        -storepass android \
        -keypass android \
        -alias debug \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000
fi

$APKSIGNER sign \
    --ks "$KEYSTORE" \
    --ks-key-alias debug \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT/buds2pro-firmware-updater.apk" \
    "$OUT/apk/aligned.apk"

echo ""
echo "=== BUILD COMPLETE ==="
echo "APK: $OUT/buds2pro-firmware-updater.apk"
ls -la "$OUT/buds2pro-firmware-updater.apk"
