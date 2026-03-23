#!/bin/bash
# build_and_install.sh
# Builds a fresh debug APK using Android Studio's bundled JDK 17
# and installs it on every running emulator / connected device.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"

# ── 1. Locate Android Studio's bundled JDK 17 ───────────────────────────────
AS_JDK=""
for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/Applications/Android Studio.app/Contents/jdk/Contents/Home" \
    "$HOME/Library/Application Support/JetBrains/Toolbox/apps/AndroidStudio/ch-0/*/jbr/Contents/Home"; do
    # Expand glob
    for expanded in $candidate; do
        if [ -f "$expanded/bin/java" ]; then
            AS_JDK="$expanded"
            break 2
        fi
    done
done

if [ -z "$AS_JDK" ]; then
    echo "❌  Could not find Android Studio JDK. Open Android Studio → Settings → Build Tools → Gradle → Gradle JDK and note the path, then set AS_JDK manually in this script."
    exit 1
fi
echo "✅  Using JDK: $AS_JDK"
export JAVA_HOME="$AS_JDK"

# ── 2. Locate ADB ────────────────────────────────────────────────────────────
ADB=""
for candidate in \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "/usr/local/bin/adb"; do
    if [ -f "$candidate" ]; then
        ADB="$candidate"
        break
    fi
done

if [ -z "$ADB" ]; then
    echo "❌  adb not found. Make sure Android SDK platform-tools are installed."
    exit 1
fi
echo "✅  Using ADB: $ADB"

# ── 3. Build debug APK ───────────────────────────────────────────────────────
echo ""
echo "🔨  Building debug APK..."
cd "$SCRIPT_DIR"
./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
    echo "❌  APK not found at $APK_PATH"
    exit 1
fi

APK_SIZE=$(du -sh "$APK_PATH" | cut -f1)
echo "✅  APK built: $APK_SIZE  →  $APK_PATH"

# ── 4. Find all running devices / emulators ──────────────────────────────────
echo ""
echo "📱  Scanning for devices..."
DEVICES=$("$ADB" devices | grep -v "List of devices" | grep -v "^$" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    echo "⚠️   No devices found. Start an emulator first:"
    echo "     ~/Library/Android/sdk/emulator/emulator -list-avds"
    echo "     ~/Library/Android/sdk/emulator/emulator -avd <name> &"
    exit 1
fi

# ── 5. Install on each device ────────────────────────────────────────────────
INSTALLED=0
for DEVICE in $DEVICES; do
    MODEL=$("$ADB" -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    echo ""
    echo "📲  Installing on: $DEVICE ($MODEL)..."
    "$ADB" -s "$DEVICE" install -r "$APK_PATH" && {
        echo "✅  Installed on $DEVICE"
        # Launch the app
        "$ADB" -s "$DEVICE" shell am start -n "com.vamanit.calendar/.ui.signin.SignInActivity" 2>/dev/null
        echo "🚀  Launched SignInActivity on $DEVICE"
        INSTALLED=$((INSTALLED + 1))
    } || echo "❌  Install failed on $DEVICE"
done

echo ""
echo "─────────────────────────────────────────────────────"
echo "✅  Done — installed on $INSTALLED device(s)"
echo ""
echo "📋  To watch logs:"
echo "    $ADB logcat -s 'CalendarRepo' 'AuthManager' 'GoogleCal' 'MSCal' '*:E' | grep -v 'chatty'"
