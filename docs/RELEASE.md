# Releasing the paid watch app

The order matters. Uploading the watch package before the unlock service answers locks every buyer out on day 8,
permanently, with no way to pay. This checklist exists to make that impossible to do by accident.

**Current state (2026-11-11):** the Worker is deployed with live Razorpay keys, the Garmin OAuth keys and the live
webhook. All four routes are live. India/₹199 works; countries outside India see an "international checkout is being
set up" page because Razorpay cannot take international cards for this account.

## 0. Before any of this

- [ ] `docs/refunds.html` is live at <https://navmap.download/refunds.html> (Garmin's review guidelines require a
      stated refund policy, and the payment page links it)
- [ ] The listing description says the app is **free for seven days and then requires payment**, and repeats the
      refund policy
- [ ] You know today's state of the live app, because a monetisation change means a **re-review**, during which
      Garmin removes the app from the store

## 1. Deploy the unlock service

```bash
cd unlock
wrangler login
wrangler kv namespace create ORDERS          # paste the id into wrangler.toml
wrangler secret put RAZORPAY_KEY_ID
wrangler secret put RAZORPAY_KEY_SECRET
wrangler secret put RAZORPAY_WEBHOOK_SECRET
wrangler secret put GARMIN_OAUTH_KEY
wrangler secret put GARMIN_OAUTH_SECRET
wrangler deploy
```

- [ ] Production uses **live** `rzp_live_…` keys, not the `rzp_test_…` keys used on staging
- [ ] Razorpay webhook points at `https://<worker>/razorpay/webhook`, events `payment.captured`, with the same
      secret as `RAZORPAY_WEBHOOK_SECRET`
- [ ] A second provider is live for USD (Razorpay cannot take international cards here); an INR order still offers UPI

## 2. Prove it answers before touching the manifest

`watch/manifest.xml` points at `https://navmap.download/unlock`. Whatever you deploy must answer there.

- [ ] `curl -i https://navmap.download/unlock` returns a page, **not** a 404, 5xx, or Cloudflare error page
- [ ] `https://navmap.download/api/create-order` reaches the Worker (an empty `POST` returns JSON `400`, not a
      GitHub Pages `404`)
- [ ] Open it with Garmin-style parameters and check the payment page renders:
      `/unlock?appUnlockRequestId=test-1&callbackUrl=https%3A%2F%2Fapps.garmin.com%2FappUnlock%3FappUnlockRequestId%3Dtest-1&appPageUrl=https%3A%2F%2Fapps.garmin.com%2Fapps%2Ff18fe040-5bb7-41a0-afb0-f61edf418dab`
- [ ] The page shows **US$1.99** for a non-Indian visitor and **₹199** with `CF-IPCountry: IN`
- [ ] `POST /api/create-order` returns `order_id`, `amount`, `currency` and `key_id` for that request, and a second
      call returns the same order instead of creating another
- [ ] `POST /api/verify-payment` returns `400` for a bad signature and `{ "success": true }` for the correct one
- [ ] A **test-mode** payment completes end to end in **both** currencies: checkout → webhook → Garmin callback
      returns 200/202 → `/return` says unlocked

**STOP if this is not green.** Do not go to step 3.

## 3. Confirm the manifest's unlock URL

- [ ] `watch/manifest.xml` has the real URL in `<iq:unlockURL>` (currently `https://navmap.download/unlock`)
- [ ] Rebuild and diff the manifest into the package (`monkeyc -e` embeds it)

## 4. Build the store package

The package must be signed with **your real developer key**. A build signed with a throwaway key may be rejected or
treated as a different identity.

```bash
# the real key, not a test one
ls -l ~/.config/navmap/developer_key.der

bash tools/build-watch.sh fr165 --publish --package
# → dist/NavMap-publish.iq
```

The script refuses to build a store package while `<iq:unlockURL>` doesn't answer, because uploading one would lock
buyers out. `NAVBRIDGE_SKIP_UNLOCK_CHECK=1` overrides that for an offline build.

**Every device in the manifest needs its files installed**, not just `fr165`: `--package` exports one `.iq` covering
all 19 products, and `monkeyc` fails with `Device ids … are not recognized` if any is missing. Add the rest in the
Connect IQ SDK Manager first.

- [ ] `dist/NavMap-publish.iq` exists
- [ ] Built with the release key, not `NAVBRIDGE_WATCH_KEY` pointing at a temporary one
- [ ] Version in the package is higher than what is live
- [ ] The unlock URL answered during the build (the guard did not fire, or was knowingly skipped)

## 5. Upload

In the Connect IQ Developer Dashboard:

- [ ] Upload `dist/NavMap-publish.iq`
- [ ] Monetisation: **Yes, this app requires payment**; trial enabled via the manifest's `trialMode`
- [ ] Support email set (needed for review, and for refunds)
- [ ] Privacy policy URL: <https://navmap.download/privacy.html>
- [ ] Refund policy linked in the description: <https://navmap.download/refunds.html>
- [ ] Submit for review

## 6. The day it goes live

- [ ] Install from the store on a real watch and confirm the trial counter is present
- [ ] Buy it once with a real card, small amount, and confirm the unlock lands
- [ ] Watch the Worker logs for `garmin refused the unlock` and `rejected a webhook with a bad signature`

## When something goes wrong

| Symptom | Likely cause | Fix |
|---|---|---|
| Trial users blocked, unlock page 404s | Worker not deployed, or route not pointing at it | Redeploy / fix the route. The app stays blocked until the URL answers |
| Payment taken, app still locked | Garmin callback returned non-2xx | Check the Worker logs; the signature is the usual culprit. Razorpay retries the webhook, so fixing it often resolves itself |
| `401` from Garmin | Wrong OAuth key/secret, or the base string is off | Re-copy the consumer key/secret from the dashboard |
| `409` from Garmin | Already unlocking | Fine — treated as success |
| US checkout shows “being set up” | `INTERNATIONAL_CHECKOUT=off` because Razorpay cannot take international cards | Integrate the second USD provider, then set the flag to `on` |
| Someone charged twice | Paid, then tapped Unlock again | Refund in Razorpay |

## Rolling back

There is no "unpublish trial" switch that restores paid-up-front behaviour instantly. If the unlock service has to be
taken down, **leave the current store version in place** rather than shipping a build with a trial whose unlock URL is
dead. A stuck app is worse than an old one.
