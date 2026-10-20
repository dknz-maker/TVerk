#!/bin/bash
# Builds and signs the APK with the Android SDK command-line tools directly (no Gradle).
#
#   ./build.sh              -> build/tverk.apk
#   ./build.sh install      -> also installs it on the connected device (adb)
#
# Needs: Android SDK (build-tools + any platform >= 30) and a JDK 11+.
# Looks in $ANDROID_HOME, $ANDROID_SDK_ROOT, then the default Android Studio locations.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
for guess in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/lib/android-sdk"; do
    [ -z "$SDK" ] && [ -d "$guess" ] && SDK=$guess
done
[ -d "${SDK:-}" ] || { echo "Android SDK not found; set ANDROID_HOME" >&2; exit 1; }

BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar
[ -x "$BT/aapt2" ] || { echo "No build-tools in $SDK (sdkmanager 'build-tools;35.0.0')" >&2; exit 1; }
[ -f "$PLATFORM" ] || { echo "No platform in $SDK (sdkmanager 'platforms;android-34')" >&2; exit 1; }

if [ -z "${JAVA_HOME:-}" ]; then
    for guess in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$HOME/android-studio/jbr" "/opt/android-studio/jbr"; do
        [ -d "$guess" ] && export JAVA_HOME=$guess && break
    done
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
command -v javac >/dev/null || { echo "JDK not found; set JAVA_HOME" >&2; exit 1; }

VERSION_NAME=$(cat VERSION 2>/dev/null || echo 0.0.0)
VERSION_CODE=$(( $(date +%s) / 60 ))

rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$PLATFORM" --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 26 --target-sdk-version 30 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res.zip
javac -nowarn -Xlint:none --release 11 -classpath "$PLATFORM" -d build/classes \
    $(find src build/gen -name '*.java') 2>&1 | grep -v '^Note:' || true
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

# Local signing key (git-ignored). Each machine gets its own; reinstalling over an APK signed
# with a different key requires `adb uninstall nz.home.tv` first.
KEYSTORE=${KEYSTORE:-tverk.keystore}
KS_PASS=${KS_PASS:-tvhome-local}
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TVerk" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out build/tverk.apk build/aligned.apk
echo "built build/tverk.apk ($(du -h build/tverk.apk | cut -f1), v$VERSION_NAME)"

if [ "${1:-}" = install ]; then
    adb install -r build/tverk.apk
fi
