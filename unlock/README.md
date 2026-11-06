# NavMap unlock service

The endpoint Garmin's trial sends a buyer to, and the only thing standing between "trial expired" and
"paid and unlocked". It runs on Cloudflare Workers (free plan) with one KV namespace.

```
Watch trial ends ──▶ Connect IQ Store ──▶ GET /unlock?appUnlockRequestId=…&callbackUrl=…&appPageUrl=…
                                              │
                                              ▼  Razorpay Checkout
                                        payment captured
                                              │
                                              ▼  POST /razorpay/webhook  (signature checked)
                                  GET {callbackUrl}  (OAuth1, HMAC-SHA1)
                                              │
                                      200 / 202 / 409 ──▶ Garmin marks the app unlocked
```

## What it does not do

- It does not decide whether a user is in their trial. **The watch does that** (`getTrialDaysRemaining()` in
  `watch/source/NavMapApp.mc`), and Garmin's platform enforces it by refusing to run the app at zero.
- It does not store card details. Razorpay's checkout handles the payment; this service only ever sees an order id.

## Setup

You need a Cloudflare account (free) and a Razorpay account.

```bash
cd unlock
npm install -g wrangler        # or: npx wrangler
wrangler login

# 1. KV namespace for the order/request mapping
wrangler kv namespace create ORDERS
#    paste the printed id into wrangler.toml

# 2. Secrets
wrangler secret put RAZORPAY_KEY_ID          # rzp_test_… while testing
wrangler secret put RAZORPAY_KEY_SECRET
wrangler secret put RAZORPAY_WEBHOOK_SECRET  # set this same value in the Razorpay webhook
wrangler secret put GARMIN_OAUTH_KEY         # Connect IQ Developer Dashboard
wrangler secret put GARMIN_OAUTH_SECRET

# 3. Deploy
wrangler deploy
```

Then point `navmap.download/unlock` at it, either with a Worker route (see `wrangler.toml`) or by keeping
`https://navmap.download/unlock` as a redirect to the `*.workers.dev` URL. Garmin only cares that the manifest's
`unlockURL` answers over HTTPS.

## Razorpay webhook

In the Razorpay dashboard, add a webhook:

- **URL:** `https://<your-worker>/razorpay/webhook`
- **Secret:** the same value as `RAZORPAY_WEBHOOK_SECRET`
- **Events:** `payment.captured` (and `payment.failed` if you want the log line)

The signature is checked against the raw request body, so the route must receive the body untouched.

## Local testing

```bash
npm test          # 14 tests, no network: signing, routing, idempotency, signatures
wrangler dev      # then open http://localhost:8787/unlock?…
```

The OAuth1 tests include the published OAuth 1.0a worked example (the Twitter vector), so the signature
implementation is checked against a known-good value rather than against itself.

To exercise the flow without Razorpay, set `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` to dummy values and stub
the calls; the service fails closed (503 "Payments aren't switched on") if either is missing.

## Rules this service enforces

- **Unlock only on a verified webhook.** The `/return` page is cosmetic; anyone can open it. Razorpay's HMAC
  signature is the only thing that unlocks anything.
- **Never unlock twice.** `done:<requestId>` is checked before calling Garmin; a redelivered webhook is a no-op.
  Garmin's `409 Conflict` ("already being marked as unlocked") is treated as success.
- **Only call Garmin.** `callbackUrl` must be an `https://*.garmin.com` URL, otherwise `/unlock` would be an open
  request forwarder.
- **Retry on Garmin failure.** A non-2xx from Garmin returns 500 to Razorpay, which redelivers — the customer has
  already paid, so giving up silently is not an option.

## Operating notes

- Watch the Worker logs for `garmin refused the unlock` and `rejected a webhook with a bad signature`. The first
  means a paying customer is stuck; the second usually means the webhook secret is out of sync.
- KV records expire after 90 days, which is long enough to answer "I paid and it isn't unlocked" emails.
- Refunds are manual: issue them in Razorpay. There is no automatic revocation, so a refunded user keeps the app —
  acceptable at this price, and worth knowing before you promise otherwise in the refund policy.
