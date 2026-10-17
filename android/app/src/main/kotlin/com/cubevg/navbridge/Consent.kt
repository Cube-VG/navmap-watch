package com.cubevg.navbridge

import android.app.Activity
import android.content.Context
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * What Google Maps Platform's terms ask of an app that shows Google Maps content and uses its users' location: say
 * that it includes Google Maps features and content, under Google's end user terms and privacy policy (section
 * 3.2.2(a)); say what it collects and whether the location is combined with other providers' data; and have the
 * user's express, prior, revocable consent before it obtains or keeps their location (4.4(c)(iii)). Shown by
 * [MainActivity] before anything else, and always readable there under Privacy and terms.
 */
object Consent {
    private const val TERMS_URL = "https://maps.google.com/help/terms_maps/"
    private const val PRIVACY_URL = "https://policies.google.com/privacy"

    private const val TEXT =
        "<p>NavBridge shows Google Maps’ turns on NavMap, on your Garmin watch. NavBridge and NavMap include Google " +
            "Maps features and content: the map pictures and the blue route line come from Google Maps Platform, " +
            "with your own Google Maps key.</p>" +
            "<p><b>What NavBridge uses</b><br>" +
            "• Google Maps’ navigation notification: the next turn, the distance and the arrival time, to show them " +
            "on the watch.<br>" +
            "• Your location: where your watch is while NavMap is open, and where your phone is when you share a " +
            "place with NavBridge. It goes to Google for the route line and the map pictures, which Garmin Connect " +
            "fetches for the watch, possibly through Garmin’s servers. NavBridge doesn’t save it, doesn’t combine " +
            "it with other providers’ data, and forgets it when you withdraw.</p>" +
            "<p>Nothing is sent to the makers of NavBridge and NavMap: there’s no account and no server of ours.</p>" +
            "<p>Your use of Google Maps features and content is subject to the " +
            "<a href=\"$TERMS_URL\">Google Maps End User Additional Terms of Service</a> and the " +
            "<a href=\"$PRIVACY_URL\">Google Privacy Policy</a>.</p>" +
            "<p>Until you agree, NavMap shows no map or turns. You can withdraw at any time, under Privacy and terms " +
            "on NavBridge’s screen.</p>"

    fun text(): Spanned = HtmlCompat.fromHtml(TEXT, HtmlCompat.FROM_HTML_MODE_COMPACT)

    /** Makes the two links in [text] open in the browser. */
    fun linkify(view: TextView) {
        view.movementMethod = LinkMovementMethod.getInstance()
    }

    /** Asks with the whole text; [onAnswer] runs after either button. */
    fun ask(activity: Activity, onAnswer: () -> Unit): AlertDialog {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Before you start")
            .setMessage(text())
            .setCancelable(false)
            .setPositiveButton("Agree") { _, _ ->
                agree(activity)
                onAnswer()
            }
            .setNegativeButton("Not now") { _, _ -> onAnswer() }
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.let(::linkify)
        return dialog
    }

    private fun agree(context: Context) {
        Prefs.setConsented(context, true)
        // A NavMap that was told to wait for this pairs with its next poll.
        NavServer.pairing.openWindow()
        DebugLog.log("You agreed to Google's terms and to NavBridge using your location")
    }

    /** Forgets every position NavBridge has; NavMap is told with its next poll and drops its key and route line. */
    fun withdraw(context: Context) {
        Prefs.setConsented(context, false)
        if (Simulator.running) Simulator.stop()
        NavSession.forgetLocation()
        DebugLog.log("You withdrew your consent: NavBridge forgot your location, and NavMap gets no map or turns")
    }
}
