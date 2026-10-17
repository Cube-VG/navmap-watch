# NavMap

A real Google map on the Garmin Forerunner 165 that follows your GPS and stays on screen while you navigate. Built in public.

**Status:** test builds of the watch app and the NavBridge phone app. Setup: [docs/SETUP.md](docs/SETUP.md).

## What the watch app does
One thing: while Google Maps navigates on your phone, NavMap shows it on the watch, and when the navigation ends, NavMap closes.
- Light, high-contrast Google map (readable in sunlight: warm paper ground, blue-grey water and small roads, dark main roads) turned so the way you face is up, as in Google Maps (direction of travel when moving, the compass when standing), with you just below the centre, sliding as you move, with the next turn (Google's own arrow), the distance, the street, the ETA and the route line ahead of you (Google blue with a darker edge). Between the phone's answers the watch counts the distance down from its own GPS speed, so it keeps up on a fast bike. It zooms out automatically with speed
- Finds your position with every satellite system the watch has (GPS, GLONASS, Galileo and BeiDou on the FR165), not only GPS, Connect IQ's default: Garmin says that's faster. It needs open sky, so indoors it can wait for GPS for good
- Buzzes at most twice per turn, timed by seconds at your speed rather than metres: one long buzz about 25 s before ("turn coming") and three short strong ones about 6 s before ("turn now"), when the turn banner also turns blue. A turn that comes up too soon for both gets only "turn now", and there is never a buzz within 5 s of another, so close city turns don't stack up. Standing still at a light doesn't count down to one (only the last 15 m do). It lights the screen with each buzz and keeps the screen lit for the last 120 m before a turn (at most 40 s, because the screen's burn-in protection allows no more). When the screen dims it shows only the next turn and the distance, with under 4% of the pixels lit
- Says "Arrived" or "Navigation ended" and closes 5 s later
- Only Back does something (it asks before exiting). Taps, swipes and the other buttons do nothing, so nothing pops up over the map
- Maps come at 2 bits per pixel with the land transparent (the watch paints it), 7% fewer Bluetooth bytes than v0.8's three greys and about half v0.5's 15 colours (see "Bluetooth bytes" below)
- Runs as a plain watch app (no activity session), so nothing is recorded
- Draws about once a second, paced by the GPS: each fix is drawn at once, and turn answers and map pictures show with the next one (a buzz is drawn at once). v0.10 redrew the whole turned map on every GPS fix, timer tick, answer and picture, about three times a second in the simulator, for the same picture. Each map picture is turned into screen colours once when it arrives and then turned straight onto the screen, instead of being copied into a square every frame first. Built with the compiler's speed optimisations (`-O 3p`)
- Gets the turns by asking NavBridge every 2 s, every second in the last 500 m before a turn (every 5 s when not navigating), at `http://127.0.0.1:18165/nav`. Garmin Connect makes that request on the phone, the same way it fetches the map pictures, so the turns don't depend on Connect IQ phone messages: Garmin Connect for Android accepts the watch's messages but no longer delivers them to phone apps (Garmin bug [CIQQA-4631](https://forums.garmin.com/developer/connect-iq/i/bug-reports/gcm-5-27-3-android-accepts-communications-transmit-messages-from-watch-app-but-never-delivers-them-to-the-companion-app)). Garmin Connect allows plain HTTP only to 127.0.0.1, and NavBridge answers only requests that carry the key both apps are built with

## What the phone app (NavBridge) does
- Reads Google Maps' turn-by-turn notification. No Google Maps changes, no root
- Opens NavMap on the watch when navigation starts (Garmin asks for one button press)
- Answers NavMap's requests on 127.0.0.1:18165 with the current turn: Google's own arrow, the distance, the street and the ETA, plus the route line when it changes. From 0.5 it answers in the compact form NavMap 0.6 asks for (see "Bluetooth bytes"). Where Garmin Connect still delivers Connect IQ messages, turns are pushed that way too
- Share a place from Google Maps to **Navigate with NavMap** to also get the blue route line. It's computed with the Routes API (motorbike, car, bicycle or walking) and redrawn after a reroute
- **Simulate navigation** demo ride for testing without riding

The notification parser is tested against 174 Google Maps notifications in 17 locales (real captures and hand-written ones) from [PebbleNTN](https://github.com/optio/PebbleNTN) (MIT).

## Build
Needs the Connect IQ SDK, a developer key and your own Google Maps Static API key.

1. Put `MAPS_API_KEY=...` and `NAVBRIDGE_TOKEN=...` (32 hex characters, for example from `openssl rand -hex 16`) in `~/.config/navmap/secrets.properties`. NavBridge is built with the same file
2. Put your developer key at `~/.config/navmap/developer_key.der`
3. Run `tools/build-watch.sh fr165` (or `fr165m`)

`watch/source/Secrets.mc` is generated at build time and never committed.

`tools/build-watch.sh fr165 --perf` writes `dist/NavMap-perf.prg`, which shows on the watch how long a frame takes, averaged over 5 s: `map` (the turned map), `line` (the route line), `all` (the whole frame) in ms, and frames a second. The simulator runs on a PC, so only the watch gives real numbers. `--perf` works with `--sim` too.

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

So NavMap 0.6 asks for (`c=1`) and reads a compact answer: the turn as one line of text, the arrow as runs of rows, the route as a Google encoded polyline thinned to 2 m. `tools/navcodec.py` is the spec and reference encoder (run it to self-test). NavBridge 0.5 answers this way (`NavCodec.kt`, tested against navcodec.py's own output from `tools/navcodec-vectors.py`); with NavBridge 0.4, NavMap 0.6 reads the old answer. A route still over 250 points after thinning is thinned harder, as before, because the watch redraws every point each second. `tools/link_bytes.py msg` and `tools/link_bytes.py map [lat lon]` measure the bytes with a copy of Garmin's LZW:

| | v0.5 | v0.6 |
|---|---|---|
| Route, 600 points | 6,035 B | 555 B (152 points after thinning) |
| Turn arrow | 515 B | 154-186 B |
| Turn answer every 2 s | 176 B | 103 B |
| Map picture, suburb / Kansas City / Times Square | 11.8 / 16.9 / 21.5 KB | 6.7 / 10.3 / 12.4 KB |

Since v0.9 the map picture leaves the land transparent: Google draws only roads, water and road names, and a pixel under half opacity becomes Garmin's transparent index, which the watch fills with its own ground colour. Two colours plus transparent still fit 2 bits, and road edges on transparent land come out as clean steps instead of blended greys, which pack smaller. On 14 maps in 7 cities (zoom 15 and 16) every v0.9 map was smaller than v0.8's, 7% in all.

The watch decodes the route a slice per second: Connect IQ stops an app that runs too long at once.

The 9.x simulator asks you to sign in to Garmin Connect before it loads map images.
