#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
KS_DIR="$ROOT/keystore"
SDK="${ANDROID_SDK:-/tmp/android/sdk}"
TOOLS="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
AAPT2="$TOOLS/aapt2"
D8="$TOOLS/d8"
ZIPALIGN="$TOOLS/zipalign"
APKSIGNER="$TOOLS/apksigner"

MAIN_KS="$KS_DIR/custominstaller.jks"
MAIN_ALIAS="custominstaller"

require() {
    [ -e "$1" ] || { echo "Missing required path: $1" >&2; exit 1; }
}

require "$AAPT2"
require "$D8"
require "$ZIPALIGN"
require "$APKSIGNER"
require "$ANDROID_JAR"
require "$ROOT/app/AndroidManifest.xml"
require "$ROOT/app/res"

# Use javac from system (JDK 17+)
JAVAC="javac"

mkdir -p "$BUILD" "$DIST" "$KS_DIR"

create_keystore() {
    local file="$1" alias="$2" dname="$3"
    if [ ! -f "$file" ]; then
        keytool -genkeypair \
            -keystore "$file" \
            -storetype JKS \
            -storepass android \
            -keypass android \
            -alias "$alias" \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -dname "$dname" \
            -noprompt
    fi
}

create_keystore "$MAIN_KS" "$MAIN_ALIAS" "CN=Custom Installer, O=iqoofurry, C=RU"

sign_apk() {
    local input="$1" output="$2" ks="$3" alias="$4"
    rm -f "$output" "$output.idsig"
    "$APKSIGNER" sign \
        --ks "$ks" \
        --ks-key-alias "$alias" \
        --ks-pass pass:android \
        --key-pass pass:android \
        --min-sdk-version 31 \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --v4-signing-enabled false \
        --out "$output" \
        "$input"
}

build_main() {
    echo "==> Building CustomInstaller.apk"
    rm -rf "$BUILD/res.zip" "$BUILD/base.apk" "$BUILD/unaligned.apk" \
        "$BUILD/aligned.apk" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
    mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

    # 1. Компилируем ресурсы
    "$AAPT2" compile --dir "$ROOT/app/res" -o "$BUILD/res.zip"

    # 2. Link — получаем base.apk + R.java
    "$AAPT2" link \
        -o "$BUILD/base.apk" \
        -I "$ANDROID_JAR" \
        --manifest "$ROOT/app/AndroidManifest.xml" \
        --java "$BUILD/gen" \
        --min-sdk-version 31 \
        --target-sdk-version 36 \
        --version-code 6 \
        --version-name 1.4 \
        "$BUILD/res.zip"

    # 3. Компилируем Java код
    find "$ROOT/app/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
    "$JAVAC" -source 8 -target 8 -encoding UTF-8 \
        -classpath "$ANDROID_JAR" \
        -d "$BUILD/classes" \
        @"$BUILD/sources.txt"

    # 4. DEX
    "$D8" --min-api 31 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
        $(find "$BUILD/classes" -name '*.class')

    # 5. Сборка APK
    cp "$BUILD/base.apk" "$BUILD/unaligned.apk"
    (cd "$BUILD/dex" && zip -q "$BUILD/unaligned.apk" classes.dex)

    # 6. Выравнивание и подпись
    "$ZIPALIGN" -f 4 "$BUILD/unaligned.apk" "$BUILD/aligned.apk"
    sign_apk "$BUILD/aligned.apk" "$DIST/CustomInstaller.apk" "$MAIN_KS" "$MAIN_ALIAS"
}

build_module() {
    echo "==> Assembling module zip"
    local module="$ROOT/module"
    mkdir -p "$module/system/app/SheetInstaller"
    mkdir -p "$module/META-INF/com/google/android"
    cp -f "$DIST/CustomInstaller.apk" "$module/system/app/SheetInstaller/SheetInstaller.apk"

    chmod 0755 "$module/customize.sh" "$module/service.sh" "$module/uninstall.sh"
    chmod 0644 "$module/module.prop" \
        "$module/system/app/SheetInstaller/SheetInstaller.apk" \
        "$module/META-INF/com/google/android/updater-script"
    chmod 0755 "$module/META-INF/com/google/android/update-binary"

    local output="$DIST/CustomInstaller-module.zip"
    rm -f "$output"
    (cd "$module" && zip -q "$output" \
        module.prop \
        customize.sh \
        service.sh \
        uninstall.sh \
        system/app/SheetInstaller/SheetInstaller.apk \
        META-INF/com/google/android/update-binary \
        META-INF/com/google/android/updater-script)
}

build_main
build_module

echo "==> Done"
ls -l "$DIST"
