// One-legged OAuth 1.0a signing, for the callback Garmin gives us at /unlock.
//
// Garmin's Trial Apps doc: the callbackUrl "is secured and can only be used with a (one-legged) OAuth1-signed
// request", using the consumer key and secret from the Developer Dashboard. "One-legged" means there is no token
// or token secret, just the consumer pair, so the signing key ends with an empty half.
//
// Everything here is plain WebCrypto so it runs unchanged on Cloudflare Workers and in Node.

/** RFC 3986 percent-encoding, which is *not* what encodeURIComponent does: !'()* are left alone by it. */
export function percentEncode(value) {
  return encodeURIComponent(String(value)).replace(
    /[!'()*]/g,
    (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase(),
  );
}

function compare([k1, v1], [k2, v2]) {
  if (k1 !== k2) return k1 < k2 ? -1 : 1;
  if (v1 === v2) return 0;
  return v1 < v2 ? -1 : 1;
}

/**
 * The signature base string: METHOD & encoded-url & encoded(sorted parameters).
 * Query parameters already on the URL count too, which matters because Garmin's callbackUrl carries
 * appUnlockRequestId in its query string.
 */
export function signatureBaseString(method, url, params = {}) {
  const u = new URL(url);
  const pairs = [];
  for (const [k, v] of u.searchParams) pairs.push([percentEncode(k), percentEncode(v)]);
  for (const [k, v] of Object.entries(params)) {
    if (k === "oauth_signature" || v === undefined || v === null) continue;
    pairs.push([percentEncode(k), percentEncode(v)]);
  }
  pairs.sort(compare);
  const normalized = pairs.map(([k, v]) => `${k}=${v}`).join("&");
  const baseUrl = `${u.protocol}//${u.host}${u.pathname}`;
  return [method.toUpperCase(), percentEncode(baseUrl), percentEncode(normalized)].join("&");
}

function toBase64(bytes) {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

export async function hmacSha1Base64(key, message) {
  const encoder = new TextEncoder();
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    encoder.encode(key),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(message));
  return toBase64(new Uint8Array(signature));
}

function nonce() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Signs a request and returns the Authorization header value.
 *
 * `params` are extra request parameters that are part of the signed material (a form body, say). `token` and
 * `tokenSecret` exist only so the known OAuth1 test vector can be reproduced in the tests; Garmin needs neither.
 */
export async function sign({
  method,
  url,
  consumerKey,
  consumerSecret,
  token = "",
  tokenSecret = "",
  params = {},
  nonce: givenNonce,
  timestamp = String(Math.floor(Date.now() / 1000)),
}) {
  if (!consumerKey || !consumerSecret) throw new Error("missing Garmin OAuth consumer key or secret");

  const oauth = {
    oauth_consumer_key: consumerKey,
    oauth_nonce: givenNonce ?? nonce(),
    oauth_signature_method: "HMAC-SHA1",
    oauth_timestamp: timestamp,
    oauth_version: "1.0",
  };
  if (token) oauth.oauth_token = token;

  const base = signatureBaseString(method, url, { ...params, ...oauth });
  const signingKey = `${percentEncode(consumerSecret)}&${percentEncode(tokenSecret)}`;
  const signature = await hmacSha1Base64(signingKey, base);

  const header = Object.keys({ ...oauth, oauth_signature: signature })
    .sort()
    .map((k) => {
      const value = k === "oauth_signature" ? signature : oauth[k];
      return `${percentEncode(k)}="${percentEncode(value)}"`;
    })
    .join(", ");

  return { header: `OAuth ${header}`, signature, base };
}
