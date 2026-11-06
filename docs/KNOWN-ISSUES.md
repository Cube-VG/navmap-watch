# Known limitations at launch

Things that are true today and will stay true unless someone does the work. Written down so support answers are
consistent and nobody discovers them from a one-star review.

## 1. The seven-day trial resets if the app is reinstalled

The trial clock lives in the app's own storage (`getTrialDaysRemaining()` in `watch/source/NavMapApp.mc`), because
Garmin gives developers no server-side clock for trials. Uninstalling NavMap clears that storage, so reinstalling
starts another seven days.

**Mitigation:** none, short of writing the start date somewhere Garmin controls. Accept it: it costs a determined
person a reinstall, and fighting it would mean a backend and a licence check.

## 2. Someone can be charged twice

Each press of **Unlock** in the Connect IQ Store arrives with a *new* `appUnlockRequestId`, and the unlock service has
no way to recognise the same person. If Garmin shows Unlock again to someone who already bought it, they can pay
again.

**Mitigation:** Garmin normally hides Unlock once the app is owned, so this should be rare. Refund the duplicate in
Razorpay when it happens.

## 3. A refund does not revoke the unlock

Garmin holds the unlock against the account, and the service has no switch to take it back. A refunded customer keeps
working software.

**Mitigation:** stated plainly in `docs/refunds.html`. Refunds are given in good faith, and repeated requests can be
declined.

## 4. iOS is not supported and is not planned

iOS does not let any app read another app's notifications, so NavBridge cannot see Google Maps' turns there. Every
"workaround" (Live Activities, ANCS, Accessibility, Shortcuts) is closed to third-party apps. The only way to build
it would be a separate app that does its own routing, which is a different product.

**Mitigation:** the README and the site say so before someone buys the watch app.

## 5. The store package needs all 19 device files installed

`tools/build-watch.sh --package` exports one `.iq` covering every product in the manifest, and `monkeyc` fails if any
of them is missing:

```
ERROR: Device ids 'fr265s', 'd2mach1', 'venu3s', … are not recognized.
```

The Connect IQ SDK Manager installs them; a machine that only ever built `fr165` will have four and needs the rest.

**Mitigation:** in the SDK Manager, add every watch listed in `watch/manifest.xml` before a `--package` build.

## 6. A store build is refused unless the unlock URL answers

`build-watch.sh --publish/--package` checks that `<iq:unlockURL>` actually responds. A `404` or a dead connection
stops the build, because uploading it would lock every buyer out on day 8.

**Mitigation:** deploy the unlock service first; `NAVBRIDGE_SKIP_UNLOCK_CHECK=1` overrides when you know what you are
doing (an offline build, say).

## 7. The site still says "$1.99 planned"

`docs/index.html` shows "planned one-time price". Once the store charges, "planned" has to go — but the real price
hasn't been confirmed yet, and publishing a number that isn't charged is worse than an out-of-date word.
