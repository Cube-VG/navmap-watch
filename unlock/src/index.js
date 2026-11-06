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
import { createOrder, verifyWebhook } from "./razorpay.js";
import { errorPage, paymentPage, thanksPage } from "./page.js";

const KV_TTL = 60 * 60 * 24 * 90; // 90 days: long enough to answer "where is my unlock" questions

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/health") return json({ ok: true });
      if (url.pathname === "/unlock" && request.method === "GET") return await handleUnlock(url, env);
      if (url.pathname === "/razorpay/webhook" && request.method === "POST") return await handleWebhook(request, env);
      if (url.pathname === "/return" && request.method === "GET") return await handleReturn(url, env);
      return new Response("Not found", { status: 404 });
    } catch (error) {
      console.error("unlock service failed:", error?.stack ?? error);
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

async function handleUnlock(url, env) {
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

  const existing = await env.ORDERS.get(`req:${requestId}`);
  if (existing) {
    const record = JSON.parse(existing);
    if (record.status === "unlocked") return page(thanksPage({ appPageUrl, unlocked: true }));
    // Already started a payment: reuse its order so the user doesn't pay twice.
    if (record.orderId) {
      return page(
        paymentPage({
          keyId: env.RAZORPAY_KEY_ID,
          orderId: record.orderId,
          amount: record.amount,
          currency: record.currency,
          requestId,
          appPageUrl,
        }),
      );
    }
  }

  const amount = Number(env.PRICE_AMOUNT);
  const currency = env.PRICE_CURRENCY ?? "INR";
  if (!Number.isInteger(amount) || amount <= 0) {
    return page(errorPage("The price isn't configured correctly."), 500);
  }

  const order = await createOrder({
    keyId: env.RAZORPAY_KEY_ID,
    keySecret: env.RAZORPAY_KEY_SECRET,
    amount,
    currency,
    receipt: requestId.slice(0, 40),
    notes: { appUnlockRequestId: requestId },
  });

  const record = {
    requestId,
    callbackUrl,
    appPageUrl,
    orderId: order.id,
    amount,
    currency,
    status: "pending",
    createdAt: Date.now(),
  };
  await env.ORDERS.put(`req:${requestId}`, JSON.stringify(record), { expirationTtl: KV_TTL });
  await env.ORDERS.put(`order:${order.id}`, requestId, { expirationTtl: KV_TTL });

  return page(
    paymentPage({
      keyId: env.RAZORPAY_KEY_ID,
      orderId: order.id,
      amount,
      currency,
      requestId,
      appPageUrl,
    }),
  );
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
    headers: { "content-type": "application/json; charset=utf-8" },
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
