# Consent

Google's terms require the rider's consent before NavBridge obtains their location. Until the rider taps Agree in NavBridge, every `/nav` poll gets `{"t":"consent"}`: no key, no turns, and nothing in the poll is read. NavMap then drops its key and route line, sends no position, and shows "Open NavBridge to agree". Withdrawing consent returns to that state.

## Sub-features

- `consent-wait`: before Agree, the watch shows `Open NavBridge to agree` and sends no position.
- `consent-agree`: after Agree, pairing, the key and turns follow.
- `consent-withdraw`: after a withdrawal, the watch drops the key and stops sending its position.

## How to get to it (user POV)

- Install NavBridge and open NavMap before agreeing on NavBridge's first screen.
- In NavBridge, go to Privacy and terms, then Withdraw consent.

## Driving it with navmap-verify

Preconditions:

- `$V up --no-consent --fresh-watch` is ready, and `$V doctor` is clean.

- **Waiting.** Run `$V gps`, wait 6 s, then run `$V shot consent-before`. The screen is black, with `No map key yet` and `Open NavBridge to agree`. The phone log never has `Paired with NavMap`.
- **No position.** Run `$V wait 'NavMap sends its position' 20`. It must time out: NavMap sends its position only while it holds the key.
- **Agree.** Run `$V phone agree`, then `$V wait 'Paired with NavMap'` and `$V wait 'has the Google Maps key now'`. The phone log shows `The user agreed in NavBridge`.
- **After.** Run `$V shot consent-after`. The turn banner, the route line and `Arrive hh:mm` show (the demo ride starts on the first paired poll).
- **Withdraw.** Run `$V phone withdraw`, wait 8 s, then run `$V shot consent-withdrawn`. `No map key yet` and `Open NavBridge to agree` show again, and the phone log has `Ride stopped` and `NavMap doesn't send its position with /nav`.

Proven on 2026-10-28 (commit ce656b2), every step: there was no position for 15 s before Agree; after Agree, pairing, the key and the position came within 6 s; after a withdrawal, the position stopped within 4 s.

## Gotchas

- Without `--fresh-watch`, the watch may still hold the key from an earlier run. The "no position" step then proves nothing about a new install.
- `phone withdraw` also stops `sim-phone.py`'s demo ride.
- `sim-phone.py` answers consent before pairing, as NavBridge does. A poll during consent never pairs.
