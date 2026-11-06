import test from "node:test";
import assert from "node:assert/strict";
import worker, { isGarminUrl } from "../src/index.js";
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
    PRICE_AMOUNT: "19900",
    PRICE_CURRENCY: "INR",
  };
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

const PAID = JSON.stringify({
  event: "payment.captured",
  payload: { payment: { entity: { id: "pay_1", order_id: "order_1", amount: 19900 } } },
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

test("unlock creates a Razorpay order and remembers the mapping", async () => {
  const env = newEnv();
  await withFetch(
    async () =>
      new Response(JSON.stringify({ id: "order_1", amount: 19900, currency: "INR" }), { status: 200 }),
    async (calls) => {
      const url = new URL("https://unlock.test/unlock");
      url.searchParams.set("appUnlockRequestId", "req-1");
      url.searchParams.set("callbackUrl", "https://apps.garmin.com/appUnlock?appUnlockRequestId=req-1");
      url.searchParams.set("appPageUrl", "https://apps.garmin.com/apps/abc");
      const response = await worker.fetch(new Request(url), env);
      assert.equal(response.status, 200);
      const html = await response.text();
      assert.match(html, /order_1/);

      assert.equal(calls.length, 1);
      assert.match(calls[0].url, /api\.razorpay\.com\/v1\/orders/);
      assert.match(calls[0].init.headers.Authorization, /^Basic /);
    },
  );
  assert.equal(await env.ORDERS.get("order:order_1"), "req-1");
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
      status: "pending",
    }),
  );
  await env.ORDERS.put("order:order_2", "req-2");

  const body = JSON.stringify({
    event: "payment.captured",
    payload: { payment: { entity: { id: "pay_2", order_id: "order_2" } } },
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
      status: "pending",
    }),
  );
  await env.ORDERS.put("order:order_3", "req-3");
  const body = JSON.stringify({
    event: "payment.captured",
    payload: { payment: { entity: { id: "pay_3", order_id: "order_3" } } },
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
