import test from "node:test";
import assert from "node:assert/strict";
import { percentEncode, signatureBaseString, sign } from "../src/oauth1.js";

test("percentEncode follows RFC 3986, not encodeURIComponent", () => {
  assert.equal(percentEncode("Hello Ladies + Gentlemen, a signed OAuth request!"),
    "Hello%20Ladies%20%2B%20Gentlemen%2C%20a%20signed%20OAuth%20request%21");
  // encodeURIComponent leaves these alone; OAuth1 does not.
  assert.equal(percentEncode("!*'()"), "%21%2A%27%28%29");
  assert.equal(percentEncode("a b&c=d"), "a%20b%26c%3Dd");
});

test("signature base string sorts encoded keys and includes the URL's own query", () => {
  const base = signatureBaseString("GET", "https://apps.garmin.com/appUnlock?appUnlockRequestId=abc-123", {
    oauth_consumer_key: "key",
    oauth_nonce: "n",
    oauth_signature_method: "HMAC-SHA1",
    oauth_timestamp: "1",
    oauth_version: "1.0",
  });
  const [method, url, params] = base.split("&");
  assert.equal(method, "GET");
  assert.equal(decodeURIComponent(url), "https://apps.garmin.com/appUnlock");
  // appUnlockRequestId sorts before oauth_*, and oauth_consumer_key before oauth_nonce.
  const decoded = decodeURIComponent(params);
  assert.ok(decoded.startsWith("appUnlockRequestId=abc-123&oauth_consumer_key=key&oauth_nonce=n"));
});

test("reproduces the published OAuth1 HMAC-SHA1 test vector", async () => {
  // From the OAuth 1.0a spec's worked example (Twitter's documentation). If this passes, the signing key
  // construction, the parameter normalisation and the encoding are all correct.
  const { signature, base } = await sign({
    method: "POST",
    url: "https://api.twitter.com/1.1/statuses/update.json?include_entities=true",
    consumerKey: "xvz1evFS4wEEPTGEFPHBog",
    consumerSecret: "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
    token: "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
    tokenSecret: "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
    params: { status: "Hello Ladies + Gentlemen, a signed OAuth request!" },
    nonce: "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
    timestamp: "1318622958",
  });

  assert.equal(
    base,
    "POST&https%3A%2F%2Fapi.twitter.com%2F1.1%2Fstatuses%2Fupdate.json&include_entities%3Dtrue" +
      "%26oauth_consumer_key%3Dxvz1evFS4wEEPTGEFPHBog%26oauth_nonce%3DkYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg" +
      "%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1318622958" +
      "%26oauth_token%3D370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb%26oauth_version%3D1.0" +
      "%26status%3DHello%2520Ladies%2520%252B%2520Gentlemen%252C%2520a%2520signed%2520OAuth%2520request%2521",
  );
  assert.equal(signature, "hCtSmYh+iHYCEqBWrE7C7hYmtUk=");
});

test("a one-legged sign has no oauth_token and still produces a header", async () => {
  const { header } = await sign({
    method: "GET",
    url: "https://apps.garmin.com/appUnlock?appUnlockRequestId=1fe443e5",
    consumerKey: "k",
    consumerSecret: "s",
  });
  assert.match(header, /^OAuth /);
  assert.match(header, /oauth_signature_method="HMAC-SHA1"/);
  assert.ok(!header.includes("oauth_token="), "one-legged requests carry no token");
});

test("refuses to sign without credentials", async () => {
  await assert.rejects(() => sign({ method: "GET", url: "https://x.test/", consumerKey: "", consumerSecret: "" }));
});
