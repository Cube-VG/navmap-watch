# Navigation end

When the rider stops navigating in Google Maps, or arrives, NavBridge answers `{"t":"end","ar":<arrived>}`. NavMap shows "Navigation ended" (or "Arrived"), drops the route, and closes itself 5 s later, back to the watch face (`MapView.onTick`).

## Sub-features

- `end-stop`: the rider stops navigating. The watch shows `Navigation ended`.
- `end-arrive`: the rider arrives. NavMap shows `Arriving at …`, then `Arrived` after NavBridge's 6 s grace period.
- `auto-exit`: NavMap closes 5 s after the end.

## How to get to it (user POV)

- Tap Exit navigation in Google Maps.
- Ride to the destination.
- Tap "Stop simulation" in NavBridge.

## Driving it with navmap-verify

Preconditions:

- `$V up` is ready, `$V gps` has run, and the ride shows a turn banner (see `turn-guidance.md`).

- **Before.** Run `$V shot before-end`. The turn banner shows.
- **Stop.** Run `$V phone stop`, then `$V wait '"t": "end"'`. The phone log shows `Ride stopped` and `/nav -> {"t": "end", "ar": false}`.
- **Ended.** Run `$V shot ended` at once: there are only 5 s before NavMap closes. The screen is black with `Navigation ended` at the top, and no banner or route line.
- **Arrive.** In a fresh run, let the 1500 m demo ride finish (about 2 minutes at 12 m/s). Run `$V wait 'Ride finished' 200`, then `$V shot arrived` at once. Expect `Arrived`.
- **Auto-exit.** Wait 10 s after the end, then run `$V shot closed`. NavMap is gone: the simulator shows the watch face. The watch console has no `Unhandled Exception`.

Proven on 2026-10-28 (commit ce656b2): `end-stop`. `ended.png` shows `Navigation ended`, paired with `/nav -> {"t": "end", "ar": false}`. `end-arrive` and `auto-exit` are recipe only.

## Gotchas

- The ended screen lasts about 5 s, plus up to one poll (2 s) before the end answer arrives. A shot taken later shows the watch face. That is `auto-exit` working, not a crash. A crash shows `IQ!`, and `Unhandled Exception` in the watch console.
- `~/opt/ciq-sim/README.md` says NavMap closes 60 s after the end. That was an older NavMap; the current one closes after 5 s.
