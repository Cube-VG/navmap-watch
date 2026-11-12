# NavMap unlock service

The endpoint Garmin's trial sends a buyer to, and the only thing standing between "trial expired" and
"paid and unlocked". It runs on Cloudflare Workers (free plan) with one KV namespace.

```
Watch trial ends ──▶ Connect IQ Store ──▶ GET /unlock?appUnlockRequestId=…&callbackUrl=…&appPageUrl=…
                                              │  stores request + price, renders page
                                              ▼  buyer presses Pay
                                       POST /api/create-order ──▶ Razorpay order
                                              │
                                              ▼  Razorpay Checkout modal
                                        payment captured
                                              │
                        POST /api/verify-payment  (signature checked by the browser path)
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

## Checkout endpoints

`/unlock` renders the payment page and stores the Garmin request in KV. India uses Razorpay Standard Checkout;
everyone else uses PayPal Orders v2:

| Route | Purpose |
|---|---|
| `POST /api/create-order` | Razorpay only. Body `{ "requestId": "…" }`. Creates or reuses the INR order, returns `{ order_id, amount, currency, key_id }`. |
| `POST /api/verify-payment` | Razorpay only. Checks `HMAC-SHA256(order_id + "|" + payment_id, key_secret)` and returns `{ success: true }`. |
| `POST /api/paypal/create-order` | PayPal only. Body `{ "requestId": "…" }`. Creates or reuses a USD order and returns `{ order_id, amount, currency }`. |
| `POST /api/paypal/capture-order` | PayPal only. Body `{ "requestId": "…", "orderId": "…" }`. Captures the order, verifies the amount/currency, and calls Garmin. |
| `POST /api/paypal/webhook` | PayPal's server-to-server `PAYMENT.CAPTURE.COMPLETED` event. Verifies the signature through PayPal's verify-webhook API, then calls Garmin. |
| `POST /razorpay/webhook` | Razorpay's server-to-server event. Verifies the HMAC, then calls Garmin. |

`create-order` returns `400` for a missing request id, `404` for an unknown request, `409` if the unlock is already
complete, `503` if Razorpay keys are missing, `401` if Razorpay rejects the credentials, and `500` for other gateway
or network failures.

`verify-payment` returns `400` for missing fields or a bad signature and `404` for an unknown order. It does **not**
mark anything paid and does **not** unlock; Razorpay's webhook remains authoritative for INR.

Both PayPal capture and either provider's webhook go through one `completeUnlock()` function, so a retry cannot
unlock twice and a duplicate delivery is harmless.

## Pricing by country

India is charged **₹199 (INR)** so Razorpay Checkout can offer UPI. Everywhere else is charged **US$1.99 (USD)**.
`PRICE_INR` and `PRICE_USD` in `wrangler.toml` are in minor units (`19900`, `199`). The Worker reads Cloudflare's
`request.cf.country` and falls back to the `CF-IPCountry` header; a blank or unknown country is USD.

The first open of an `appUnlockRequestId` fixes the amount and currency in KV. The order id is added when the buyer
presses Pay; later loads and button presses reuse the same order, so changing network or country mid-flow cannot
create a second charge at a different price.

Razorpay cannot take international cards for this account, so the deployed Worker keeps `INTERNATIONAL_CHECKOUT=off`
and shows an "international checkout is being set up" page outside India until PayPal is live. Local testing sets the
flag to `on` in `.dev.vars`. UPI is offered only on the INR order, which is why India must not be switched to USD.

## Setup

You need a Cloudflare account (free), a Razorpay account for INR/UPI, and a PayPal app for USD.

```bash
cd unlock
npm install -g wrangler        # or: npx wrangler
wrangler login

# 0. Local secrets. Workers read .dev.vars, not .env; the real file is gitignored.
cp .dev.vars.example .dev.vars
#    fill RAZORPAY_*, PAYPAL_*, GARMIN_*, and set INTERNATIONAL_CHECKOUT=on for local testing

# 1. KV namespace for the order/request mapping
wrangler kv namespace create ORDERS
#    paste the printed id into wrangler.toml

# 2. Secrets
wrangler secret put RAZORPAY_KEY_ID          # rzp_test_… while testing
wrangler secret put RAZORPAY_KEY_SECRET
wrangler secret put RAZORPAY_WEBHOOK_SECRET  # set this same value in the Razorpay webhook
wrangler secret put PAYPAL_CLIENT_ID         # public client id is also rendered on the page
wrangler secret put PAYPAL_CLIENT_SECRET
wrangler secret put PAYPAL_WEBHOOK_ID        # from the PayPal webhook you create
wrangler secret put GARMIN_OAUTH_KEY         # Connect IQ Developer Dashboard
wrangler secret put GARMIN_OAUTH_SECRET

# 3. Deploy
wrangler deploy
```

Then point `navmap.download/unlock` at it. If you use Worker routes on `navmap.download`, route **all** of
`/unlock`, `/api/*`, `/return` and `/razorpay/*` to the Worker (see `wrangler.toml`); routing only `/unlock` sends
the page's `/api/...` calls to GitHub Pages and they 404. Alternatively, keep `navmap.download/unlock` as a redirect
to the `*.workers.dev` URL, where the relative `/api/...` calls already reach the Worker. Garmin only cares that the
manifest's `unlockURL` answers over HTTPS.

## Razorpay webhook

In the Razorpay dashboard, add a webhook:

- **URL:** `https://<your-worker>/razorpay/webhook`
- **Secret:** the same value as `RAZORPAY_WEBHOOK_SECRET`
- **Events:** `payment.captured` (and `payment.failed` if you want the log line)

The signature is checked against the raw request body, so the route must receive the body untouched.

## PayPal webhook

In the PayPal app, add a webhook:

- **URL:** `https://<your-worker>/api/paypal/webhook`
- **Event:** `PAYMENT.CAPTURE.COMPLETED`
- Copy the webhook id into `PAYPAL_WEBHOOK_ID`.

The Worker verifies each delivery through PayPal's `verify-webhook-signature` API before it calls Garmin. The
`capture-order` endpoint already unlocks after a successful capture; the webhook is the backup for a buyer who closes
the tab before the capture response arrives.

## Local testing

```bash
npm test          # 31 tests, no network: signatures, PayPal capture/webhook, geo pricing, idempotency
wrangler dev      # then open http://localhost:8787/unlock?…
```

`.dev.vars` sets `ALLOW_COUNTRY_OVERRIDE=1` for local testing, so append `&country=IN` or `&country=US` to the
unlock URL to exercise both prices without a VPN. A real Cloudflare deployment must not set that flag; there
`request.cf.country` is used.

### Test payments

- **India / ₹199:** choose UPI and use Razorpay's test UPI id `success@razorpay` (or `failure@razorpay`). This avoids
  the international-card restriction on the test account. See [Razorpay's test UPI details](https://razorpay.com/docs/payments/payments/test-upi-details/).
- **Outside India / US$1.99:** set `PAYPAL_ENV=sandbox`, `INTERNATIONAL_CHECKOUT=on`, and a sandbox
  `PAYPAL_CLIENT_ID`/`PAYPAL_CLIENT_SECRET` in `.dev.vars`. Open the unlock URL with `&country=US`, click the PayPal
  button, and approve with a PayPal sandbox buyer account. The capture endpoint then calls Garmin.
- **PayPal webhook:** set `PAYPAL_WEBHOOK_ID` to a sandbox webhook pointing at `/api/paypal/webhook` if you want to
  test the signature/capture-retry path; a tunnel is needed for PayPal to reach localhost.

The OAuth1 tests include the published OAuth 1.0a worked example (the Twitter vector), so the signature
implementation is checked against a known-good value rather than against itself.

The service calls Razorpay and PayPal REST APIs with `fetch`; no Node SDKs are installed because they use Node APIs
that are not available in a Worker. With test keys in `.dev.vars`, use Razorpay's test UPI/card details and a PayPal
sandbox buyer to exercise the checkouts.

To exercise the flow without live providers, stub the calls in tests; the service fails closed (503
"Payments aren't switched on") when a provider's keys are missing.

## Rules this service enforces

- **Unlock only after a verified payment.** The `/return` page is cosmetic; anyone can open it. Razorpay's webhook
  HMAC, PayPal's capture response, or PayPal's verified webhook is what unlocks anything.
- **Razorpay checkout signatures are checked but do not unlock.** `/api/verify-payment` proves the browser's payment
  id and order id came from Razorpay, then redirects to `/return`; the Razorpay webhook remains authoritative.
- **Never unlock twice.** `done:<requestId>` is checked before calling Garmin; a redelivered webhook is a no-op.
  Garmin's `409 Conflict` ("already being marked as unlocked") is treated as success.
- **Only call Garmin.** `callbackUrl` must be an `https://*.garmin.com` URL, otherwise `/unlock` would be an open
  request forwarder.
- **Amount and currency must match.** The webhook's payment amount and currency are compared with the stored order
  before Garmin is called. A mismatch is logged and ignored, never unlocked.
- **Retry on Garmin failure.** A non-2xx from Garmin returns 500 to Razorpay, which redelivers — the customer has
  already paid, so giving up silently is not an option.

## Operating notes

- Watch the Worker logs for `garmin refused the unlock` and `rejected a webhook with a bad signature`. The first
  means a paying customer is stuck; the second usually means the webhook secret is out of sync.
- A USD request showing "being set up" is expected while `INTERNATIONAL_CHECKOUT=off`; integrate the second provider
  before turning it on.
- KV records expire after 90 days, which is long enough to answer "I paid and it isn't unlocked" emails.
- Refunds are manual: issue them in Razorpay. There is no automatic revocation, so a refunded user keeps the app —
  acceptable at this price, and worth knowing before you promise otherwise in the refund policy.
