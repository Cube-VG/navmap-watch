// NavMap's unlock endpoint for Garmin's app trials.
//
// Flow (see docs/MONETIZATION.md):
//   Garmin redirects a buyer here with appUnlockRequestId, callbackUrl and appPageUrl.
//   We take the payment with Razorpay, and when the money is definitely ours we call Garmin's callbackUrl back with a
//   one-legged OAuth1 signature. Garmin then marks NavMap unlocked for that account.
//
// Two rules this file exists to enforce:
//   1. Unlock only on a *verified webhook*, never on the browser landing back here. The return URL is attacker
//      controlled; Razorpay's signature is not.
//   2. Never unlock the same request twice. Retries and webhook redelivery are normal, and Garmin answers 409.

import { sign } from "./oauth1.js";
import { createOrder, verifyPaymentSignature, verifyWebhook } from "./razorpay.js";
import { errorPage, internationalPage, paymentPage, thanksPage } from "./page.js";

const KV_TTL = 60 * 60 * 24 * 90; // 90 days: long enough to answer "where is my unlock" questions

/**
 * Cloudflare fills in request.cf.country. The header is a fallback for `wrangler dev` and tests; behind Cloudflare
 * the header is set by the edge too, so a browser cannot choose its own price.
 */
export function countryFrom(request, env = {}) {
  // Local-only override so a developer in India can exercise the USD path without a VPN. Production never sets
  // ALLOW_COUNTRY_OVERRIDE.
  const override =
    env.ALLOW_COUNTRY_OVERRIDE === "1"
      ? new URL(request.url).searchParams.get("country")
      : null;
  const cfCountry = override || request.cf?.country;
  const headerCountry = request.headers.get("cf-ipcountry");
  return String(cfCountry || headerCountry || "").toUpperCase();
}

/**
 * India pays in rupees so UPI works. Everyone outside India pays US$1.99 in dollars. One price per currency keeps
 * Indian cards out of dollar conversion and gives US buyers the price they were shown.
 */
export function priceForCountry(country, env = {}) {
  const normalized = String(country || "").toUpperCase();
  if (normalized === "IN") {
    return { amount: Number(env.PRICE_INR ?? 19900), currency: "INR" };
  }
  return { amount: Number(env.PRICE_USD ?? 199), currency: "USD" };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/health") return json({ ok: true });
      if (url.pathname === "/unlock" && request.method === "GET") return await handleUnlock(request, url, env);
      if (url.pathname === "/api/create-order" && request.method === "POST") {
        return await handleCreateOrder(request, env);
      }
      if (url.pathname === "/api/verify-payment" && request.method === "POST") {
        return await handleVerifyPayment(request, env);
      }
      if (url.pathname === "/razorpay/webhook" && request.method === "POST") return await handleWebhook(request, env);
      if (url.pathname === "/return" && request.method === "GET") return await handleReturn(url, env);
      return new Response("Not found", { status: 404 });
    } catch (error) {
      console.error("unlock service failed:", error?.stack ?? error);
      if (url.pathname.startsWith("/api/")) return json({ error: "The server could not complete that." }, 500);
      return page(errorPage("We couldn't complete that.", "Nothing has been charged."), 500);
    }
  },
};

/** Only ever call back to Garmin. Without this, /unlock is an open request forwarder. */
export function isGarminUrl(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== "https:") return false;
    return url.hostname === "garmin.com" || url.hostname.endsWith(".garmin.com");
  } catch {
    return false;
  }
}

async function handleUnlock(request, url, env) {
  const requestId = url.searchParams.get("appUnlockRequestId");
  const callbackUrl = url.searchParams.get("callbackUrl");
  const appPageUrl = url.searchParams.get("appPageUrl");

  if (!requestId || !callbackUrl || !appPageUrl) {
    return page(
      errorPage(
        "This link is missing something Garmin normally sends.",
        "Open the Connect IQ Store on your phone, find NavMap, and use the Unlock button there.",
      ),
      400,
    );
  }
  if (!isGarminUrl(callbackUrl) || !isGarminUrl(appPageUrl)) {
    return page(errorPage("This link didn't come from the Connect IQ Store."), 400);
  }
  if (!env.RAZORPAY_KEY_ID || !env.RAZORPAY_KEY_SECRET) {
    return page(
      errorPage("Payments aren't switched on yet.", "Razorpay keys are missing from this deployment."),
      503,
    );
  }

  const country = countryFrom(request, env);
  const price = priceForCountry(country, env);
  if (!Number.isInteger(price.amount) || price.amount <= 0) {
    return page(errorPage("The price isn't configured correctly."), 500);
  }

  // Razorpay's live account cannot take international cards yet. Do not create a USD checkout that would fail at
  // the card step; show an honest page until a second provider is wired in.
  if (country !== "IN" && env.INTERNATIONAL_CHECKOUT !== "on") {
    return page(internationalPage({ appPageUrl }));
  }

  let record;
  let shouldSave = false;
  const existing = await env.ORDERS.get(`req:${requestId}`);
  if (existing) {
    record = JSON.parse(existing);
    if (record.status === "unlocked") return page(thanksPage({ appPageUrl, unlocked: true }));
    // The first open decides the currency; changing country or network mid-flow must not create a different order.
    if (!record.amount || !record.currency) {
      record.amount = price.amount;
      record.currency = price.currency;
      shouldSave = true;
    }
  } else {
    record = {
      requestId,
      callbackUrl,
      appPageUrl,
      amount: price.amount,
      currency: price.currency,
      country,
      status: "pending",
      createdAt: Date.now(),
    };
    shouldSave = true;
  }

  // The order itself is created by POST /api/create-order when the buyer presses Pay, so a slow or failed
  // Razorpay call does not stop this page from rendering and the buyer can retry from the button. Do not rewrite an
  // existing record here: another tab may have just attached an order id to it.
  if (shouldSave) {
    await env.ORDERS.put(`req:${requestId}`, JSON.stringify(record), { expirationTtl: KV_TTL });
  }

  return page(
    paymentPage({
      amount: record.amount,
      currency: record.currency,
      requestId,
    }),
  );
}

/** Creates the Razorpay order once per unlock request, then reuses it. */
async function ensureOrder(record, env) {
  if (!Number.isInteger(record.amount) || record.amount < 100) {
    throw new Error("order amount is invalid");
  }
  if (!record.currency) throw new Error("order currency is missing");
  if (record.orderId) return record;

  const order = await createOrder({
    keyId: env.RAZORPAY_KEY_ID,
    keySecret: env.RAZORPAY_KEY_SECRET,
    amount: record.amount,
    currency: record.currency,
    receipt: record.requestId.slice(0, 40),
    notes: {
      appUnlockRequestId: record.requestId,
      ...(record.country ? { country: record.country } : {}),
    },
  });
  if (!order?.id) throw new Error("Razorpay returned no order id");

  record.orderId = order.id;
  record.orderCreatedAt = Date.now();
  await env.ORDERS.put(`req:${record.requestId}`, JSON.stringify(record), { expirationTtl: KV_TTL });
  await env.ORDERS.put(`order:${order.id}`, record.requestId, { expirationTtl: KV_TTL });
  return record;
}

async function handleCreateOrder(request, env) {
  if (!env.RAZORPAY_KEY_ID || !env.RAZORPAY_KEY_SECRET) {
    return json({ error: "Payments aren't switched on yet." }, 503);
  }

  const body = await readJson(request);
  const requestId = body?.requestId ?? body?.appUnlockRequestId;
  if (typeof requestId !== "string" || !requestId) {
    return json({ error: "Missing requestId." }, 400);
  }

  const raw = await env.ORDERS.get(`req:${requestId}`);
  if (!raw) return json({ error: "Unknown unlock request." }, 404);

  const record = JSON.parse(raw);
  if (record.status === "unlocked") {
    return json({ error: "This unlock is already complete." }, 409);
  }
  if (record.currency === "USD" && env.INTERNATIONAL_CHECKOUT !== "on") {
    return json({ error: "International checkout is not available yet." }, 503);
  }

  try {
    await ensureOrder(record, env);
  } catch (error) {
    console.error("create order failed", requestId, error?.stack ?? error);
    if (error?.status === 401) return json({ error: "Payment gateway authentication failed." }, 401);
    if (error?.status === 429) {
      return json({ error: "Too many payment attempts. Wait a moment and try again." }, 429);
    }
    if (error?.status) return json({ error: "The payment gateway refused the order." }, 500);
    return json({ error: "Could not create the payment order." }, 500);
  }

  return json({
    order_id: record.orderId,
    amount: record.amount,
    currency: record.currency,
    key_id: env.RAZORPAY_KEY_ID,
  });
}

async function handleVerifyPayment(request, env) {
  if (!env.RAZORPAY_KEY_ID || !env.RAZORPAY_KEY_SECRET) {
    return json({ success: false, error: "Payments aren't switched on yet." }, 503);
  }

  const body = await readJson(request);
  const orderId = body?.razorpay_order_id;
  const paymentId = body?.razorpay_payment_id;
  const signature = body?.razorpay_signature;

  if (typeof orderId !== "string" || typeof paymentId !== "string" || typeof signature !== "string") {
    return json({ success: false, error: "Missing payment fields." }, 400);
  }

  const valid = await verifyPaymentSignature({
    orderId,
    paymentId,
    signature,
    secret: env.RAZORPAY_KEY_SECRET,
  });
  if (!valid) {
    return json({ success: false, error: "Payment signature did not match." }, 400);
  }

  const requestId = await env.ORDERS.get(`order:${orderId}`);
  if (!requestId) return json({ success: false, error: "Unknown order." }, 404);

  return json({ success: true });
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

async function handleWebhook(request, env) {
  // The exact bytes, before any parsing: re-stringifying changes them and breaks the signature.
  const rawBody = await request.text();
  const signature = request.headers.get("x-razorpay-signature");

  if (!(await verifyWebhook(rawBody, signature, env.RAZORPAY_WEBHOOK_SECRET))) {
    console.warn("rejected a webhook with a bad signature");
    return new Response("bad signature", { status: 400 });
  }

  const event = JSON.parse(rawBody);
  const payment = event?.payload?.payment?.entity;
  const orderId = payment?.order_id ?? event?.payload?.order?.entity?.id;

  if (!orderId) {
    console.warn("webhook had no order id", event?.event);
    return json({ ok: true, note: "no order" });
  }
  if (event.event === "payment.failed") {
    console.warn("payment failed for order", orderId, payment?.error_description);
    return json({ ok: true, note: "failed payment noted" });
  }

  const requestId = await env.ORDERS.get(`order:${orderId}`);
  if (!requestId) {
    // Not one of ours, or the record has expired. Acknowledged so Razorpay stops retrying.
    console.warn("webhook for an unknown order", orderId);
    return json({ ok: true, note: "unknown order" });
  }
  if (await env.ORDERS.get(`done:${requestId}`)) {
    return json({ ok: true, note: "already unlocked" });
  }

  const raw = await env.ORDERS.get(`req:${requestId}`);
  if (!raw) {
    console.error("order points at a missing request", requestId);
    return new Response("request record missing", { status: 500 });
  }
  const record = JSON.parse(raw);

  // Defence in depth: the amount and currency must be the ones this server created the order with. A mismatch means
  // something is wrong upstream, so do not unlock and acknowledge the webhook to stop pointless retries.
  if (record.amount != null && payment?.amount != null && Number(payment.amount) !== Number(record.amount)) {
    console.error("webhook amount does not match the order", requestId, payment.amount, record.amount);
    return json({ ok: true, note: "amount mismatch" });
  }
  if (
    record.currency &&
    payment?.currency &&
    String(payment.currency).toUpperCase() !== String(record.currency).toUpperCase()
  ) {
    console.error("webhook currency does not match the order", requestId, payment.currency, record.currency);
    return json({ ok: true, note: "currency mismatch" });
  }

  const status = await callGarmin(record.callbackUrl, env);
  // 200 unlocked, 202 accepted in Garmin's test mode, 409 already unlocking. Anything else is ours to retry.
  if (status === 200 || status === 202 || status === 409) {
    record.status = "unlocked";
    record.paymentId = payment?.id ?? null;
    record.unlockedAt = Date.now();
    await env.ORDERS.put(`req:${requestId}`, JSON.stringify(record), { expirationTtl: KV_TTL });
    await env.ORDERS.put(`done:${requestId}`, "1", { expirationTtl: KV_TTL });
    console.log("unlocked", requestId, "garmin status", status);
    return json({ ok: true, garminStatus: status });
  }

  // Deliberately a 500: Razorpay retries failed webhooks, and the customer has paid already.
  console.error("garmin refused the unlock", requestId, "status", status);
  return new Response(`garmin returned ${status}`, { status: 500 });
}

async function callGarmin(callbackUrl, env) {
  const { header } = await sign({
    method: "GET",
    url: callbackUrl,
    consumerKey: env.GARMIN_OAUTH_KEY,
    consumerSecret: env.GARMIN_OAUTH_SECRET,
  });
  const response = await fetch(callbackUrl, {
    method: "GET",
    headers: { Authorization: header },
    // A hung callback shouldn't stall the webhook until Razorpay gives up.
    signal: AbortSignal.timeout(10_000),
  });
  return response.status;
}

async function handleReturn(url, env) {
  const requestId = url.searchParams.get("rid");
  const done = requestId ? await env.ORDERS.get(`done:${requestId}`) : null;
  const raw = requestId ? await env.ORDERS.get(`req:${requestId}`) : null;
  const appPageUrl = raw ? JSON.parse(raw).appPageUrl : null;
  return page(thanksPage({ appPageUrl, unlocked: Boolean(done) }));
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function page(html, status = 200) {
  return new Response(html, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
    },
  });
}
