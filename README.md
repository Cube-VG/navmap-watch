# NavMap

A real Google map on the Garmin Forerunner 165 that follows your GPS and stays on screen while you navigate. Built in public.

**Status:** test builds of the watch app and the NavBridge phone app. Setup: [docs/SETUP.md](docs/SETUP.md).

## What the watch app does
One thing: while Google Maps navigates on your phone, NavMap shows it on the watch, and when the navigation ends, NavMap closes.
- Light, high-contrast Google map (readable in sunlight) centred on your position, sliding as you move, with the next turn (Google's own arrow), the distance, the street, the ETA and the route line. It zooms out automatically with speed
- Buzzes and lights the screen at each new turn and 400, 150 and 50 m before it, and keeps the screen lit for the last 120 m before a turn (at most 40 s, because the screen's burn-in protection allows no more). When the screen dims it shows only the next turn and the distance, with under 4% of the pixels lit
- Says "Arrived" or "Navigation ended" and closes 5 s later
- Only Back does something (it asks before exiting). Taps, swipes and the other buttons do nothing, so nothing pops up over the map
- Maps come in three greys at 2 bits per pixel, 40-45% fewer Bluetooth bytes than v0.5's 15 colours (see "Bluetooth bytes" below)
- Runs as a plain watch app (no activity session), so nothing is recorded
- Gets the turns by asking NavBridge every 2 s (every 5 s when not navigating) at `http://127.0.0.1:18165/nav`. Garmin Connect makes that request on the phone, the same way it fetches the map pictures, so the turns don't depend on Connect IQ phone messages: Garmin Connect for Android accepts the watch's messages but no longer delivers them to phone apps (Garmin bug [CIQQA-4631](https://forums.garmin.com/developer/connect-iq/i/bug-reports/gcm-5-27-3-android-accepts-communications-transmit-messages-from-watch-app-but-never-delivers-them-to-the-companion-app)). Garmin Connect allows plain HTTP only to 127.0.0.1, and NavBridge answers only requests that carry the key both apps are built with

## What the phone app (NavBridge) does
- Reads Google Maps' turn-by-turn notification. No Google Maps changes, no root
- Opens NavMap on the watch when navigation starts (Garmin asks for one button press)
- Answers NavMap's requests on 127.0.0.1:18165 with the current turn: Google's own arrow, the distance, the street and the ETA, plus the route line when it changes. Where Garmin Connect still delivers Connect IQ messages, turns are pushed that way too
- Share a place from Google Maps to **Navigate with NavMap** to also get the blue route line. It's computed with the Routes API (motorbike, car, bicycle or walking) and redrawn after a reroute
- **Simulate navigation** demo ride for testing without riding

The notification parser is tested against 174 Google Maps notifications in 17 locales (real captures and hand-written ones) from [PebbleNTN](https://github.com/optio/PebbleNTN) (MIT).

## Build
Needs the Connect IQ SDK, a developer key and your own Google Maps Static API key.

1. Put `MAPS_API_KEY=...` and `NAVBRIDGE_TOKEN=...` (32 hex characters, for example from `openssl rand -hex 16`) in `~/.config/navmap/secrets.properties`. NavBridge is built with the same file
2. Put your developer key at `~/.config/navmap/developer_key.der`
3. Run `tools/build-watch.sh fr165` (or `fr165m`)

`watch/source/Secrets.mc` is generated at build time and never committed.

### Phone app
Needs JDK 17 and the Android SDK (platform 36). The release signing key lives in `~/.config/navmap/navbridge-release.jks`, with its password in `navbridge-signing.properties` next to it. Without them the APK is debug-signed.

Run `tools/build-phone.sh`. It runs the unit tests and writes `dist/NavBridge.apk`. The Maps key and the token from `secrets.properties` are baked in, so don't publish that APK.

### Testing the watch without a phone
`tools/sim-phone.py` stands in for NavBridge against the Connect IQ simulator. It answers `/nav` on 127.0.0.1:18165 the way NavBridge does (with the key from `secrets.properties`) and plays a demo ride with turn arrows, a route line, the ETA and the arrival. It needs only Python 3, not adb.

1. `tools/build-watch.sh fr165 --sim` writes `dist/NavMap-sim.prg`. This build never transmits, because the simulator (SDK 9.1 and 9.2, on Linux, macOS and Windows) segfaults whenever a watch app transmits over its phone link: Garmin bug [CIQQA-4579](https://forums.garmin.com/developer/connect-iq/i/bug-reports/simulator-segfaults-on-communications-transmit-over-the-tethered-adb-connection-sdk-9-1-0-and-9-2-0-linux).
2. In the simulator, untick **Settings → Use Device HTTPS Requirements**. Garmin Connect allows plain HTTP to 127.0.0.1, but the simulator doesn't make that exception, and NavMap gets -1001 for every `/nav` request.
3. Run `tools/sim-phone.py serve --ride`, then load `NavMap-sim.prg`. The ride starts when NavMap first asks; `tools/sim-phone.py ride` starts another and `stop` ends it. **Settings → Display Mode → Always-On** shows the dimmed screen.
4. Connect IQ phone messages work too: choose adb Connection → Start in the simulator, then `tools/sim-phone.py send '<json>'`.

### Bluetooth bytes
Everything NavMap receives goes over Bluetooth, which is slow, so it is packed tight. How Garmin Connect (Android 5.30) carries it was read from the app itself:

- **JSON answers** are re-serialized into Monkey C's format: every number costs 5 bytes, text 1 byte per ASCII character. Then Garmin's LZW (9-bit codes) may shrink it.
- **Map pictures** go through Garmin's cloud image service, or the phone if that fails. Every pixel becomes the nearest palette colour, packed at 1, 2, 4 or 8 bits (palette + 1 transparent: 4 colours fit 2 bits), then Garmin's LZW with 10-bit codes.

So NavMap 0.6 asks for (`c=1`) and reads a compact answer: the turn as one line of text, the arrow as runs of rows, the route as a Google encoded polyline thinned to 2 m. `tools/navcodec.py` is the spec and reference encoder (run it to self-test). `tools/link_bytes.py msg` and `tools/link_bytes.py map [lat lon]` measure the bytes with a copy of Garmin's LZW:

| | v0.5 | v0.6 |
|---|---|---|
| Route, 600 points | 6,035 B | 555 B (152 points after thinning) |
| Turn arrow | 515 B | 154-186 B |
| Turn answer every 2 s | 176 B | 103 B |
| Map picture, suburb / Kansas City / Times Square | 11.8 / 16.9 / 21.5 KB | 6.7 / 10.3 / 12.4 KB |

The watch decodes the route a slice per second: Connect IQ stops an app that runs too long at once.

The 9.x simulator asks you to sign in to Garmin Connect before it loads map images.
