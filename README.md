# NavMap

A real Google map on the Garmin Forerunner 165 that follows your GPS and stays on screen while you navigate. Built in public.

**Status:** first watch test build. The Android companion app (auto-launch on Google Maps navigation, turn arrows, route line) is next.

## What the watch app does
- Dark-style Google map tiles centred on your position, sliding as you move
- Zooms out automatically with speed
- Runs as a plain watch app (no activity session); when the screen dims it shows only the next turn or the time
- Maps come in a 14-colour palette, about a quarter of the Bluetooth and memory cost of full colour
- Up/Down zoom instantly (the current map is scaled until the sharp one arrives), Start shows a debug panel, Back asks before exiting
- Talks to the NavBridge phone app: says hello until it answers, then shows turns, distance, ETA and the route line

## Build
Needs the Connect IQ SDK, a developer key and your own Google Maps Static API key.

1. Put `MAPS_API_KEY=...` in `~/.config/navmap/secrets.properties`
2. Put your developer key at `~/.config/navmap/developer_key.der`
3. Run `tools/build-watch.sh fr165` (or `fr165m`)

`watch/source/Secrets.mc` is generated at build time and never committed.
