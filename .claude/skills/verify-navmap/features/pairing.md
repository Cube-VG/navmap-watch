# Pairing

The first time it runs, NavMap makes a 32-hex token (Storage `tok`) and sends it as `k` with every poll. NavBridge keeps the first well-formed token it sees while pairing is open, and from then on answers only that one. A watch with any other token gets 403 and shows "Open NavBridge to pair".

## Sub-features

- `pair-first`: a new watch's first poll pairs, and the key follows.
- `pair-refused`: a watch with another token gets 403 and shows `Open NavBridge to pair`.
- `pair-again`: "Pair again" in NavBridge forgets the paired watch. This needs a real phone.

## How to get to it (user POV)

- Install NavMap and open it while NavBridge is on screen (pairing is open for 10 minutes).
- Reinstall NavMap under another `.prg` name, or on another watch.

## Driving it with navmap-verify

Preconditions:

- `$V up --fresh-watch` is ready.

- **First pairing.** Run `$V wait 'Paired with NavMap'` and `$V wait 'has the Google Maps key now'`. Then `$V gps`, and `$V shot paired`, which shows the turn banner.
- **Another watch.** Run `$V down`. Then run `$V up --fresh-watch` again: this starts a new `sim-phone.py`, which pairs afresh, so it is not a refusal. To see a refusal, keep the phone and give the watch a new token. With the run still up, delete `~/opt/ciq-sim/tmp/com.garmin.connectiq/GARMIN/APPS/DATA/NAVMAP-FR165.*` and reload with `~/opt/ciq-sim/load.sh dist/NavMap-sim.prg fr165`. Then run `$V wait '403, not the paired watch'` and `$V shot pair-refused`, which shows `Open NavBridge to pair`.

Not yet proven with this skill.

## Gotchas

- `sim-phone.py` pairs with the first token it ever sees and has no pairing window, so it can't show the 10-minute limit or "Pair again".
- `kh` re-pairing (a reinstalled NavMap whose hello matches) needs a transmit, which the simulator build never does.
- The watch keeps its storage under the app's file name. On a real watch, a NavMap copied under another `.prg` name makes a new token and is refused.
