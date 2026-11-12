// PayPal Orders v2 calls for the international checkout.
//
// The Worker uses PayPal's REST API directly: the Node/PHP SDKs are not available in a Cloudflare Worker. The
// client secret never leaves the server; the browser only needs the public client id.

const SANDBOX = "https://api-m.sandbox.paypal.com";
const LIVE = "https://api-m.paypal.com";

/** Carries PayPal's HTTP status so the API route can distinguish auth failures from gateway errors. */
export class PayPalError extends Error {
  constructor(message, status, detail) {
    super(message);
    this.name = "PayPalError";
    this.status = status;
    this.detail = detail;
  }
}

function apiBase(env) {
  return env.PAYPAL_ENV === "live" ? LIVE : SANDBOX;
}

let tokenCache = { key: "", value: "", expiresAt: 0 };

export async function paypalToken(env) {
  if (!env.PAYPAL_CLIENT_ID || !env.PAYPAL_CLIENT_SECRET) {
    throw new PayPalError("PayPal keys are not configured", 503);
  }
  const cacheKey = `${apiBase(env)}:${env.PAYPAL_CLIENT_ID}`;
  if (tokenCache.key === cacheKey && tokenCache.value && tokenCache.expiresAt > Date.now() + 60_000) {
    return tokenCache.value;
  }

  const auth = btoa(`${env.PAYPAL_CLIENT_ID}:${env.PAYPAL_CLIENT_SECRET}`);
  const response = await fetch(`${apiBase(env)}/v1/oauth2/token`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${auth}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: "grant_type=client_credentials",
  });
  if (!response.ok) {
    const detail = await response.text();
    throw new PayPalError(`PayPal token failed: ${response.status}`, response.status, detail);
  }
  const data = await response.json();
  if (!data.access_token) throw new PayPalError("PayPal returned no access token", 502);
  tokenCache = {
    key: cacheKey,
    value: data.access_token,
    expiresAt: Date.now() + Number(data.expires_in ?? 3000) * 1000,
  };
  return data.access_token;
}

async function paypalFetch(env, path, init = {}) {
  const token = await paypalToken(env);
  const response = await fetch(`${apiBase(env)}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
  });
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!response.ok) {
    throw new PayPalError(
      `PayPal request failed: ${response.status}`,
      response.status,
      data ?? text,
    );
  }
  return data;
}

/** PayPal wants "1.99", while the Worker stores minor units (199). */
export function amountValue(minorUnits) {
  return (Number(minorUnits) / 100).toFixed(2);
}

export async function createPayPalOrder({ env, amount, requestId }) {
  return paypalFetch(env, "/v2/checkout/orders", {
    method: "POST",
    headers: { "PayPal-Request-Id": `create-${requestId}` },
    body: JSON.stringify({
      intent: "CAPTURE",
      purchase_units: [
        {
          reference_id: requestId,
          custom_id: requestId,
          description: "NavMap unlock",
          amount: { currency_code: "USD", value: amountValue(amount) },
        },
      ],
      application_context: {
        brand_name: "NavMap",
        locale: "en-US",
        shipping_preference: "NO_SHIPPING",
        user_action: "PAY_NOW",
      },
    }),
  });
}

export async function getPayPalOrder(env, orderId) {
  return paypalFetch(env, `/v2/checkout/orders/${encodeURIComponent(orderId)}`, { method: "GET" });
}

export async function capturePayPalOrder(env, orderId) {
  return paypalFetch(env, `/v2/checkout/orders/${encodeURIComponent(orderId)}/capture`, {
    method: "POST",
    headers: { "PayPal-Request-Id": `capture-${orderId}` },
    body: "{}",
  });
}

export function captureFromOrder(order) {
  const captures = order?.purchase_units?.[0]?.payments?.captures;
  return Array.isArray(captures) && captures.length > 0 ? captures[0] : null;
}

export async function verifyPayPalWebhook({ env, headers, event }) {
  if (!env.PAYPAL_WEBHOOK_ID) return false;
  const required = [
    "paypal-transmission-id",
    "paypal-transmission-time",
    "paypal-cert-url",
    "paypal-auth-algo",
    "paypal-transmission-sig",
  ];
  const values = required.map((name) => headers.get(name));
  if (values.some((value) => !value)) return false;

  const response = await fetch(`${apiBase(env)}/v1/notifications/verify-webhook-signature`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${await paypalToken(env)}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      auth_algo: values[3],
      cert_url: values[2],
      transmission_id: values[0],
      transmission_sig: values[4],
      transmission_time: values[1],
      webhook_id: env.PAYPAL_WEBHOOK_ID,
      webhook_event: event,
    }),
  });
  if (!response.ok) return false;
  const data = await response.json();
  return data.verification_status === "SUCCESS";
}
