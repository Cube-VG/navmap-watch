#!/usr/bin/env bash
# Builds the NavMap watch app. Usage: tools/build-watch.sh [device] [--sim] [--perf]   (default fr165)
# --sim writes dist/NavMap-sim.prg for the Connect IQ simulator. It receives phone messages but never
# transmits, because the simulator segfaults on any transmit (Garmin bug CIQQA-4579).
# --perf adds -perf to the name and shows frame times on the watch (map, route line, whole frame, frames a second).
# Nothing secret goes in: the watch makes its own pairing token and gets the Google Maps key from NavBridge
# (watch/source/Keys.mc), so the same .prg works for everyone and can be published.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE=fr165
JUNGLE="$ROOT/watch/monkey.jungle"
NAME=NavMap
PERF=0
for arg in "$@"; do
    case "$arg" in
        --sim) JUNGLE="$ROOT/watch/sim.jungle"; NAME="$NAME-sim" ;;
        --perf) PERF=1 ;;
        *) DEVICE="$arg" ;;
    esac
done
if [ "$PERF" = 1 ]; then
    # The same build with frame timing kept in: perfOff left out instead of perfOn.
    sed 's/perfOn/perfOff/' "$JUNGLE" > "$ROOT/watch/perf.jungle"
    JUNGLE="$ROOT/watch/perf.jungle"
    NAME="$NAME-perf"
fi
OUT="$ROOT/dist/$NAME.prg"
KEY_DER="$HOME/.config/navmap/developer_key.der"
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/opt/jdk-17* | head -1)}"
export PATH="$JAVA_HOME/bin:$PATH"
# setup.sh --from-source points CIQ_SDK_MANAGER at the copy it downloaded.
SDK_BIN="$("${CIQ_SDK_MANAGER:-$HOME/bin/connect-iq-sdk-manager}" sdk current-path --bin)"

# Older builds generated this file with the keys in it. Remove it so they can never end up in a .prg again.
rm -f "$ROOT/watch/source/Secrets.mc"

mkdir -p "$ROOT/dist"
# -r (release): leaves out the debug information, which was four fifths of the .prg (124 KB -> 24 KB).
# -O 3p: the compiler's slowest, most thorough optimisations, for speed rather than size.
"$SDK_BIN/monkeyc" -f "$JUNGLE" -d "$DEVICE" -y "$KEY_DER" -l 2 -w -r -O 3p -o "$OUT"
echo "Built $OUT for $DEVICE"
