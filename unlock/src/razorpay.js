// The Razorpay calls this service needs: create the order, verify Checkout's payment signature, and check that a
// webhook really came from Razorpay.

const API = "https://api.razorpay.com/v1";

/** Carries the gateway's HTTP status so the API route can tell an auth failure from an outage. */
export class RazorpayError extends Error {
  constructor(message, status, detail) {
    super(message);
    this.name = "RazorpayError";
    this.status = status;
    this.detail = detail;
  }
}

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
    throw new RazorpayError(`Razorpay order failed: ${response.status}`, response.status, detail);
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
 * Razorpay Checkout signs `order_id|payment_id` with the account's key secret. This is the check the browser sends
 * back after a successful payment; the webhook remains the source of truth for unlocking.
 */
export async function verifyPaymentSignature({ orderId, paymentId, signature, secret }) {
  if (!orderId || !paymentId || !signature || !secret) return false;
  const expected = await hmacSha256Hex(secret, `${orderId}|${paymentId}`);
  return timingSafeEqual(expected, String(signature).trim().toLowerCase());
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
