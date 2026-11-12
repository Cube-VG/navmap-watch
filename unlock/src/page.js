// The three small pages this service serves. Plain HTML strings: no framework, no build step.

const escapeHtml = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );

/** JSON for an inline script; escaping `<` prevents a crafted request id from closing the script tag. */
const scriptJson = (value) => JSON.stringify(value).replace(/</g, "\\u003c");

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
  .status { min-height: 1.2em; margin: .6rem 0 0; color: #6b6b70; font-size: .85rem; }
  .status.error { color: #b3261e; }
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

export function paymentPage({ amount, currency, requestId }) {
  const price = formatMoney(amount, currency);
  return shell(
    "Unlock NavMap",
    `<h1>Unlock NavMap</h1>
<p class="sub">One payment, yours for good. No subscription.</p>
<p class="price">${escapeHtml(price)}</p>
<p class="sub">Pay any time — on day 2 or on day 200. Turns keep working either way.</p>
<button id="pay" type="button">Pay ${escapeHtml(price)}</button>
<p id="status" class="status" role="status" aria-live="polite"></p>
<p class="note">Secure payment by Razorpay. By paying you agree to the <a href="https://navmap.download/refunds.html">refund policy</a>.</p>
<p class="note"><strong>Already paid?</strong> Don't pay again. Reopen NavMap from the Connect IQ Store; if it still asks for payment, email support@navmap.download and we will sort it out.</p>
<script src="https://checkout.razorpay.com/v1/checkout.js"></script>
<script>
  var requestId = ${scriptJson(requestId)};
  var payButton = document.getElementById("pay");
  var statusLine = document.getElementById("status");
  var completed = false;

  function setStatus(message, isError) {
    statusLine.textContent = message || "";
    statusLine.classList.toggle("error", Boolean(isError));
  }

  function setBusy(busy) {
    payButton.disabled = busy;
  }

  async function createOrder() {
    var response = await fetch("/api/create-order", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ requestId: requestId })
    });
    var data = await response.json().catch(function () { return {}; });
    if (!response.ok || !data.order_id) {
      throw new Error(data.error || "Could not start the payment.");
    }
    return data;
  }

  async function verifyPayment(response) {
    setStatus("Verifying payment…");
    try {
      var result = await fetch("/api/verify-payment", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          razorpay_order_id: response.razorpay_order_id,
          razorpay_payment_id: response.razorpay_payment_id,
          razorpay_signature: response.razorpay_signature
        })
      });
      var data = await result.json().catch(function () { return {}; });
      if (!result.ok || !data.success) {
        throw new Error(data.error || "We couldn't verify the payment.");
      }
      window.location.href = "/return?rid=" + encodeURIComponent(requestId);
    } catch (error) {
      setBusy(false);
      setStatus(error.message || "We couldn't verify the payment.", true);
    }
  }

  function openCheckout(order) {
    if (typeof Razorpay === "undefined") {
      setBusy(false);
      setStatus("The payment window could not load. Check your connection and try again.", true);
      return;
    }
    var checkout = new Razorpay({
      key: order.key_id,
      order_id: order.order_id,
      amount: order.amount,
      currency: order.currency,
      name: "NavMap",
      description: "NavMap unlock",
      theme: { color: "#1a73e8" },
      handler: function (response) {
        completed = true;
        verifyPayment(response);
      },
      modal: {
        ondismiss: function () {
          if (completed) return;
          setBusy(false);
          setStatus("Payment cancelled. You can try again.");
        }
      }
    });
    checkout.on("payment.failed", function (response) {
      setBusy(false);
      var description = response && response.error && response.error.description;
      setStatus(description ? "Payment failed: " + description : "Payment failed. Please try again.", true);
    });
    checkout.open();
  }

  payButton.addEventListener("click", async function () {
    setBusy(true);
    setStatus("Opening secure checkout…");
    try {
      var order = await createOrder();
      openCheckout(order);
    } catch (error) {
      setBusy(false);
      setStatus(error.message || "Could not start the payment.", true);
    }
  });
</script>`,
  );
}

export function paypalPage({ amount, currency, requestId, clientId, appPageUrl }) {
  const price = formatMoney(amount, currency);
  return shell(
    "Unlock NavMap",
    `<h1>Unlock NavMap</h1>
<p class="sub">One payment, yours for good. No subscription.</p>
<p class="price">${escapeHtml(price)}</p>
<p class="sub">Pay with PayPal or a card. The price is charged in US dollars.</p>
<div id="paypal-button-container"></div>
<p id="status" class="status" role="status" aria-live="polite"></p>
<p class="note">Secure payment by PayPal. By paying you agree to the <a href="https://navmap.download/refunds.html">refund policy</a>.</p>
<p class="note"><strong>Already paid?</strong> Don't pay again. Reopen NavMap from the Connect IQ Store; if it still asks for payment, email support@navmap.download and we will sort it out.</p>
<p class="note"><a href="${escapeHtml(appPageUrl ?? "https://apps.garmin.com/apps/f18fe040-5bb7-41a0-afb0-f61edf418dab")}">Back to NavMap in the Connect IQ Store</a></p>
<script src="https://www.paypal.com/sdk/js?client-id=${encodeURIComponent(clientId)}&currency=USD&intent=capture&components=buttons"></script>
<script>
  var requestId = ${scriptJson(requestId)};
  var statusLine = document.getElementById("status");

  function setStatus(message, isError) {
    statusLine.textContent = message || "";
    statusLine.classList.toggle("error", Boolean(isError));
  }

  if (typeof paypal === "undefined") {
    setStatus("PayPal could not load. Check your connection and try again.", true);
  } else {
    paypal.Buttons({
      style: { layout: "vertical", color: "blue", shape: "pill", label: "paypal", height: 45 },
      createOrder: async function () {
        setStatus("Opening PayPal…");
        var response = await fetch("/api/paypal/create-order", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ requestId: requestId })
        });
        var data = await response.json().catch(function () { return {}; });
        if (!response.ok || !data.order_id) {
          throw new Error(data.error || "Could not start the PayPal payment.");
        }
        return data.order_id;
      },
      onApprove: async function (data) {
        setStatus("Capturing payment…");
        var response = await fetch("/api/paypal/capture-order", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ requestId: requestId, orderId: data.orderID })
        });
        var result = await response.json().catch(function () { return {}; });
        if (!response.ok || !result.success) {
          throw new Error(result.error || "PayPal captured the payment, but the unlock is still being confirmed.");
        }
        window.location.href = "/return?rid=" + encodeURIComponent(requestId);
      },
      onCancel: function () {
        setStatus("Payment cancelled. You can try again.");
      },
      onError: function (error) {
        console.error(error);
        setStatus("PayPal could not complete the payment. Please try again.", true);
      }
    }).render("#paypal-button-container").catch(function () {
      setStatus("PayPal could not start. Check your connection and try again.", true);
    });
  }
</script>`,
  );
}

export function internationalPage({ appPageUrl }) {
  return shell(
    "International checkout",
    `<h1>International checkout is being set up</h1>
<p class="sub">NavMap's India checkout is live. Card payments from outside India are not available yet, so nothing has been charged.</p>
<p class="sub">When the international checkout is ready, open the Unlock button in the Connect IQ Store again.</p>
<p class="note">Questions: <a href="mailto:support@navmap.download">support@navmap.download</a></p>
<p class="note"><a href="${escapeHtml(appPageUrl ?? "https://apps.garmin.com/apps/f18fe040-5bb7-41a0-afb0-f61edf418dab")}">Back to NavMap in the Connect IQ Store</a></p>`,
  );
}

export function thanksPage({ appPageUrl, unlocked }) {
  return shell(
    "Thank you",
    `<h1>${unlocked ? "NavMap is unlocked" : "Payment received"}</h1>
<p class="sub">${
      unlocked
        ? "Garmin has unlocked NavMap for your account. Open it on your watch."
        : "We've verified the payment and are waiting for Garmin to mark the app unlocked. This usually takes a few seconds."
    }</p>
<p class="sub">${
      unlocked
        ? "If the watch still shows the trial, update NavMap from the Connect IQ Store. Reinstalling only refreshes the entitlement; the unlock belongs to your Garmin account, not to the install."
        : "Wait a moment, then reopen NavMap. If it still shows the trial, update or reinstall it to refresh the unlock. You will not be charged again."
    }</p>
<ul>
  <li>Open the Connect IQ Store on your phone.</li>
  <li>Open NavMap, or update it if the trial screen still appears.</li>
  <li>The trial countdown should be gone.</li>
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
