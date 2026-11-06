# Selling NavMap: the watch app pays, the phone app doesn't

NavBridge stays **free** — on the website, on GitHub, and on Google Play once Play finishes verifying it. The
money comes from the **watch app on the Connect IQ Store**: seven days free, then $1.99 once.

This file is the plan for that. It records how the trial works, how the unlock and payment are wired, what it
costs, and what has to be true before anything is uploaded.

## 1. Two ways to charge, and why the trial decides it

Garmin supports two completely separate models. They don't mix.

| | **A. Garmin merchant** | **B. Trial + your own payment** |
|---|---|---|
| How it works | App is listed at a price. Garmin takes the money and pays you. | App is free. Seven-day trial, then the user pays on your page to unlock it. |
| When the user pays | **Before** they can install it | **After** the trial ends |
| Free trial | **Not supported** — the store sells first, installs second | Supported. The platform refuses to run the app once the trial hits zero |
| Who collects | Garmin | You (Razorpay, Stripe, Paddle…) |
| Garmin's cut | 15% | 0% (Garmin takes nothing on an external payment) |
| What you must run | Nothing | A small web service, HTTPS, and a payment account |
| Effort | An afternoon | A few days, then maintenance |

**The trial forces model B.** Garmin's own paid-app flow in
[App Sales](https://developer.garmin.com/connect-iq/articles/monetization/App_Sales.html) is purchase-before-download:
"users receive a message explaining that they must purchase the app before they can use it again." There is no
trial setting on a Garmin-priced app.

Only the
[Trial Apps](https://developer.garmin.com/connect-iq/articles/core-topics/Trial_Apps.html)
feature is time-based, and it works by sending the buyer to **your** HTTPS endpoint. That endpoint is where the
payment happens.

> **Checked 2026-11-06: Garmin has no in-app purchase API.** The Developer Dashboard banner says approved accounts can
> publish apps "that are for sale or offer in-app purchases", but the Connect IQ SDK 9.2.0 has no `Purchase`, `Store`
> or `Billing` namespace in `Toybox` at all, and the monetization docs cover only Merchant Onboarding, App Sales,
> Account Management and Price Points — no IAP page. The two mechanisms are therefore the paid app and the trial, as
> described above. Treat the banner's "in-app purchases" wording as not something you can call from code.

## 2. What the user sees

1. **Day 0** — installs NavMap free from the Connect IQ Store. The trial clock starts on the first launch and is
   stored in application storage, so it survives restarts and app updates.
2. **Days 1–7** — everything works. Garmin's own trial notifications tell the user how many days are left.
3. **Day 8** — `getTrialDaysRemaining()` returns 0 and **the platform refuses to start NavMap.** This is enforced by
   the operating system, not by a screen, so there is nothing in the app to get round.
4. **Unlock** — the user taps Unlock in the Connect IQ Store, gets sent to your payment page, pays $1.99, and Garmin
   marks the app unlocked for their account.
5. **Turns, arrows and distances never stop.** After the Garmin unlock there is no second paywall anywhere: NavBridge
   is free and does not charge for anything.

## 3. The unlock service

This is the one piece that doesn't exist yet. It is a small web app — no framework needed, Cloudflare Workers or a
single-file Vercel/Node service is plenty.

```
Watch (trial)  ──day 8──▶  Connect IQ Store  ──▶  GET /unlock?appUnlockRequestId=…&callbackUrl=…&appPageUrl=…
                                                          │
                                                          ▼
                                              payment page (Razorpay Checkout)
                                                          │  paid
                                                          ▼
                                    GET/POST {callbackUrl}  (OAuth1-signed, HMAC-SHA1)
                                                          │  200 OK
                                                          ▼
                                              redirect → appPageUrl
```

### Endpoints

| Route | Purpose |
|---|---|
| `GET /unlock` | Garmin redirects here with `appUnlockRequestId`, `callbackUrl`, `appPageUrl` (both URL-encoded). Create an order row, render the $1.99 page, open Razorpay Checkout. |
| `POST /razorpay/webhook` | Verify `X-Razorpay-Signature` against the raw body with your webhook secret. On `payment.captured`, mark the order paid. |
| `GET /return` | Thank-you page, then link/redirect to `appPageUrl`. |
| `GET /health` | For your own monitoring. |

### Calling Garmin back

Once the payment is confirmed, call the `callbackUrl` Garmin gave you with a **one-legged OAuth1 request** (GET,
signed with HMAC-SHA1) using the consumer key and secret from the Developer Dashboard.

Handle the responses honestly:

| Code | Meaning | What to do |
|---|---|---|
| 200 | Unlocked | Done. Never call again for that id. |
| 202 | Accepted (test only) | Expected when you test with the sandbox request. |
| 401 | Bad OAuth signature | Your consumer key/secret or signature base string is wrong. Log the string, not the secret. |
| 404 | Unknown `appUnlockRequestId` | The order wasn't paid, or you're calling with the wrong id. |
| 409 | Already being unlocked | Harmless duplicate — treat as success. |

### Three rules that will otherwise bite you

1. **Idempotency.** Store `appUnlockRequestId ↔ razorpay order/payment id`. Retries, webhook redelivery and impatient
   users must never produce two callbacks or two charges.
2. **Only unlock after the money is certain.** Trust the *verified webhook*, not the browser redirect — a user can
   close the tab or fake the return URL.
3. **Never put the OAuth secret in the watch app or in the site's JavaScript.** It lives in the server's env vars.

### Secrets you'll be holding

| Secret | Where it comes from |
|---|---|
| Garmin OAuth consumer key + secret | Connect IQ Developer Dashboard |
| Razorpay key id + key secret | Razorpay dashboard |
| Razorpay webhook secret | Razorpay dashboard |

## 4. Payment gateway

**Razorpay** is the sensible default in India: UPI, cards, netbanking, wallets, payouts to an Indian bank, and
international cards once you enable them.

- Fees are roughly **2% + GST** for domestic cards and a bit more for international; check the current schedule before
  you set the price.
- **Merchant of record:** you are the seller, so you're responsible for GST on the sale. Below ₹20 lakh a year you're
  likely not registered; above it, get a chartered accountant involved (PLAN.md, "Side matters").
- **Alternative if you want the tax handled for you:** Paddle or Lemon Squeezy act as merchant of record and deal with
  VAT/GST everywhere, at roughly 5%. Worth it only if international sales become significant.

### What the money looks like on one $1.99 sale

| Model | You receive |
|---|---|
| Garmin merchant (no trial) | ~$1.69 (Garmin 15%) |
| Own gateway, Razorpay ~2% + GST | ~$1.92 |
| Own gateway, Paddle ~5% | ~$1.89 |

So the trial path also pays better per sale — it just costs you a server to run.

## 5. Steps, in order

1. **Check the dashboard for a native trial option.** If Garmin can sell a trial app itself, stop here and skip the
   service.
2. **Pick a host and a domain path.** `https://navmap.download/unlock` is already what the manifest points at; that
   can be a redirect to wherever the service really runs, or the service itself behind the existing domain.
3. **Register the unlock URL with Garmin** and copy the OAuth key/secret out of the dashboard.
4. **Build the four routes** above, with the idempotency rule from §3.
5. **Test end to end before touching the listing**: trial expiry on a real watch, unlock redirect, test payment,
   OAuth callback 200, and a 409 on double-unlock.
6. **Truthful listing.** Garmin requires apps that ask for payment to be flagged **"Payment Required"**, and the
   review guidelines say you must disclose from the outset that the app is free only for a limited time, and state
   your refund policy. Both go in the listing description.
7. **Publish a refund policy** on navmap.download and link it from the listing. Without one, the first unhappy buyer
   has nothing to point at.
8. **Update the copy.** The site still says "$1.99 planned". Once the store charges, that becomes the real price.

## 6. What already exists

| Piece | State |
|---|---|
| Seven-day trial, enforced by the platform | Built, compiles (`PR #2`), waiting on the unlock URL |
| `build-watch.sh` finds an installed SDK | Fixed (`PR #2`) |
| NavBridge paywall | Removed from the plan; branch `feature/pro-unlock` closed |
| Phone app | Free everywhere. Play `.aab` submitted, awaiting verification |
| Website | Links the live Connect IQ listing |
| Unlock service | **Built** in `unlock/` — Cloudflare Worker, 14 tests, needs Razorpay + Garmin keys to go live |

## 7. Open questions

1. Answered: no. Garmin cannot take the $1.99 itself while a trial is running — see the note in section 1.
2. Where will the service run — Cloudflare Workers, Vercel, or a VPS?
3. Razorpay, or a merchant-of-record like Paddle?
4. Is `navmap.download/unlock` the final URL, or should the manifest point at a separate host?
