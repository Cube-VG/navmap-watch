package com.cubevg.navbridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.cubevg.navmap.core.Destination
import com.cubevg.navmap.core.MapsNotificationParser
import com.cubevg.navmap.core.ShareParser
import kotlin.concurrent.thread

/**
 * Google Maps → Share → NavBridge. Starts Google Maps navigation to the shared place and works out the route line
 * for the watch. Has no screen of its own.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = ShareParser.fromShareText(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        if (shared.url == null && shared.lines.isEmpty()) {
            done("Nothing to navigate to")
            return
        }
        // Read now, while NavBridge is on screen: Android only gives location to apps in the foreground.
        val origin = Locator.lastKnown(this)
        Toast.makeText(this, "Getting directions…", Toast.LENGTH_SHORT).show()
        thread(name = "share") {
            val destination = try {
                destinationOf(shared.url, shared.name, shared.lines)
            } catch (e: Exception) {
                DebugLog.log("Couldn't read the shared place: $e")
                null
            }
            runOnUiThread {
                if (destination == null) {
                    done("Couldn't find a place in what was shared")
                } else {
                    NavSession.setDestination(destination, origin)
                    startNavigation(destination)
                }
            }
        }
    }

    private fun destinationOf(url: String?, name: String?, lines: List<String>): Destination? {
        if (url != null) {
            val full = if (ShareParser.isShortLink(url)) RoutesClient.resolveShortLink(url) else url
            DebugLog.log("Shared link: $full")
            ShareParser.destination(full, name)?.let { return it }
        }
        // Text only, e.g. an address: Google geocodes it.
        return lines.takeIf { it.isNotEmpty() }?.let { Destination.Address(it.joinToString(", "), name) }
    }

    private fun startNavigation(destination: Destination) {
        val mode = Prefs.travelMode(this)
        val uri = when (destination) {
            is Destination.Point -> Uri.parse("google.navigation:q=${destination.lat},${destination.lng}&mode=${mode.maps}")
            is Destination.Address -> Uri.parse("google.navigation:q=${Uri.encode(destination.query)}&mode=${mode.maps}")
            is Destination.Place -> Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination.name.orEmpty())}" +
                    "&destination_place_id=${Uri.encode(destination.placeId)}&travelmode=${mode.urlMode}&dir_action=navigate",
            )
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(MapsNotificationParser.MAPS_PACKAGE))
            done(null)
        } catch (e: ActivityNotFoundException) {
            done("Google Maps isn't installed")
        }
    }

    private fun done(message: String?) {
        message?.let {
            DebugLog.log(it)
            Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
