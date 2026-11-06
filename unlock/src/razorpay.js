// The two Razorpay calls this service needs: create the order, and check that a webhook really came from Razorpay.

const API = "https://api.razorpay.com/v1";

/**
 * Creates a Razorpay order. `payment_capture: 1` asks Razorpay to capture the payment automatically, so a successful
 * payment webhook means the money is actually ours rather than sitting authorised.
 */
export async function createOrder({ keyId, keySecret, amount, currency, receipt, notes }) {
  if (!keyId || !keySecret) throw new Error("Razorpay keys are not configured");
  const auth = btoa(`${keyId}:${keySecret}`);
  const response = await fetch(`${API}/orders`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${auth}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      amount,
      currency,
      receipt,
      notes,
      payment_capture: 1,
    }),
  });
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`Razorpay order failed: ${response.status} ${detail}`);
  }
  return response.json();
}

export async function hmacSha256Hex(secret, message) {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(message));
  return [...new Uint8Array(signature)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Length-independent comparison, so a wrong signature can't be narrowed down by timing. */
export function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * Razorpay signs the *raw* webhook body with the webhook secret and sends it as X-Razorpay-Signature (hex).
 * The body must be the exact bytes received: parsing and re-stringifying changes them.
 */
export async function verifyWebhook(rawBody, signatureHeader, secret) {
  if (!secret || !signatureHeader) return false;
  const expected = await hmacSha256Hex(secret, rawBody);
  return timingSafeEqual(expected, signatureHeader.trim().toLowerCase());
}
