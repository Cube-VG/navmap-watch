package com.cubevg.navbridge

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * NavMap Pro: the one-time $1.99 unlock that turns on the map picture and the route line (PLAN.md, step 6). Turns,
 * arrows and distances stay free, so NavBridge is still useful with nothing bought.
 *
 * Off unless the build passes `-PNAVBRIDGE_PRO_UNLOCK=true` ([BuildConfig.PRO_UNLOCK]): every GitHub APK and every
 * development build keeps the whole map, and only the Play build charges. That switch is read in [enabled], which
 * [entitled] honours, so the rest of the app can ask "may this user see the map?" without knowing about billing.
 *
 * The purchase is Play Billing, not Garmin's: developers in India can't sign up for Garmin's payments, and Play takes
 * 15%. Nothing here may link to a payment outside Play (see the Play billing rules in PLAN.md).
 *
 * [entitled] is called on NavBridge's HTTP worker thread for every poll from the watch, so it reads the stored flag in
 * [Prefs] instead of touching Play. Play is only asked in [start] (when NavBridge opens) and after a purchase.
 */
object Pro {
    const val PRODUCT_ID = "navmap_pro"

    /** Days of free map before the unlock is needed. Counted from the first run with the unlock switched on. */
    const val TRIAL_DAYS = 7L

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val main = Handler(Looper.getMainLooper())

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private var appContext: Context? = null

    /** Called on the main thread when the entitlement or the Play price changes, so the screen can redraw. */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** Whether this build charges at all. False in every GitHub and development build. */
    val enabled: Boolean get() = BuildConfig.PRO_UNLOCK

    /** Play's price for [PRODUCT_ID] ("₹165.00" and the like), or null until Play has answered. */
    val price: String? get() = details?.oneTimePurchaseOfferDetails?.formattedPrice

    /**
     * Whether the map picture and the route line may be shown. True whenever the unlock is switched off, so the
     * default GitHub build behaves exactly as before.
     *
     * With it on, a bought unlock is honoured, then the trial, which starts with the first ride. A refunded or absent
     * purchase falls back to the trial, and a refund doesn't restart it (see [Prefs.trialStartedAt]; uninstalling does,
     * since Android clears app data and a one-time Play product carries no trial of its own).
     */
    fun entitled(context: Context): Boolean {
        if (!enabled) return true
        if (Prefs.pro(context)) return true
        val now = System.currentTimeMillis()
        val started = Prefs.trialStartedAt(context)
        if (started == 0L) {
            Prefs.setTrialStartedAt(context, now)
            return true
        }
        return now - started < TRIAL_DAYS * DAY_MS
    }

    /** Whole days left in the trial, 0 once it is over and 0 always when the unlock is switched off. */
    fun trialDaysLeft(context: Context): Int {
        if (!enabled) return 0
        val started = Prefs.trialStartedAt(context)
        if (started == 0L) return TRIAL_DAYS.toInt()
        val left = TRIAL_DAYS * DAY_MS - (System.currentTimeMillis() - started)
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    /**
     * True once the seven days are over and nothing has been bought, so the screen should ask for the purchase rather
     * than quietly turning the map off. Always false when the unlock is switched off.
     */
    fun needsPurchase(context: Context): Boolean =
        enabled && !Prefs.pro(context) && trialDaysLeft(context) <= 0

    /** Connects to Play and refreshes the price and the entitlement. Main thread; cheap to call again. */
    fun start(context: Context) {
        if (!enabled) return
        val app = context.applicationContext
        appContext = app
        val existing = client
        if (existing != null) {
            if (existing.isReady) {
                queryDetails()
                queryOwned()
            }
            return
        }
        val billing = BillingClient.newBuilder(app)
            .setListener { result, purchases -> onPurchases(result, purchases) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = billing
        billing.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        // A phone without Play (or a build whose signature Play doesn't know) lands here. The trial
                        // still works and turns are unaffected, so this is only worth a log line.
                        DebugLog.log("Play Billing is unavailable (code ${result.responseCode}); Pro can't be bought")
                        return
                    }
                    queryDetails()
                    queryOwned()
                }

                override fun onBillingServiceDisconnected() {
                    // Left to the next start() to reconnect; nothing here waits for Play.
                }
            },
        )
    }

    /**
     * Starts the Play purchase screen. Returns false when Play hasn't answered yet, which the caller reports, so the
     * button does nothing silently rather than crashing.
     */
    fun buy(activity: Activity): Boolean {
        val billing = client
        val product = details
        if (billing == null || !billing.isReady || product == null) return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build(),
                ),
            )
            .build()
        return billing.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    /** Asks Play what this account owns, for "Restore purchase" and after a reinstall. Main thread. */
    fun restore(context: Context) {
        start(context)
        if (client?.isReady == true) queryOwned()
    }

    /** Play's one-time product, kept for the purchase screen. */
    private fun queryDetails() {
        val billing = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        billing.queryProductDetailsAsync(params) { result, products ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                DebugLog.log("Play didn't return $PRODUCT_ID (code ${result.responseCode})")
                notifyChange()
                return@queryProductDetailsAsync
            }
            details = products.firstOrNull { it.productId == PRODUCT_ID }
            if (details == null) DebugLog.log("$PRODUCT_ID isn't set up in Play Console yet")
            notifyChange()
        }
    }

    /** Everything this account owns, so a reinstall or another phone gets the unlock back. */
    private fun queryOwned() {
        val billing = client ?: return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        billing.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                DebugLog.log("Play didn't list purchases (code ${result.responseCode})")
                return@queryPurchasesAsync
            }
            grant(purchases)
        }
    }

    private fun onPurchases(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                DebugLog.log("The Pro purchase didn't complete (code ${result.responseCode})")
            }
            return
        }
        grant(purchases.orEmpty())
    }

    /**
     * Records the unlock from Play's purchases and acknowledges any that are still open. Play refunds a purchase that
     * isn't acknowledged within three days, so [AcknowledgePurchaseParams] has to happen on the first sight of it.
     */
    private fun grant(purchases: List<Purchase>) {
        val app = appContext ?: return
        var owned = Prefs.pro(app)
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            if (!purchase.products.contains(PRODUCT_ID)) continue
            owned = true
            if (!purchase.isAcknowledged) acknowledge(purchase.purchaseToken)
        }
        if (owned != Prefs.pro(app)) {
            Prefs.setPro(app, owned)
            DebugLog.log(if (owned) "NavMap Pro unlocked" else "NavMap Pro is no longer owned")
            notifyChange()
        }
    }

    private fun acknowledge(token: String) {
        val billing = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        billing.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                DebugLog.log("Pro purchase acknowledged")
            } else {
                DebugLog.log("Couldn't acknowledge the Pro purchase (code ${result.responseCode})")
            }
        }
    }

    private fun notifyChange() {
        main.post { onChange?.invoke() }
    }

    /** One line for the setup checklist. */
    fun statusText(context: Context): String = when {
        !enabled -> "Not in this build: the map and route line are free."
        Prefs.pro(context) -> "Unlocked. Thank you!"
        Prefs.trialStartedAt(context) == 0L -> "$TRIAL_DAYS days free, starting with your first ride."
        trialDaysLeft(context) > 0 -> {
            val days = trialDaysLeft(context)
            "Trial: $days day${if (days == 1) "" else "s"} left."
        }
        else -> "Trial over. Map and route line are off; turns still work."
    }
}
