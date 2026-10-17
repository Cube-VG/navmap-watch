# Developing NavMap

How the two apps work, how to build and test them, and how to publish a release. For using NavMap, see the [README](../README.md).

## How it fits together

- **NavBridge** (Android, `android/`) reads Google Maps' turn-by-turn notification (no Google Maps changes, no root) and serves the current turn on `http://127.0.0.1:18165/nav`: Google's own arrow, the distance, the street and the arrival time, plus the route line when it changes. It opens NavMap on the watch when navigation starts (Garmin asks for one button press). Sharing a place from Google Maps to **Navigate with NavMap** also computes a route line with the Routes API (motorbike, car, bicycle or walking), redrawn after a reroute. **Simulate navigation** plays a demo ride.
- **NavMap** (Connect IQ, `watch/`) asks NavBridge for the turn every 2 s, every second in the last 500 m before a turn, and every 5 s when not navigating. Garmin Connect makes that request on the phone, the same way it fetches the map pictures, so the turns don't depend on Connect IQ phone messages: Garmin Connect for Android accepts the watch's messages but no longer delivers them to phone apps (Garmin bug [CIQQA-4631](https://forums.garmin.com/developer/connect-iq/i/bug-reports/gcm-5-27-3-android-accepts-communications-transmit-messages-from-watch-app-but-never-delivers-them-to-the-companion-app)). Garmin Connect allows plain HTTP only to 127.0.0.1. Where it still delivers Connect IQ messages, NavBridge pushes turns that way too.
- NavMap fetches Google Static Maps pictures with `makeImageRequest`: Google draws only roads, water and road names in two colours, the land comes out transparent, and the watch paints it warm paper. Between NavBridge's answers it counts the distance down from its own GPS speed. The map turns so the way you face is up (direction of travel when moving, the compass below walking pace); turned bitmaps must be in the screen's colour format, so each 2-bit picture is copied into screen colours once when it arrives and then drawn turned with an AffineTransform. The route line starts at your closest point on it, searched a few segments on from last time (a route can pass the same place twice), then along the rest of it, and over the whole line when it's new or you went back. It draws about once a second, paced by the GPS: turn answers and pictures show with the next fix, a buzz at once. It finds its position with every satellite system the watch has (GPS, GLONASS, Galileo and BeiDou on the FR165), not only GPS, Connect IQ's default.
- The screen: NavMap buzzes at most twice per turn, timed in seconds at the current speed rather than metres: one long buzz about 25 s before ("turn coming") and three short strong ones about 6 s before ("turn now"), when the turn banner turns blue. A turn that comes up too soon for both gets only "turn now", no buzz comes within 5 s of another, and standing still at a light doesn't count down to one (only the last 15 m do). It lights the screen with each buzz and keeps it lit for the last 120 m before a turn (at most 40 s, the limit Garmin's burn-in protection allows). Dimmed, it shows only the next turn and the distance, with under 4% of the pixels lit. Only Back does something (it asks before exiting), so nothing pops up over the map. It runs as a plain watch app with no activity session, so nothing is recorded.

The notification parser is tested against 174 Google Maps notifications in 17 locales (real captures and hand-written ones) from [PebbleNTN](https://github.com/optio/PebbleNTN) (MIT).

## Pairing, the key, consent and the map credit

Nothing secret is built into either app, so the published files work for everyone.

- **Pairing.** The first time NavMap runs, it makes a 32-hex-character token (Application.Storage `tok`) and sends it as `k` with every `/nav` request. NavBridge keeps the first well-formed token it sees while pairing is open, and from then on answers only that token; any other gets 403, and the watch says "Open NavBridge to pair". Pairing is open for 10 minutes after NavBridge comes on screen, after setup.sh asks, and after NavBridge asks the watch to open NavMap. **Pair again** in NavBridge forgets the paired watch. See `WatchPairing.kt`.
- **The key.** The user's key lives only in NavBridge (`Prefs.mapsKey`). NavMap 0.10+ sends `kf`, the last 6 characters of the key it has (`none` for none). While that differs from NavBridge's key, every answer carries `mk`, the key itself, and NavMap keeps it (Storage `mk`). See `NavEndpoint.withMapsKey` and `watch/source/Keys.mc`.
- The `/nav` answers show where the rider is going and carry the key, and any app on the phone can connect to 127.0.0.1, hence the pairing. The map picture URLs carry the key too, and Garmin Connect fetches them for the watch, possibly through Garmin's servers.
- NavMap up to 0.8 had the token and the key built in. NavBridge still pairs with its token, but never sends it the key.
- **Consent.** Google's terms require the user's express, prior, revocable consent before an app obtains or keeps their location (Maps Platform terms 4.4(c)(iii)), and a notice that the app includes Google Maps content under Google's terms (3.2.2(a)). NavBridge shows both on its first screen (`Consent.kt`) and keeps the answer in `Prefs.consentedAt`. Until the user agrees, it answers every `/nav` poll with `{"t":"consent"}`, before pairing: no key, no turns, and nothing from the poll is read. NavMap 0.10+ then drops its key and route line and shows "Open NavBridge to agree", and it sends its position only while it holds the key. **Withdraw consent** (under Privacy and terms) forgets every position NavBridge has (`NavSession.forgetLocation`).
- **Credit.** Google's logo and data credit are printed along the bottom of each map picture, outside the round screen. NavMap writes "Google Maps" under the map instead (the text form Google allows where its logo doesn't fit), and shows the picture's own credit strip, cut into two unscaled rows, with the first map and whenever START is pressed. In countries where Google names other data providers, that strip is where they appear.

The protocol is described in full in `android/core/src/main/kotlin/com/cubevg/navmap/core/WatchProtocol.kt`.

## What setup.sh does

`setup.sh` is one bash script for macOS and Linux (bash 3.2 or newer). It keeps its tools in `~/.cache/navmap`, and your key and choices in `~/.config/navmap` (mode 700; files 600):

- `secrets.properties`: `MAPS_API_KEY`.
- `setup.properties`: `WATCH` (fr165 or fr165m) and `GCP_PROJECT`.
- `gcloud/`: gcloud's sign-in, kept apart from any other gcloud setup (`CLOUDSDK_CONFIG`).

Each step checks first and does only what's missing, so it's safe to run again. The steps:

1. **Devices.** It asks which watch, whether the phone is Android, and whether the watch is paired in Garmin Connect, and stops plainly for anything NavMap can't work with.
2. **Key.** It reuses a saved key that still works; otherwise you paste one or let it make one. To make one, it downloads gcloud (and, on Macs and ARM Linux without Python 3.10+, a standalone Python just for gcloud, checksum pinned). After you sign in to Google it makes a project called NavMap, links a billing account (or opens the page to create one) and enables `static-maps-backend.googleapis.com` and `routes.googleapis.com`. Then it creates a key restricted to those two APIs and waits until both answer.
3. **Files.** It downloads `NavMap-fr165.prg`, `NavMap-fr165m.prg`, `NavBridge.apk` and `SHA256SUMS` from the latest GitHub release and checks them. `NAVMAP_FILES=<dir>` uses local files instead, and `NAVMAP_FILES_URL` another address.
4. **Phone,** over USB debugging with adb. It downloads Google's platform-tools, or offers apt on ARM Linux, where Google makes none. It opens the Play Store if Garmin Connect or Google Maps is missing and installs NavBridge, handling a differently signed old build, "Install via USB" and Play Protect. Then it switches on what NavBridge needs:
   - `cmd notification allow_listener com.cubevg.navbridge/com.cubevg.navbridge.NavListenerService`
   - `pm grant com.cubevg.navbridge android.permission.POST_NOTIFICATIONS` (Android 13+)
   - `dumpsys deviceidle whitelist +<app>` and `cmd appops set <app> RUN_ANY_IN_BACKGROUND allow` for NavBridge and Garmin Connect
   - `cmd appops set com.cubevg.navbridge AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore` (Android 11+)

   It gives NavBridge the key and the travel mode through `SetupProvider` (below). If the phone wouldn't let adb grant notification access, it opens that setting and waits. Last, it opens NavBridge and waits for the user to tap **Agree** on its first screen (see Consent, above): adb can read that answer but not give it.
5. **Watch.** It copies the `.prg` into `GARMIN/Apps` over MTP: GVfs (`gio`) on Linux, libmtp (`mtp-sendfile`, from Homebrew) on macOS. It reads the watch's part number from `GARMIN/GarminDevice.xml` (006-B4432 is the FR165, 006-B4433 the FR165 Music) and copies the matching build. Without either tool it tells you where to copy the file by hand.
6. **Test.** It checks for the user's Agree, opens pairing, waits until NavMap has paired and has the key, plays the demo ride, and asks whether the turn shows on the watch.

`bash setup.sh key|phone|watch|test` runs one step again. `bash setup.sh --from-source` adds three steps before the phone: Garmin sign-in with [connect-iq-sdk-manager](https://github.com/lindell/connect-iq-sdk-manager-cli) (it shows Garmin's licence, accepts it once you say yes, signs in, and downloads SDK 9.2.0 and the fr165/fr165m device files), a developer key made with openssl, and the build below with Java 17 and the Android SDK downloaded into the cache. The Android build needs an x86-64 computer or a Mac: Google ships aapt2 only for those.

### SetupProvider

setup.sh's way into NavBridge, over USB debugging. Only adb's shell user and root may call it.

```
adb shell content call --uri content://com.cubevg.navbridge.setup --method <key|mode|pair|simulate|stop|status> [--arg <value>]
```

Every method answers with the status, for example `Result: Bundle[{key=true, paired=true, watchHasKey=true, listener=true, …}]`.

## Building

### Watch app
Needs the Connect IQ SDK (through connect-iq-sdk-manager) and a developer key at `~/.config/navmap/developer_key.der`. To make one:

```
openssl genrsa -out developer_key.pem 4096
openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem -out ~/.config/navmap/developer_key.der -nocrypt
```

Run `tools/build-watch.sh fr165` (or `fr165m`); it writes `dist/NavMap.prg`. Nothing secret goes in. `--perf` writes `dist/NavMap-perf.prg`, which shows how long a frame takes on the watch, averaged over 5 s: `map` (the turned map), `line` (the route line), `all` (the whole frame) in ms, and frames a second. The simulator runs on a PC, so only the watch gives real numbers.

### Phone app
Needs JDK 17 and the Android SDK (platform 36). The release signing key lives in `~/.config/navmap/navbridge-release.jks`, with its password in `navbridge-signing.properties` next to it; without them the APK is debug-signed. Android only updates an app with one signed by the same key, so keep it.

Run `tools/build-phone.sh`. It runs the unit tests and writes `dist/NavBridge.apk`. No key goes in.

### Publishing a release
setup.sh downloads the files from `https://github.com/Cube-VG/navmap-watch/releases/latest/download/`.

1. Build into `dist/release`: `bash setup.sh --from-source` does it, or by hand, `tools/build-watch.sh fr165 && cp dist/NavMap.prg dist/release/NavMap-fr165.prg`, the same for `fr165m`, and `tools/build-phone.sh && cp dist/NavBridge.apk dist/release/`.
2. `cd dist/release && sha256sum NavBridge.apk NavMap-fr165.prg NavMap-fr165m.prg > SHA256SUMS`
3. Check that no key slipped in. This must print 0 for each file: `grep -c -aE 'AIza[0-9A-Za-z_-]{35}' dist/release/*`
4. `gh release create v<NavMap version> --title "NavMap <version> and NavBridge <version>" dist/release/NavBridge.apk dist/release/NavMap-fr165.prg dist/release/NavMap-fr165m.prg dist/release/SHA256SUMS`

## Testing the watch without a phone
`tools/sim-phone.py` stands in for NavBridge against the Connect IQ simulator. It answers `/nav` on 127.0.0.1:18165 the way NavBridge does: it pairs with the first token it sees, and hands over the key from `~/.config/navmap/secrets.properties`. It also plays a demo ride with turn arrows, a route line, the ETA and the arrival. It needs only Python 3, not adb.

1. `tools/build-watch.sh fr165 --sim` writes `dist/NavMap-sim.prg`. This build never transmits, because the simulator (SDK 9.1 and 9.2, on Linux, macOS and Windows) segfaults whenever a watch app transmits over its phone link: Garmin bug [CIQQA-4579](https://forums.garmin.com/developer/connect-iq/i/bug-reports/simulator-segfaults-on-communications-transmit-over-the-tethered-adb-connection-sdk-9-1-0-and-9-2-0-linux).
2. In the simulator, untick **Settings → Use Device HTTPS Requirements**. Garmin Connect allows plain HTTP to 127.0.0.1, but the simulator doesn't make that exception, and NavMap gets -1001 for every `/nav` request. The simulator ticks it again each time it starts.
3. Run `tools/sim-phone.py serve --ride`, then load `NavMap-sim.prg`. It logs "Paired with NavMap", then "also the Google Maps key… (kf=none)" and "NavMap has the Google Maps key now". The ride starts when NavMap first asks; `tools/sim-phone.py ride` starts another and `stop` ends it. **Settings → Display Mode → Always-On** shows the dimmed screen.
4. Connect IQ phone messages work too: choose adb Connection → Start in the simulator, then `tools/sim-phone.py send '<json>'`.
5. Consent: `tools/sim-phone.py serve --no-consent` answers `{"t":"consent"}` like a NavBridge the user hasn't agreed in, and logs whether NavMap sends its position. `tools/sim-phone.py agree` and `withdraw` change the answer while it runs.

The simulator keeps NavMap's storage (the token and the key) in `GARMIN/APPS/DATA/NAVMAP-FR165.DAT` and `.IDX` in its temporary folder, across restarts. Delete both to start as a new watch would; they hold the key in plain text.

The 9.x simulator asks you to sign in to Garmin Connect before it loads map images.

## Bluetooth bytes
Everything NavMap receives goes over Bluetooth, which is slow, so it is packed tight:

- **JSON answers:** a number costs several times as many bytes as a short text, so anything bulky travels as text.
- **Map pictures** are reduced to the palette NavMap asks for (`makeImageRequest`'s `:palette`), and fewer colours make a smaller picture. Three greys cut the bytes per map by 40-45% against NavMap 0.5's palette (table below).

So NavMap 0.6 asks for (`c=1`) and reads a compact answer: the turn as one line of text, the arrow as runs of rows, the route as a Google encoded polyline thinned to 2 m. `tools/navcodec.py` is the spec and reference encoder (run it to self-test). NavBridge 0.5 answers this way (`NavCodec.kt`, tested against navcodec.py's own output from `tools/navcodec-vectors.py`); with NavBridge 0.4, NavMap 0.6 reads the old answer. A route still over 250 points after thinning is thinned harder, as before, because the watch redraws every point each second. Bytes on the Bluetooth link:

| | v0.5 | v0.6 |
|---|---|---|
| Route, 600 points | 6,035 B | 555 B (152 points after thinning) |
| Turn arrow | 515 B | 154-186 B |
| Turn answer every 2 s | 176 B | 103 B |
| Map picture, suburb / Kansas City / Times Square | 11.8 / 16.9 / 21.5 KB | 6.7 / 10.3 / 12.4 KB |

Since v0.9 the map picture leaves the land transparent: Google draws only roads, water and road names, and the watch fills the rest with its own ground colour. Road edges on transparent land come out as clean steps instead of blended greys, which pack smaller. On 14 maps in 7 cities (zoom 15 and 16) every v0.9 map was smaller than v0.8's, 7% in all.

The watch decodes the route a slice per second: Connect IQ stops an app that runs too long at once.
