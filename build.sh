#!/bin/bash
# StepSim 精简版构建（原生 Views UI + libxposed）。
set -e
PROJECT_ROOT=~/workspace/stepsim
cd "$PROJECT_ROOT"

export JAVA_HOME=~/android-sdk/jdk17
export PATH=$JAVA_HOME/bin:$PATH
SDK=~/android-sdk
ANDROID_JAR=$SDK/platforms/android-36/android.jar
AAPT2=$SDK/build-tools/36.0.0/aapt2
D8=$SDK/build-tools/36.0.0/d8
ZIPALIGN=$SDK/build-tools/36.0.0/zipalign
APKSIGNER=$SDK/build-tools/36.0.0/apksigner
MB=~/workspace/lspd-run-sim/manual-build
KOTLINC_HOME=$MB/kotlinc
OUT=$PROJECT_ROOT/out
SRC=$PROJECT_ROOT/src

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "=== 1. kotlinc ==="
find "$SRC/java" -name "*.kt" > "$OUT/sources.txt"
cat "$OUT/sources.txt"
LIBXPOSED_JAR=$MB/out/aar/api-102.0.0/classes.jar
[ -f "$LIBXPOSED_JAR" ] || { echo "libxposed not found"; exit 1; }
COROUTINES_JAR=$(ls "$MB"/libs/kotlinx-coroutines-core-jvm-*.jar | head -1)
ANNOT_JAR=$(ls "$MB"/libs/annotations-*.jar | head -1)
KCE_CP="$MB/kotlin-compiler-embeddable-2.0.21.jar:$MB/kotlin-stdlib-2.0.21.jar:$COROUTINES_JAR:$ANNOT_JAR:$(ls "$MB"/kce-deps/*.jar | paste -sd: -)"
"$JAVA_HOME/bin/java" -Xmx2g -cp "$KCE_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -cp "$ANDROID_JAR:$KOTLINC_HOME/lib/kotlin-stdlib.jar:$LIBXPOSED_JAR" \
    -d "$OUT/classes" \
    -jvm-target 17 \
    -no-stdlib \
    -no-reflect \
    @"$OUT/sources.txt"

echo "=== 2. aapt2 compile + link ==="
COMPILED_RES=""
if [ -d "$SRC/res" ]; then
    mkdir -p "$OUT/compiled-res"
    "$AAPT2" compile --dir "$SRC/res" -o "$OUT/compiled-res/res.zip"
    COMPILED_RES="$OUT/compiled-res/res.zip"
fi
"$AAPT2" link -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$SRC/AndroidManifest.xml" \
    --min-sdk-version 27 --target-sdk-version 36 \
    $COMPILED_RES

echo "=== 3. d8 (dex) ==="
"$D8" --min-api 27 --lib "$ANDROID_JAR" \
    --output "$OUT/dex" \
    $(find "$OUT/classes" -name "*.class") "$KOTLINC_HOME/lib/kotlin-stdlib.jar"

echo "=== 4. package APK ==="
cd "$OUT"
cp base.apk StepSim-unsigned.apk
# Add dex
"$AAPT2" add StepSim-unsigned.apk dex/classes.dex 2>/dev/null || {
    mkdir -p apk-out
    cd apk-out
    unzip -q ../StepSim-unsigned.apk
    cp ../dex/classes.dex classes.dex
    zip -q -r ../StepSim-unsigned.apk .
    cd ..
}
# Add xposed init and module.prop (both under META-INF/xposed/)
mkdir -p META-INF/xposed
cp "$SRC/java_init.list" META-INF/xposed/java_init.list
cp "$SRC/module.prop" META-INF/xposed/module.prop
zip -q StepSim-unsigned.apk META-INF/xposed/java_init.list META-INF/xposed/module.prop
# Remove the wrongly-placed root module.prop if it exists
zip -q -d StepSim-unsigned.apk module.prop 2>/dev/null || true

echo "=== 5. zipalign + sign ==="
"$ZIPALIGN" -f 4 StepSim-unsigned.apk StepSim-aligned.apk
KEYSTORE=~/workspace/lspd-run-sim/manual-build/debug.keystore
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out StepSim.apk StepSim-aligned.apk
"$APKSIGNER" verify --print-certs StepSim.apk | head -5

mkdir -p "$PROJECT_ROOT/output"
cp StepSim.apk "$PROJECT_ROOT/output/"
ls -lh "$PROJECT_ROOT/output/StepSim.apk"
echo "BUILD OK"
