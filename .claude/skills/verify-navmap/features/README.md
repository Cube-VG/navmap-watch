# NavMap verification map

This folder is the maintained source for proving NavMap's rider-facing behavior. Read this index first, then use the matching feature file as the recipe.

## Baseline preconditions

- Nothing else is using the simulator. `V up` refuses otherwise. Don't stop another session's simulator.
- `V=.claude/skills/verify-navmap/navmap-verify`, run from the repo root.
- `$V up` has printed `ready. run folder: …`.
- `$V doctor` shows no `FAIL`.
- After `$V gps`, the phone log has `NavMap sends its position with /nav`.

## Driving conventions

- Start every recipe from a fresh `$V up` with the flags its preconditions name, and end it with `$V down`.
- NavBridge's side is `$V phone …`. The rider's movement is `$V gps …`. The screen is `$V shot <name>`.
- Wait on log lines (`$V wait`), not on fixed sleeps. Allow 2 s after the line for the watch to draw the next frame.
- The watch polls `/nav` every 5 s when not navigating, every 2 s while navigating, and every 1 s in the last 500 m before a turn. Allow a poll's time before calling a change missing.

## Proof and skip reporting

- Name each shot after the feature and step (`turn-banner`, `consent-before`, `consent-after`).
- Pair each shot with the phone-log line that caused it. `journal.txt` records both.
- Report unverified anything that needs a real watch or phone: map pictures, buzzes, the backlight, transmits (hello, bye, `kh`) and NavBridge's own screens. Say why.
- Don't report an entry point as verified through a different one. For example, the demo ride is not Google Maps' notification.

## Features

| File | Feature | Proven here |
|---|---|---|
| [turn-guidance.md](turn-guidance.md) | The turn banner, distance, street, arrival time and route line during a navigation | yes, 2026-10-28 |
| [navigation-end.md](navigation-end.md) | Stopping or arriving ends the navigation on the watch | yes, 2026-10-28 (stop) |
| [consent.md](consent.md) | No key, turns or position before the rider agrees in NavBridge | yes, 2026-10-28 |
| [pairing.md](pairing.md) | Only the paired watch gets answers | recipe only |
| [navbridge-on-phone.md](navbridge-on-phone.md) | NavBridge on a real Android phone over adb | needs a phone |

## Feature entry contract

Each feature file starts with an H1 title and a paragraph on what the rider sees. Then come four H2 sections, in this order: `Sub-features`, `How to get to it (user POV)`, `Driving it with navmap-verify` (starting with `Preconditions:`), and `Gotchas`.
