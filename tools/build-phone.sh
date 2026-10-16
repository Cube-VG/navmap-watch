#!/usr/bin/env bash
# Builds NavBridge, the Android phone app, and runs its unit tests. Usage: tools/build-phone.sh
# The Maps API key and the /nav key NAVBRIDGE_TOKEN (~/.config/navmap/secrets.properties) and the
# release signing key (~/.config/navmap/navbridge-release.jks + navbridge-signing.properties) are
# read from outside the repo and never committed. Without the signing files the APK is debug-signed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="$HOME/.config/navmap"
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/opt/jdk-17* | head -1)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/opt/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

prop() { grep "^$1=" "$2" | cut -d= -f2- || true; }

if [ -f "$CONF/secrets.properties" ]; then
    MAPS_API_KEY="$(prop MAPS_API_KEY "$CONF/secrets.properties")"
    NAVBRIDGE_TOKEN="$(prop NAVBRIDGE_TOKEN "$CONF/secrets.properties")"
    export MAPS_API_KEY NAVBRIDGE_TOKEN
fi
if [ -z "${NAVBRIDGE_TOKEN:-}" ]; then
    echo "No NAVBRIDGE_TOKEN in $CONF/secrets.properties. Add one, e.g. NAVBRIDGE_TOKEN=\$(openssl rand -hex 16);" >&2
    echo "tools/build-watch.sh builds the same key into the watch app." >&2
    exit 1
fi
if [ -f "$CONF/navbridge-signing.properties" ] && [ -f "$CONF/navbridge-release.jks" ]; then
    export NAVBRIDGE_KEYSTORE="$CONF/navbridge-release.jks"
    NAVBRIDGE_KEYSTORE_PASSWORD="$(prop password "$CONF/navbridge-signing.properties")"
    export NAVBRIDGE_KEYSTORE_PASSWORD
fi

args=(--console=plain -q)
if [ "$(uname -m)" = aarch64 ]; then
    # Google only ships aapt2 for x86-64; on this ARM server it runs through user-mode QEMU.
    args+=("-Pandroid.aapt2FromMavenOverride=$HOME/opt/android-x86-tools/aapt2")
fi

cd "$ROOT/android"
./gradlew "${args[@]}" :core:test :app:assembleRelease
mkdir -p "$ROOT/dist"
cp app/build/outputs/apk/release/app-release.apk "$ROOT/dist/NavBridge.apk"
echo "Built $ROOT/dist/NavBridge.apk"
