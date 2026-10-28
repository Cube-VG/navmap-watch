# Turn guidance

While Google Maps navigates, NavMap shows the next turn in a banner across the top: Google's arrow, the distance in big type and the instruction. Below that is the map, turned so the way you face is up, with the blue route line from your position, then the arrival time and "Google Maps". The distance counts down between NavBridge's answers from the watch's own GPS speed.

## Sub-features

- `turn-banner`: the arrow, distance and instruction from the `n` line of a `/nav` answer.
- `route-line`: the blue line from the closest point on the route (`P` in the answer).
- `eta`: the `Arrive hh:mm` pill.
- `countdown`: the distance falls between answers while the watch moves.
- `turn-now`: the banner turns blue about 6 s before the turn (with three buzzes on a real watch).

## How to get to it (user POV)

- Start navigating in Google Maps while NavMap is open. On the phone, NavBridge opens NavMap.
- Share a place to "Navigate with NavMap". NavBridge computes the route line. This needs a real phone.
- Tap "Simulate navigation" in NavBridge. This needs a real phone; here it is stood in for by `sim-phone.py`'s demo ride.

## Driving it with navmap-verify

Preconditions:

- `$V up` (with the demo ride) is ready, and `$V doctor` is clean.

- **Ride starts.** NavMap's first poll starts the demo ride. Run `$V wait 'Demo ride from'`. The phone log shows `Demo ride from 38.85591,-94.79964: 1500 m at 12.0 m/s`, then `/nav -> {"n": "…|250 m|Arrive …|Turn right onto Test Street", "A": …, "P": …}`. The watch console shows `nav answer` and `route 23 chars, 5 points…`.
- **Rider moves.** Run `$V gps`, then `$V wait 'gps fix' 40 --app` and `$V wait 'NavMap sends its position'`.
- **Banner.** Run `$V shot turn-banner`. The shot shows a dark green banner with an arrow, a distance in metres and `Turn … onto …`, the blue route line from the position marker, and `Arrive hh:mm` and `Google Maps` at the bottom. `No map: error 401` in the middle is expected here.
- **Next turn.** Wait for the next instruction, `$V wait 'Example Lane|Sample Avenue' 90`, then run `$V shot next-turn`. The banner now shows the new street, with the arrow from that answer.
- **Turn now.** Shoot within about 6 s of a turn (the `dm` field, the first in `n`, under about 70 m at 12 m/s). The banner is blue.

Proven on 2026-10-28 (commit ce656b2): `turn-banner` showed `Turn left onto Sample…`, 140 m, the route line and `Arrive 23:32`.

## Gotchas

- The phone log holds only answers that changed. Steady countdown polls aren't logged, so a quiet log mid-ride is normal.
- The demo ride's distances come from `sim-phone.py` on a timer, not from the simulator's GPS track (a run at about 9 m/s from the set position). The marker and route line don't follow each other the way a real ride's would.
- Map pictures never load in this simulator (no Garmin Connect sign-in). Don't report the map as proven.
- Buzzes and the backlight can't be seen in a screenshot. `turn-now` is proven only by the banner's colour.
