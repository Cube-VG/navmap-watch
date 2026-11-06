#!/usr/bin/env bash
# Builds the NavMap watch app. Usage: tools/build-watch.sh [device] [--sim] [--perf] [--publish] [--package]   (default fr165)
# --sim writes dist/NavMap-sim.prg for the Connect IQ simulator. It receives phone messages but never
# transmits, because the simulator segfaults on any transmit (Garmin bug CIQQA-4579).
# --perf adds -perf to the name and shows frame times on the watch (map, route line, whole frame, frames a second).
# --publish is the build for Garmin Connect: it uses the "Google Maps" label (storeOff left out).
# --package builds a .iq application package for Garmin Connect IQ Store export instead of a single-device .prg.
# Nothing secret goes in: the watch makes its own pairing token and gets the Google Maps key from NavBridge
# (watch/source/Keys.mc), so the same .prg works for everyone and can be published.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE=fr165
JUNGLE="$ROOT/watch/monkey.jungle"
NAME=NavMap
PERF=0
PUBLISH=0
PACKAGE=0
for arg in "$@"; do
    case "$arg" in
        --sim) JUNGLE="$ROOT/watch/sim.jungle"; NAME="$NAME-sim" ;;
        --perf) PERF=1 ;;
        --publish) PUBLISH=1 ;;
        --package) PACKAGE=1 ;;
        *) DEVICE="$arg" ;;
    esac
done
if [ "$PERF" = 1 ]; then
    # The same build with frame timing kept in: perfOff left out instead of perfOn.
    sed 's/perfOn/perfOff/' "$JUNGLE" > "$ROOT/watch/perf.jungle"
    JUNGLE="$ROOT/watch/perf.jungle"
    NAME="$NAME-perf"
fi
if [ "$PUBLISH" = 1 ]; then
    # Store build for Garmin Connect: uses "Google Maps" label (storeOff left out instead of storeOn).
    sed 's/storeOn/storeOff/' "$JUNGLE" > "$ROOT/watch/publish.jungle"
    JUNGLE="$ROOT/watch/publish.jungle"
    NAME="$NAME-publish"
fi
# The signing key. NAVBRIDGE_WATCH_KEY overrides it, for a throwaway key on a machine that isn't the release one.
KEY_DER="${NAVBRIDGE_WATCH_KEY:-$HOME/.config/navmap/developer_key.der}"
if [ -z "${JAVA_HOME:-}" ]; then
    # JDK 17 is what the Connect IQ SDK and the Android build are both tested against.
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "build-watch: no JDK 17 found. Set JAVA_HOME or install one (brew install --cask temurin@17)." >&2
    exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
# setup.sh --from-source points CIQ_SDK_MANAGER at the copy it downloaded. Otherwise a Mac that already has the SDK
# from the Connect IQ SDK Manager app records which one in current-sdk.cfg, so read that instead.
SDK_BIN=""
for manager in "${CIQ_SDK_MANAGER:-}" "$HOME/bin/connect-iq-sdk-manager"; do
    if [ -n "$manager" ] && [ -x "$manager" ]; then
        SDK_BIN="$("$manager" sdk current-path --bin 2>/dev/null || true)"
        [ -n "$SDK_BIN" ] && break
    fi
done
if [ -z "$SDK_BIN" ] || [ ! -x "$SDK_BIN/monkeyc" ]; then
    SDK_CFG="$HOME/Library/Application Support/Garmin/ConnectIQ/current-sdk.cfg"
    if [ -f "$SDK_CFG" ]; then
        SDK_BIN="$(tr -d '\r\n' < "$SDK_CFG")/bin"
    fi
fi
if [ ! -x "$SDK_BIN/monkeyc" ]; then
    echo "build-watch: no Connect IQ SDK. Install the SDK Manager from developer.garmin.com/connect-iq/sdk/," >&2
    echo "or set CIQ_SDK_MANAGER to a connect-iq-sdk-manager binary." >&2
    exit 1
fi
if [ ! -f "$KEY_DER" ]; then
    echo "build-watch: no developer key at $KEY_DER. Make one with:" >&2
    echo "  mkdir -p \"$(dirname "$KEY_DER")\"" >&2
    echo "  openssl genrsa -out developer_key.pem 4096" >&2
    echo "  openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem -out \"$KEY_DER\" -nocrypt" >&2
    exit 1
fi

# A store build is harmful if the URL it sends buyers to doesn't answer: when the trial ends, Garmin redirects the
# customer there to pay, and if it 404s they are locked out with no way to buy. Check it before producing something
# that could be uploaded.
if [ "$PUBLISH" = 1 ] || [ "$PACKAGE" = 1 ]; then
    UNLOCK_URL="$(grep -o '<iq:unlockURL>[^<]*' "$ROOT/watch/manifest.xml" | head -1 | cut -d'>' -f2)"
    if [ -z "$UNLOCK_URL" ]; then
        echo "build-watch: watch/manifest.xml has no <iq:unlockURL>, so a trial app could never be unlocked." >&2
        exit 1
    fi
    if [ "${NAVBRIDGE_SKIP_UNLOCK_CHECK:-0}" != "1" ]; then
        CODE="$(curl -s -o /dev/null -m 10 -w '%{http_code}' "$UNLOCK_URL" 2>/dev/null || echo 000)"
        case "$CODE" in
            000|404|"")
                echo "build-watch: $UNLOCK_URL answers '$CODE', so the unlock service is not live there." >&2
                echo "  Uploading a store build with that URL locks every buyer out when the trial ends." >&2
                echo "  Deploy it first (see unlock/README.md), or set NAVBRIDGE_SKIP_UNLOCK_CHECK=1 to build anyway." >&2
                exit 1
                ;;
        esac
    fi
fi

# Older builds generated this file with the keys in it. Remove it so they can never end up in a .prg again.
rm -f "$ROOT/watch/source/Secrets.mc"

mkdir -p "$ROOT/dist"
# -r (release): leaves out the debug information, which was four fifths of the .prg (124 KB -> 24 KB).
# -O 3p: the compiler's slowest, most thorough optimisations, for speed rather than size.
if [ "$PACKAGE" = 1 ]; then
    OUT="$ROOT/dist/$NAME.iq"
    "$SDK_BIN/monkeyc" -f "$JUNGLE" -e -y "$KEY_DER" -l 2 -w -r -O 3p -o "$OUT"
    echo "Built store package $OUT"
else
    OUT="$ROOT/dist/$NAME.prg"
    "$SDK_BIN/monkeyc" -f "$JUNGLE" -d "$DEVICE" -y "$KEY_DER" -l 2 -w -r -O 3p -o "$OUT"
    echo "Built $OUT for $DEVICE"
fi
