---
name: verify-navmap
description: Drive the NavMap watch app (Connect IQ, Forerunner 165) in the headless Connect IQ simulator on this server, against tools/sim-phone.py standing in for the NavBridge phone app, and capture screenshots and logs as proof. Also covers NavBridge on a real Android phone over adb when one is connected. Use it to prove a change to watch/ or to the /nav protocol works the way a rider sees it, not only that it builds.
---

# Verify NavMap

NavMap has two apps (see `docs/DEVELOPING.md`):

- **NavMap**, the watch app in `watch/`. This is the primary surface, and the one this skill drives. It runs in the Connect IQ simulator on this server.
- **NavBridge**, the Android app in `android/`. It serves the current turn on `http://127.0.0.1:18165/nav`. Here, `tools/sim-phone.py` plays NavBridge: it pairs, hands over the Google Maps key, answers consent and plays a demo ride. On a real phone it can only be driven over adb, and only when one is attached. See `features/navbridge-on-phone.md`.

Commands below run from the repo root. The helper is `.claude/skills/verify-navmap/navmap-verify`; call it `V` in your shell:

```
V=.claude/skills/verify-navmap/navmap-verify
```

## The machine

This server is arm64. The SDK's simulator is an x86-64 GTK binary, so it runs under user-mode QEMU with its own Xvfb, from the harness in `~/opt/ciq-sim` (`~/opt/ciq-sim/README.md` explains it in full). Expect it to be slow: an app starts about 6 s after load, and each menu-driving command takes 15 to 40 s.

- **One run at a time.** The simulator uses port 1234 and display `:99`, and `sim-phone.py` uses ports 18165, 7381 and 7382. So it's one instance per user account. `V up` refuses while another run, or a simulator it didn't start, is live. Never stop a simulator you didn't start: another session may be using it.
- **Map pictures never load here.** `makeImageRequest` needs a Garmin Connect sign-in, which this simulator can't do. The harness's dialog watcher clicks Cancel on the sign-in prompt, and the watch shows `No map: error 401` (or `Loading map`). That is expected. Map pictures can only be proven on a real watch.
- **The watch never transmits in the simulator build.** `tools/build-watch.sh fr165 --sim` leaves out `Communications.transmit`, because the simulator segfaults on it (Garmin bug CIQQA-4579). So hello, bye, `kh` re-pairing and the "Open NavMap?" prompt can't be proven here. Turns, pairing, consent and the key all travel over `/nav` polls, and those work.

## Launch

```
$V up                     # demo ride starts on NavMap's first poll
$V up --no-ride           # idle until "$V phone ride"
$V up --no-consent        # like a NavBridge whose user hasn't tapped Agree
$V up --fresh-watch       # delete NavMap's storage first (token and key), as a new watch would be
```

`up` does the following:
1. Builds `dist/NavMap-sim.prg` from the working tree.
2. Makes sure `UseHttpsRequirements=0` is set in `~/.Garmin/ConnectIQ/simulator.ini`. Without it, every `/nav` poll fails with -1001.
3. Starts `sim-phone.py serve` and the simulator, and loads NavMap on fr165.
4. Waits for NavMap's first `/nav` poll.

Ready means it printed `ready. run folder: verify-out/<run-id>`. That takes about 25 s.

If `up` fails partway, run `$V down` before trying again.

## Doctor

```
$V doctor
```

This is read-only. It checks that this run's `sim-phone.py` owns 127.0.0.1:18165, that Xvfb, the simulator and the app session are alive, that the HTTPS setting is off and the device is fr165, and that `dist/NavMap-sim.prg` is newer than every file in `watch/`. Then it prints the last line from the phone and from the watch. Run it first whenever anything looks off. A `FAIL` on the build check means you edited `watch/` after `up`: run `down`, then `up` again.

## Drive

| Rider action | Command |
|---|---|
| Get a GPS fix and start moving (Settings > Set Position, GPS quality good, Activity Data play) | `$V gps` (the demo ride's start, 38.85591 -94.79964) or `$V gps <lat> <lon>` |
| Start navigating in Google Maps | `$V phone ride` (not needed after a plain `up`) |
| Stop navigating | `$V phone stop` |
| Tap Agree in NavBridge, or withdraw consent | `$V phone agree`, `$V phone withdraw` |
| Any message NavBridge can send | `$V phone send '<json>'` (`"a":"RIGHT"` expands to a drawn arrow; the protocol is in `android/core/src/main/kotlin/com/cubevg/navmap/core/WatchProtocol.kt`) |
| Wait for something | `$V wait '<regex>' [secs]` on the phone log; add `--app` for the watch console |

Notes on driving:
- Run `gps` after `up`, never before. Loading an app stops the simulated activity.
- A modal dialog stalls the simulator's menus. If `gps` fails with "menu did not open", look at `~/opt/ciq-sim/logs/dialogs.log`.
- Never send Escape to the simulator. It is the watch's Back button, and NavMap then asks "Exit NavMap?".

What to wait for:
- **Phone log** (`verify-out/<run>/sim-phone.log`). It logs only what changed, so it is quiet mid-ride. Useful lines: `Paired with NavMap`, `NavMap has the Google Maps key now`, `NavMap sends its position with /nav`, `/nav -> {"n": "<dm>|<ah>|<rv>|<distance>|<eta>|<instruction>"…}`, `/nav -> 403, not the paired watch`, `Ride stopped`, `Ride finished`.
- **Watch console** (`~/opt/ciq-sim/logs/app.log`). Useful lines: `gps: all satellite systems`, `nav answer`, `route <n> chars, <n> points…`, `gps fix <lat>,<lon>`, `map 401 z16 4.1s`. A crash prints `Error: Unhandled Exception` with a stack trace, and the watch shows `IQ!`.

## Evidence

```
$V shot <name>            # verify-out/<run>/<name>.png, the 390x390 round screen, and a line in journal.txt
```

Each run folder `verify-out/<run-id>/` (gitignored) holds:
- `journal.txt`: every step, with the commit, and the last phone and watch lines at each shot.
- `sim-phone.log`
- `build.log` and `start.log`
- After `down`: `watch-console.log` and `dialogs.log`

Proof standards:
- **Drive the rider's path.** Use the fake phone's own commands and the simulator's GPS. Don't change `watch/` to force a state, and don't call internal functions.
- **Take a shot before and after each action**, and pair each with the log line that caused it. One final screen is not proof.
- **Check both sides.** A visible change needs its matching phone-side line, such as `NavMap sends its position with /nav` or `/nav -> {"t": "end"…}`, and the reverse.
- **Look at every screenshot** before calling it proof. `No map: error 401` is expected here. `IQ!`, `NavBridge not answering` or `Phone not connected` mean something is wrong.
- **Never claim more than you saw.** Map pictures, transmits and anything on a real phone or watch are not proven by this skill. Name them as unverified.

## Cleanup

```
$V down
```

`down` copies the watch console and dialogs into the run folder, stops the harness (`~/opt/ciq-sim/stop.sh`) and kills the process group of this run's `sim-phone.py`. It never kills by name. It warns if any evidence file holds a Google Maps key (`sim-phone.py` logs only the key's last 6 characters, so none should). Evidence stays in `verify-out/<run-id>/`.

`--fresh-watch` deletes NavMap's simulator storage, which holds the pairing token and the key in plain text: `~/opt/ciq-sim/tmp/com.garmin.connectiq/GARMIN/APPS/DATA/NAVMAP-FR165.*`. Without that flag the storage carries over between runs, so the watch is already paired. `sim-phone.py` pairs with the first token it sees, so that is harmless.

## Phone app (NavBridge)

- `tools/build-phone.sh` runs the core unit tests (the notification parser against 174 captured Google Maps notifications, the codec, pairing and the road planner) and writes `dist/NavBridge.apk`. On this server it takes about a minute and passes.
- To drive NavBridge itself you need a phone on adb. See `features/navbridge-on-phone.md`.

## Feature map

`features/README.md` indexes one recipe per rider-facing feature. Use the matching file, cover every entry point it lists, and update the map when a feature changes (`/pstack:maintain-verification-skill`).
