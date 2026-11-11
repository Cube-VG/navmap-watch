import test from "node:test";
import assert from "node:assert/strict";
import worker, { countryFrom, isGarminUrl, priceForCountry } from "../src/index.js";
import { hmacSha256Hex } from "../src/razorpay.js";

function fakeKV() {
  const map = new Map();
  return {
    async get(key) {
      return map.has(key) ? map.get(key) : null;
    },
    async put(key, value) {
      map.set(key, value);
    },
    async delete(key) {
      map.delete(key);
    },
    size: () => map.size,
  };
}

function newEnv() {
  return {
    ORDERS: fakeKV(),
    RAZORPAY_KEY_ID: "rzp_test_key",
    RAZORPAY_KEY_SECRET: "razorpay_secret",
    RAZORPAY_WEBHOOK_SECRET: "webhook_secret",
    GARMIN_OAUTH_KEY: "garmin_key",
    GARMIN_OAUTH_SECRET: "garmin_secret",
    PRICE_INR: "19900",
    PRICE_USD: "199",
    INTERNATIONAL_CHECKOUT: "on",
  };
}

function unlockRequest(country, id = "req-1") {
  const url = new URL("https://unlock.test/unlock");
  url.searchParams.set("appUnlockRequestId", id);
  url.searchParams.set("callbackUrl", `https://apps.garmin.com/appUnlock?appUnlockRequestId=${id}`);
  url.searchParams.set("appPageUrl", "https://apps.garmin.com/apps/abc");
  const headers = country ? { "cf-ipcountry": country } : {};
  return new Request(url, { headers });
}

/** Replaces global fetch for the duration of one test and records what was called. */
async function withFetch(handler, run) {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (input, init) => {
    calls.push({ url: String(input), init });
    return handler(String(input), init);
  };
  try {
    await run(calls);
  } finally {
    globalThis.fetch = original;
  }
}

function webhookRequest(body, signature) {
  return new Request("https://unlock.test/razorpay/webhook", {
    method: "POST",
    headers: { "x-razorpay-signature": signature },
    body,
  });
}

function apiRequest(path, body) {
  return new Request(`https://unlock.test${path}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function putRequest(env, id, overrides = {}) {
  await env.ORDERS.put(
    `req:${id}`,
    JSON.stringify({
      requestId: id,
      callbackUrl: `https://apps.garmin.com/appUnlock?appUnlockRequestId=${id}`,
      appPageUrl: "https://apps.garmin.com/apps/abc",
      amount: 19900,
      currency: "INR",
      status: "pending",
      ...overrides,
    }),
  );
}

const PAID = JSON.stringify({
  event: "payment.captured",
  payload: {
    payment: { entity: { id: "pay_1", order_id: "order_1", amount: 19900, currency: "INR" } },
  },
});

test("isGarminUrl only allows https Garmin hosts", () => {
  assert.equal(isGarminUrl("https://apps.garmin.com/appUnlock?x=1"), true);
  assert.equal(isGarminUrl("https://garmin.com/x"), true);
  assert.equal(isGarminUrl("http://apps.garmin.com/appUnlock"), false, "http is not https");
  assert.equal(isGarminUrl("https://evil.test/garmin.com"), false);
  assert.equal(isGarminUrl("https://garmin.com.evil.test/"), false);
  assert.equal(isGarminUrl("not a url"), false);
});

test("health responds", async () => {
  const response = await worker.fetch(new Request("https://unlock.test/health"), newEnv());
  assert.equal(response.status, 200);
});

test("unlock refuses a callback that isn't Garmin's", async () => {
  const url = new URL("https://unlock.test/unlock");
  url.searchParams.set("appUnlockRequestId", "req-1");
  url.searchParams.set("callbackUrl", "https://attacker.example/steal");
  url.searchParams.set("appPageUrl", "https://apps.garmin.com/apps/abc");
  const response = await worker.fetch(new Request(url), newEnv());
  assert.equal(response.status, 400);
});

test("unlock refuses when Garmin's parameters are missing", async () => {
  const response = await worker.fetch(new Request("https://unlock.test/unlock"), newEnv());
  assert.equal(response.status, 400);
});

test("priceForCountry charges India in rupees and everyone else in dollars", () => {
  assert.deepEqual(priceForCountry("IN", {}), { amount: 19900, currency: "INR" });
  assert.deepEqual(priceForCountry("in", {}), { amount: 19900, currency: "INR" });
  assert.deepEqual(priceForCountry("US", {}), { amount: 199, currency: "USD" });
  assert.deepEqual(priceForCountry("GB", {}), { amount: 199, currency: "USD" });
  assert.deepEqual(priceForCountry("", {}), { amount: 199, currency: "USD" });
  assert.deepEqual(priceForCountry("IN", { PRICE_INR: "29900" }), { amount: 29900, currency: "INR" });
  assert.deepEqual(priceForCountry("US", { PRICE_USD: "299" }), { amount: 299, currency: "USD" });
});

test("countryFrom reads Cloudflare's country and falls back to the header", () => {
  assert.equal(countryFrom(new Request("https://unlock.test", { headers: { "cf-ipcountry": "in" } })), "IN");
  assert.equal(countryFrom(new Request("https://unlock.test")), "");

  const request = new Request("https://unlock.test", { headers: { "cf-ipcountry": "US" } });
  Object.defineProperty(request, "cf", { value: { country: "IN" } });
  assert.equal(countryFrom(request), "IN", "request.cf wins over a header");

  const override = new Request("https://unlock.test/?country=US");
  assert.equal(countryFrom(override, { ALLOW_COUNTRY_OVERRIDE: "1" }), "US");
  assert.equal(countryFrom(override, {}), "", "the override must be opt-in for local development");
});

test("India gets a ₹199 INR order and the checkout API returns it", async () => {
  const env = newEnv();
  await withFetch(
    async () =>
      new Response(JSON.stringify({ id: "order_in", amount: 19900, currency: "INR" }), { status: 200 }),
    async (calls) => {
      const response = await worker.fetch(unlockRequest("IN"), env);
      assert.equal(response.status, 200);
      const html = await response.text();
      assert.match(html, /₹199/);
      assert.match(html, /\/api\/create-order/);
      assert.match(html, /\/api\/verify-payment/);
      assert.doesNotMatch(html, /razorpay_secret/i, "the key secret must never reach the page");
      assert.equal(calls.length, 0, "opening the page must not create an order");

      const orderResponse = await worker.fetch(
        apiRequest("/api/create-order", { requestId: "req-1" }),
        env,
      );
      assert.equal(orderResponse.status, 200);
      assert.deepEqual(await orderResponse.json(), {
        order_id: "order_in",
        amount: 19900,
        currency: "INR",
        key_id: "rzp_test_key",
      });

      assert.equal(calls.length, 1);
      const body = JSON.parse(calls[0].init.body);
      assert.equal(body.amount, 19900);
      assert.equal(body.currency, "INR");
      assert.equal(body.notes.country, "IN");

      // Pressing Pay again must reuse the order, not create a second one.
      const again = await worker.fetch(apiRequest("/api/create-order", { requestId: "req-1" }), env);
      assert.equal(again.status, 200);
      assert.equal((await again.json()).order_id, "order_in");
      assert.equal(calls.length, 1, "the existing order must be reused, not recreated");
    },
  );
  assert.equal(await env.ORDERS.get("order:order_in"), "req-1");
});

test("everyone outside India gets a US$1.99 USD order", async () => {
  const env = newEnv();
  await withFetch(
    async () => new Response(JSON.stringify({ id: "order_us", amount: 199, currency: "USD" }), { status: 200 }),
    async (calls) => {
      const response = await worker.fetch(unlockRequest("US"), env);
      assert.equal(response.status, 200);
      const html = await response.text();
      assert.match(html, /\$1\.99/);
      assert.match(html, /\/api\/create-order/);
      assert.equal(calls.length, 0, "opening the page must not create an order");

      const orderResponse = await worker.fetch(
        apiRequest("/api/create-order", { requestId: "req-1" }),
        env,
      );
      assert.equal(orderResponse.status, 200);
      const order = await orderResponse.json();
      assert.equal(order.order_id, "order_us");
      assert.equal(order.amount, 199);
      assert.equal(order.currency, "USD");

      assert.equal(calls.length, 1);
      const body = JSON.parse(calls[0].init.body);
      assert.equal(body.amount, 199);
      assert.equal(body.currency, "USD");
      assert.equal(body.notes.country, "US");
    },
  );
  assert.equal(await env.ORDERS.get("order:order_us"), "req-1");
});

test("international checkout can be gated off without creating an order", async () => {
  const env = newEnv();
  env.INTERNATIONAL_CHECKOUT = "off";

  const us = await worker.fetch(unlockRequest("US"), env);
  assert.equal(us.status, 200);
  const html = await us.text();
  assert.match(html, /International checkout is being set up/);
  assert.doesNotMatch(html, /\$1\.99/);
  assert.equal(await env.ORDERS.get("req:req-1"), null, "no request or order is stored for a gated country");

  const india = await worker.fetch(unlockRequest("IN", "req-in"), env);
  assert.equal(india.status, 200);
  assert.match(await india.text(), /₹199/);

  await putRequest(env, "req-us", { amount: 199, currency: "USD", country: "US" });
  const blocked = await worker.fetch(
    apiRequest("/api/create-order", { requestId: "req-us" }),
    env,
  );
  assert.equal(blocked.status, 503);
  assert.match((await blocked.json()).error, /not available/i);
});

test("an existing order keeps the price it was created with", async () => {
  const env = newEnv();
  await env.ORDERS.put(
    "req:req-9",
    JSON.stringify({
      requestId: "req-9",
      callbackUrl: "https://apps.garmin.com/appUnlock?appUnlockRequestId=req-9",
      appPageUrl: "https://apps.garmin.com/apps/abc",
      orderId: "order_old",
      amount: 19900,
      currency: "INR",
      status: "pending",
    }),
  );

  await withFetch(
    async () => {
      throw new Error("must not create a second order");
    },
    async (calls) => {
      const response = await worker.fetch(unlockRequest("US", "req-9"), env);
      assert.equal(response.status, 200);
      const html = await response.text();
      assert.match(html, /₹199/);
      assert.doesNotMatch(html, /\$1\.99/);
      assert.equal(calls.length, 0);

      const orderResponse = await worker.fetch(
        apiRequest("/api/create-order", { requestId: "req-9" }),
        env,
      );
      assert.equal(orderResponse.status, 200);
      const order = await orderResponse.json();
      assert.equal(order.order_id, "order_old");
      assert.equal(order.amount, 19900);
      assert.equal(order.currency, "INR");
      assert.equal(calls.length, 0);
    },
  );
});

test("create-order validates the request id", async () => {
  const env = newEnv();
  const missing = await worker.fetch(apiRequest("/api/create-order", {}), env);
  assert.equal(missing.status, 400);
  assert.match((await missing.json()).error, /requestId/);

  const unknown = await worker.fetch(apiRequest("/api/create-order", { requestId: "nope" }), env);
  assert.equal(unknown.status, 404);
  assert.match((await unknown.json()).error, /Unknown/);
});

test("create-order maps Razorpay auth and server failures", async () => {
  const env = newEnv();
  await putRequest(env, "req-gateway");

  await withFetch(
    async () => new Response("nope", { status: 401 }),
    async () => {
      const response = await worker.fetch(
        apiRequest("/api/create-order", { requestId: "req-gateway" }),
        env,
      );
      assert.equal(response.status, 401);
      assert.match((await response.json()).error, /authentication/i);
    },
  );

  await withFetch(
    async () => new Response("slow down", { status: 429 }),
    async () => {
      const response = await worker.fetch(
        apiRequest("/api/create-order", { requestId: "req-gateway" }),
        env,
      );
      assert.equal(response.status, 429);
      assert.match((await response.json()).error, /wait/i);
    },
  );

  await withFetch(
    async () => new Response("nope", { status: 500 }),
    async () => {
      const response = await worker.fetch(
        apiRequest("/api/create-order", { requestId: "req-gateway" }),
        env,
      );
      assert.equal(response.status, 500);
    },
  );
});

test("verify-payment accepts a valid signature and rejects a bad one", async () => {
  const env = newEnv();
  await putRequest(env, "req-7");
  await env.ORDERS.put("order:order_7", "req-7");

  const signature = await hmacSha256Hex("razorpay_secret", "order_7|pay_7");
  await withFetch(
    async () => {
      throw new Error("signature verification must not call Razorpay");
    },
    async (calls) => {
      const valid = await worker.fetch(
        apiRequest("/api/verify-payment", {
          razorpay_order_id: "order_7",
          razorpay_payment_id: "pay_7",
          razorpay_signature: signature,
        }),
        env,
      );
      assert.equal(valid.status, 200);
      assert.deepEqual(await valid.json(), { success: true });

      const invalid = await worker.fetch(
        apiRequest("/api/verify-payment", {
          razorpay_order_id: "order_7",
          razorpay_payment_id: "pay_7",
          razorpay_signature: "deadbeef",
        }),
        env,
      );
      assert.equal(invalid.status, 400);
      assert.equal((await invalid.json()).success, false);
      assert.equal(calls.length, 0);
    },
  );
});

test("verify-payment requires all three fields", async () => {
  const response = await worker.fetch(
    apiRequest("/api/verify-payment", { razorpay_order_id: "order_7" }),
    newEnv(),
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).success, false);
});

test("verify-payment rejects a valid signature for an unknown order", async () => {
  const env = newEnv();
  const signature = await hmacSha256Hex("razorpay_secret", "order_missing|pay_missing");
  const response = await worker.fetch(
    apiRequest("/api/verify-payment", {
      razorpay_order_id: "order_missing",
      razorpay_payment_id: "pay_missing",
      razorpay_signature: signature,
    }),
    env,
  );
  assert.equal(response.status, 404);
  assert.equal((await response.json()).success, false);
});

test("webhook with a bad signature neither unlocks nor calls Garmin", async () => {
  const env = newEnv();
  await withFetch(
    async () => new Response("", { status: 200 }),
    async (calls) => {
      const response = await worker.fetch(webhookRequest(PAID, "deadbeef"), env);
      assert.equal(response.status, 400);
      assert.equal(calls.length, 0, "Garmin must not be called for an unsigned webhook");
    },
  );
});

test("webhook unlocks once, survives a duplicate, and signs the Garmin call", async () => {
  const env = newEnv();
  await env.ORDERS.put(
    "req:req-1",
    JSON.stringify({
      requestId: "req-1",
      callbackUrl: "https://apps.garmin.com/appUnlock?appUnlockRequestId=req-1",
      appPageUrl: "https://apps.garmin.com/apps/abc",
      amount: 19900,
      currency: "INR",
      status: "pending",
    }),
  );
  await env.ORDERS.put("order:order_1", "req-1");

  const signature = await hmacSha256Hex("webhook_secret", PAID);

  await withFetch(
    async () => new Response("", { status: 200 }),
    async (calls) => {
      const first = await worker.fetch(webhookRequest(PAID, signature), env);
      assert.equal(first.status, 200);
      assert.equal(calls.length, 1);
      assert.match(calls[0].url, /^https:\/\/apps\.garmin\.com\/appUnlock/);
      assert.match(calls[0].init.headers.Authorization, /^OAuth /);
      assert.match(calls[0].init.headers.Authorization, /oauth_signature_method="HMAC-SHA1"/);

      // Razorpay redelivers, and users reload pages. Garmin must not be called a second time.
      const second = await worker.fetch(webhookRequest(PAID, signature), env);
      assert.equal(second.status, 200);
      assert.equal(calls.length, 1, "the second webhook must be a no-op");
    },
  );

  const record = JSON.parse(await env.ORDERS.get("req:req-1"));
  assert.equal(record.status, "unlocked");
  assert.equal(record.paymentId, "pay_1");
});

test("a Garmin failure returns 500 so Razorpay retries the webhook", async () => {
  const env = newEnv();
  await env.ORDERS.put(
    "req:req-2",
    JSON.stringify({
      requestId: "req-2",
      callbackUrl: "https://apps.garmin.com/appUnlock?appUnlockRequestId=req-2",
      appPageUrl: "https://apps.garmin.com/apps/abc",
      amount: 19900,
      currency: "INR",
      status: "pending",
    }),
  );
  await env.ORDERS.put("order:order_2", "req-2");

  const body = JSON.stringify({
    event: "payment.captured",
    payload: {
      payment: { entity: { id: "pay_2", order_id: "order_2", amount: 19900, currency: "INR" } },
    },
  });
  const signature = await hmacSha256Hex("webhook_secret", body);

  await withFetch(
    async () => new Response("nope", { status: 401 }),
    async () => {
      const response = await worker.fetch(webhookRequest(body, signature), env);
      assert.equal(response.status, 500);
      assert.equal(await env.ORDERS.get("done:req-2"), null, "must stay unlocked=false");
    },
  );
});

test("Garmin's 409 means already unlocking, which counts as success", async () => {
  const env = newEnv();
  await env.ORDERS.put(
    "req:req-3",
    JSON.stringify({
      requestId: "req-3",
      callbackUrl: "https://apps.garmin.com/appUnlock?appUnlockRequestId=req-3",
      appPageUrl: "https://apps.garmin.com/apps/abc",
      amount: 19900,
      currency: "INR",
      status: "pending",
    }),
  );
  await env.ORDERS.put("order:order_3", "req-3");
  const body = JSON.stringify({
    event: "payment.captured",
    payload: {
      payment: { entity: { id: "pay_3", order_id: "order_3", amount: 19900, currency: "INR" } },
    },
  });
  const signature = await hmacSha256Hex("webhook_secret", body);

  await withFetch(
    async () => new Response("", { status: 409 }),
    async () => {
      const response = await worker.fetch(webhookRequest(body, signature), env);
      assert.equal(response.status, 200);
    },
  );
  assert.equal(await env.ORDERS.get("done:req-3"), "1");
});

test("a USD payment unlocks the app too", async () => {
  const env = newEnv();
  await env.ORDERS.put(
    "req:req-6",
    JSON.stringify({
      requestId: "req-6",
      callbackUrl: "https://apps.garmin.com/appUnlock?appUnlockRequestId=req-6",
      appPageUrl: "https://apps.garmin.com/apps/abc",
      amount: 199,
      currency: "USD",
      status: "pending",
    }),
  );
  await env.ORDERS.put("order:order_6", "req-6");

  const body = JSON.stringify({
    event: "payment.captured",
    payload: {
      payment: { entity: { id: "pay_6", order_id: "order_6", amount: 199, currency: "USD" } },
    },
  });
  const signature = await hmacSha256Hex("webhook_secret", body);

  await withFetch(
    async () => new Response("", { status: 200 }),
    async (calls) => {
      const response = await worker.fetch(webhookRequest(body, signature), env);
      assert.equal(response.status, 200);
      assert.equal(calls.length, 1);
    },
  );
  assert.equal(await env.ORDERS.get("done:req-6"), "1");
});

test("webhook refuses a payment whose amount does not match the order", async () => {
  const env = newEnv();
  await env.ORDERS.put(
    "req:req-4",
    JSON.stringify({
      requestId: "req-4",
      callbackUrl: "https://apps.garmin.com/appUnlock?appUnlockRequestId=req-4",
      appPageUrl: "https://apps.garmin.com/apps/abc",
      amount: 19900,
      currency: "INR",
      status: "pending",
    }),
  );
  await env.ORDERS.put("order:order_4", "req-4");

  const body = JSON.stringify({
    event: "payment.captured",
    payload: {
      payment: { entity: { id: "pay_4", order_id: "order_4", amount: 100, currency: "INR" } },
    },
  });
  const signature = await hmacSha256Hex("webhook_secret", body);

  await withFetch(
    async () => {
      throw new Error("Garmin must not be called for a mismatched amount");
    },
    async (calls) => {
      const response = await worker.fetch(webhookRequest(body, signature), env);
      assert.equal(response.status, 200);
      assert.equal(calls.length, 0);
    },
  );
  assert.equal(await env.ORDERS.get("done:req-4"), null);
  assert.equal(JSON.parse(await env.ORDERS.get("req:req-4")).status, "pending");
});

test("webhook refuses a payment in a different currency", async () => {
  const env = newEnv();
  await env.ORDERS.put(
    "req:req-5",
    JSON.stringify({
      requestId: "req-5",
      callbackUrl: "https://apps.garmin.com/appUnlock?appUnlockRequestId=req-5",
      appPageUrl: "https://apps.garmin.com/apps/abc",
      amount: 19900,
      currency: "INR",
      status: "pending",
    }),
  );
  await env.ORDERS.put("order:order_5", "req-5");

  const body = JSON.stringify({
    event: "payment.captured",
    payload: {
      payment: { entity: { id: "pay_5", order_id: "order_5", amount: 19900, currency: "USD" } },
    },
  });
  const signature = await hmacSha256Hex("webhook_secret", body);

  await withFetch(
    async () => {
      throw new Error("Garmin must not be called for a currency mismatch");
    },
    async (calls) => {
      const response = await worker.fetch(webhookRequest(body, signature), env);
      assert.equal(response.status, 200);
      assert.equal(calls.length, 0);
    },
  );
  assert.equal(await env.ORDERS.get("done:req-5"), null);
});
