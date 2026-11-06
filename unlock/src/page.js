// The three small pages this service serves. Plain HTML strings: no framework, no build step.

const escapeHtml = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );

const SYMBOLS = { INR: "₹", USD: "$", EUR: "€", GBP: "£" };

export function formatMoney(amount, currency) {
  const major = Number(amount) / 100;
  const symbol = SYMBOLS[currency] ?? `${currency} `;
  // ₹199 has no decimals; $1.99 needs them.
  return `${symbol}${major % 1 === 0 ? major.toFixed(0) : major.toFixed(2)}`;
}

function shell(title, body) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${escapeHtml(title)}</title>
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; font: 16px/1.55 system-ui, -apple-system, "Segoe UI", sans-serif; background: #f6f3ec; color: #1c1c1e; padding: 24px; }
  main { width: 100%; max-width: 26rem; background: #fff; border-radius: 18px; padding: 28px; box-shadow: 0 10px 40px rgba(0,0,0,.08); }
  h1 { font-size: 1.35rem; margin: 0 0 .35rem; letter-spacing: -.01em; }
  .price { font-size: 2.4rem; font-weight: 700; letter-spacing: -.03em; margin: .6rem 0 .1rem; }
  .sub { color: #6b6b70; margin: 0 0 1.4rem; font-size: .95rem; }
  button { width: 100%; border: 0; border-radius: 12px; padding: 15px 18px; font: inherit; font-weight: 650; background: #1a73e8; color: #fff; cursor: pointer; }
  button:disabled { opacity: .55; cursor: default; }
  .note { color: #6b6b70; font-size: .82rem; margin-top: 1rem; }
  ul { padding-left: 1.1rem; color: #333; }
  a { color: #1a73e8; }
  @media (prefers-color-scheme: dark) {
    body { background: #111214; color: #f2f2f4; }
    main { background: #1b1d20; box-shadow: none; }
    .sub, .note { color: #a1a1a6; }
    ul { color: #cfcfd4; }
  }
</style>
</head>
<body><main>${body}</main></body>
</html>`;
}

export function paymentPage({ keyId, orderId, amount, currency, requestId, appPageUrl }) {
  const price = formatMoney(amount, currency);
  return shell(
    "Unlock NavMap",
    `<h1>Unlock NavMap</h1>
<p class="sub">One payment, yours for good. No subscription.</p>
<p class="price">${escapeHtml(price)}</p>
<p class="sub">Your seven-day trial has ended. Turns keep working either way.</p>
<button id="pay">Pay ${escapeHtml(price)}</button>
<p class="note">Secure payment by Razorpay. By paying you agree to the <a href="https://navmap.download/refunds.html">refund policy</a>.</p>
<script src="https://checkout.razorpay.com/v1/checkout.js"></script>
<script>
  var options = {
    key: ${JSON.stringify(keyId)},
    order_id: ${JSON.stringify(orderId)},
    amount: ${JSON.stringify(String(amount))},
    currency: ${JSON.stringify(currency)},
    name: "NavMap",
    description: "NavMap unlock",
    theme: { color: "#1a73e8" },
    handler: function () {
      window.location.href = "/return?rid=" + encodeURIComponent(${JSON.stringify(requestId)});
    },
    modal: {
      ondismiss: function () { document.getElementById("pay").disabled = false; }
    }
  };
  var rzp = new Razorpay(options);
  rzp.on("payment.failed", function (response) {
    document.getElementById("pay").disabled = false;
    alert("Payment failed: " + (response.error && response.error.description ? response.error.description : "please try again"));
  });
  document.getElementById("pay").addEventListener("click", function () {
    this.disabled = true;
    rzp.open();
  });
</script>`,
  );
}

export function thanksPage({ appPageUrl, unlocked }) {
  return shell(
    "Thank you",
    `<h1>${unlocked ? "NavMap is unlocked" : "Payment received"}</h1>
<p class="sub">${
      unlocked
        ? "Open the Connect IQ Store and download NavMap again — it will run without the trial now."
        : "We're still confirming the payment with Garmin. This usually takes a few seconds: reopen the store, or check the app again shortly."
    }</p>
<ul>
  <li>Open the Connect IQ Store on your phone.</li>
  <li>Go to NavMap and reinstall or update it.</li>
  <li>The trial countdown is gone.</li>
</ul>
<p class="note"><a href="${escapeHtml(appPageUrl ?? "https://apps.garmin.com/apps/f18fe040-5bb7-41a0-afb0-f61edf418dab")}">Back to NavMap in the Connect IQ Store</a></p>`,
  );
}

export function errorPage(message, detail) {
  return shell(
    "Something went wrong",
    `<h1>Something went wrong</h1>
<p class="sub">${escapeHtml(message)}</p>
${detail ? `<p class="note">${escapeHtml(detail)}</p>` : ""}
<p class="note">Nothing has been charged. If money left your account, email the address on navmap.download and it will be refunded.</p>`,
  );
}
