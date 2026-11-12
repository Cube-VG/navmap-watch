# Garmin Connect IQ listing copy

Paste-ready text:

- `description.txt` — full listing description
- `whats-new.txt` — release notes

## Suggested title and publisher

The live listing currently reads "NavMap: Googl…" and is published by "Gmaps". Before the next review, change it to:

- **Title:** `NavMap — Turn-by-turn for Garmin`
- **Publisher:** `NavMap` or `CubeVG`

Do not lead the title with Google or Garmin branding, and do not publish under a name that reads like a Google account.

## What was corrected

- **Not vector maps.** The watch displays Google Maps map images plus its own route line and UI. The copy now says "map view" and "resolution-aware layout", not "vector rendering".
- **Not zero telemetry.** The Android app sends anonymous crash and diagnostic reports through Firebase Crashlytics. The copy discloses this instead of claiming no analytics.
- **Not zero cloud.** Map pictures and routes go to Google Maps Platform with the user's own key, and phone-watch traffic goes through Garmin Connect. The copy says "no NavMap servers", which is true, rather than "no third-party servers".
- **Offline is bounded.** Turns, distances, and the route line already received keep working; new map images and rerouting need data.
- **Route line is not persisted across app restarts.** It is kept during the current navigation session, not cached for a future ride.
- **Trial and price must be disclosed.** Garmin requires apps that ask for payment to say the app is free only for a limited time. The copy states seven days free, then the one-time price.

## Before submitting

The international checkout is currently gated because Razorpay cannot take international cards on this account. PayPal Orders v2 is built and sandbox-tested, but live approval and a live webhook are still pending. Do not submit the listing as globally purchasable until the PayPal checkout is live, or non-India buyers will see "international checkout is being set up" after starting the unlock flow.
