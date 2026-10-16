# NavMap

A real Google map on the Garmin Forerunner 165 that follows your GPS and stays on screen while you navigate. Built in public.

**Status:** test builds of the watch app and the NavBridge phone app. Setup: [docs/SETUP.md](docs/SETUP.md).

## What the watch app does
- Light, high-contrast Google map (readable in sunlight) centred on your position, sliding as you move
- Zooms out automatically with speed
- Runs as a plain watch app (no activity session); when the screen dims it shows only the next turn or the time
- Maps come in a 14-colour palette, about a quarter of the Bluetooth and memory cost of full colour
- Up/Down zoom instantly (the current map is scaled until the sharp one arrives), Start shows a debug panel, Back asks before exiting
- Talks to the NavBridge phone app: says hello until it answers, then shows turns, distance, ETA and the route line

## What the phone app (NavBridge) does
- Reads Google Maps' turn-by-turn notification. No Google Maps changes, no root
- Opens NavMap on the watch when navigation starts (Garmin asks for one button press)
- Sends each turn: Google's own arrow, the distance, the street and the ETA
- Share a place from Google Maps to **Navigate with NavMap** to also get the blue route line. It's computed with the Routes API (motorbike, car, bicycle or walking) and redrawn after a reroute
- **Simulate navigation** demo ride for testing without riding

The notification parser is tested against 174 Google Maps notifications in 17 locales (real captures and hand-written ones) from [PebbleNTN](https://github.com/optio/PebbleNTN) (MIT).

## Build
Needs the Connect IQ SDK, a developer key and your own Google Maps Static API key.

1. Put `MAPS_API_KEY=...` in `~/.config/navmap/secrets.properties`
2. Put your developer key at `~/.config/navmap/developer_key.der`
3. Run `tools/build-watch.sh fr165` (or `fr165m`)

`watch/source/Secrets.mc` is generated at build time and never committed.

### Phone app
Needs JDK 17 and the Android SDK (platform 36). The release signing key lives in `~/.config/navmap/navbridge-release.jks`, with its password in `navbridge-signing.properties` next to it. Without them the APK is debug-signed.

Run `tools/build-phone.sh`. It runs the unit tests and writes `dist/NavBridge.apk`. The Maps key from `secrets.properties` is baked in for the route line, so don't publish that APK.

### Testing the watch without a phone
`tools/sim-phone.py` stands in for NavBridge against the Connect IQ simulator. It prints what the watch sends and plays a demo ride with turn arrows, a route line, the ETA and the arrival. It needs only Python 3, not adb.

The simulator (SDK 9.1 and 9.2, on Linux, macOS and Windows) segfaults whenever a watch app transmits over its phone link: Garmin bug [CIQQA-4579](https://forums.garmin.com/developer/connect-iq/i/bug-reports/simulator-segfaults-on-communications-transmit-over-the-tethered-adb-connection-sdk-9-1-0-and-9-2-0-linux). So use the simulator build, which receives phone messages but never transmits:

1. `tools/build-watch.sh fr165 --sim` writes `dist/NavMap-sim.prg`.
2. Run `tools/sim-phone.py serve`, then choose adb Connection → Start in the simulator.
3. Load `NavMap-sim.prg`, then run `tools/sim-phone.py ride` (or `send '<json>'`). `serve --ride` waits for a hello, which this build never sends.

The 9.x simulator asks you to sign in to Garmin Connect before it loads map images.

