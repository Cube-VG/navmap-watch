package com.cubevg.navmap.core

/** The fields NavBridge reads from a posted notification. */
data class RawNotification(
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val category: String? = null,
    val ongoing: Boolean = false,
)

enum class NavKind { STARTING, NAVIGATING, REROUTING, ARRIVED }

/** One navigation update from Google Maps, in the form the watch shows it. */
data class NavInfo(
    val kind: NavKind,
    /** The maneuver, e.g. "Turn left onto Elm Street". */
    val instruction: String,
    /** Distance to the maneuver, when Maps shows one. */
    val distance: Distance?,
    /** Trip line for the bottom of the watch, e.g. "Arrive 10:01" or "13 min · 11:55". */
    val trip: String,
    /** Arrival time as printed, e.g. "10:01" or "8:05 PM". */
    val eta: String?,
)

/**
 * Reads Google Maps' turn-by-turn notification. Two layouts are in use:
 * - Live Update (Android 16+): title "350 m · Turn left onto Elm St", subText "Arrive 10:01".
 * - Classic: title "220 m", text "toward Queen St", subText "13 min · 4.6 mi · 11:55 ETA".
 * Either may arrive without a distance, and status cards ("Rerouting…") carry no maneuver.
 */
object MapsNotificationParser {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"
    const val CATEGORY_NAVIGATION = "navigation"

    private val starting = listOf(
        "starting navigation", "正在啟動導航", "正在启动导航", "navigation wird gestartet", "avvio navigazione",
        "iniciando navegación", "iniciando a navegação", "démarrage de la navigation", "navigatie starten",
    )
    private val rerouting = listOf(
        "rerouting", "re-routing", "正在重新規劃路線", "正在重新规划路线", "route wird neu berechnet", "neuberechnung",
        "ricalcolo", "recalculando", "nouvel itinéraire", "recalcul", "herberekenen", "nieuwe route",
    )
    private val arrived = listOf(
        "you have arrived", "you've arrived", "arrived", "arriving", "sei arrivato", "vous êtes arrivé",
        "has llegado", "sie haben ihr ziel erreicht", "je bent aangekomen", "você chegou", "dotarłeś", "抵達目的地",
        "到达目的地",
    )

    // Close to the end: "Arrive in 1 min (70 m)". The bracket is the distance to the destination.
    private val arriveIn = Regex("arrive in .*\\(([^()]+)\\)", RegexOption.IGNORE_CASE)
    private val clock = Regex("\\d{1,2}[:.]\\d{2}(?:\\s?[AaPp]\\.?\\s?[Mm]\\.?)?")
    private val etaSuffix = Regex("\\s*ETA$", RegexOption.IGNORE_CASE)

    /** Returns null for anything that isn't a Google Maps navigation notification. */
    fun parse(n: RawNotification): NavInfo? {
        if (n.packageName != MAPS_PACKAGE) return null
        val title = Text.clean(n.title)
        val text = Text.clean(n.text)
        val sub = Text.clean(n.subText)
        if (title.isEmpty() && text.isEmpty()) return null

        val headline = title.ifEmpty { text }.lowercase()
        val kind = when {
            starting.any { headline.startsWith(it) } -> NavKind.STARTING
            rerouting.any { headline.startsWith(it) } -> NavKind.REROUTING
            arrived.any { headline.startsWith(it) } -> NavKind.ARRIVED
            else -> NavKind.NAVIGATING
        }

        var distance: Distance? = null
        var instruction: String
        val titleFields = Text.fields(title)
        val titleLead = titleFields.firstOrNull()?.let(Distances::parse)
        val titleDistance = Distances.parse(title)
        val textDistance = Distances.parse(text)
        when {
            // Live Update: "350 m · Turn left onto Elm St"
            titleFields.size >= 2 && titleLead != null -> {
                distance = titleLead
                instruction = titleFields.drop(1).joinToString(" · ")
            }
            // Classic: the title is only the distance and the text is the maneuver or road.
            titleDistance != null -> {
                distance = titleDistance
                instruction = text
            }
            // Maneuver in the title, distance in the text.
            title.isNotEmpty() && textDistance != null -> {
                distance = textDistance
                instruction = title
            }
            title.isNotEmpty() -> {
                instruction = title
                val near = arriveIn.matchEntire(title)
                if (near != null) {
                    distance = Distances.parse(near.groupValues[1])
                    if (text.isNotEmpty()) instruction = text
                } else if (kind != NavKind.NAVIGATING && text.firstOrNull()?.isLowerCase() == true) {
                    // "Rerouting" + "to Home", "Arriving" + "at Destination"
                    instruction = "$title $text"
                }
            }
            // No title: the text is the road; any distance in subText is the whole trip, not the turn.
            else -> instruction = text
        }

        if (n.category != CATEGORY_NAVIGATION) {
            // Builds that don't set the category: accept ongoing cards that look like turn-by-turn.
            val looksLikeNav = kind != NavKind.NAVIGATING || distance != null || clock.containsMatchIn(sub)
            if (!n.ongoing || !looksLikeNav) return null
        }

        val tripSource = when {
            sub.any { it.isDigit() } -> sub
            text != instruction && clock.containsMatchIn(text) -> text
            else -> ""
        }
        val eta = clock.findAll(tripSource).lastOrNull()?.value
        return NavInfo(kind, instruction, distance, tripLine(tripSource), eta)
    }

    // Classic "13 min · 4.6 mi · 11:55 ETA" becomes "13 min · 11:55" so it fits the bottom of a round screen.
    private fun tripLine(sub: String): String {
        val fields = Text.fields(sub)
        return if (fields.size >= 3) "${fields.first()} · ${etaSuffix.replace(fields.last(), "")}" else sub
    }
}

/**
 * Why Android took Google Maps' navigation notification away, from NotificationListenerService's REASON_* code, and
 * whether that means Maps stopped navigating. Only Maps itself, or Maps being stopped or blocked, ends it: a card
 * swiped away, timed out or cleared by another app leaves Maps navigating, and its next update brings the card back.
 */
object MapsRemoval {
    data class Why(val text: String, val mapsStopped: Boolean)

    fun of(reason: Int): Why = when (reason) {
        8, 9 -> Why("Google Maps removed it", true) // REASON_APP_CANCEL, REASON_APP_CANCEL_ALL
        5 -> Why("Google Maps was stopped, updated or turned off", true) // REASON_PACKAGE_CHANGED
        6, 15 -> Why("the phone's user or work profile was stopped", true) // REASON_USER_STOPPED, REASON_PROFILE_TURNED_OFF
        7, 14 -> Why("Google Maps' notifications are blocked or paused", true) // REASON_PACKAGE_BANNED, REASON_PACKAGE_SUSPENDED
        17, 20 -> Why("Google Maps' navigation notifications are turned off", true) // REASON_CHANNEL_BANNED, _REMOVED
        21 -> Why("Google Maps' data was cleared", true) // REASON_CLEAR_DATA
        1 -> Why("it was tapped", false) // REASON_CLICK
        2 -> Why("it was swiped away", false) // REASON_CANCEL
        3 -> Why("notifications were cleared", false) // REASON_CANCEL_ALL
        10, 11 -> Why("another app removed it", false) // REASON_LISTENER_CANCEL, _ALL: Garmin Connect, say
        18 -> Why("it was snoozed", false) // REASON_SNOOZED
        19 -> Why("it timed out without an update", false) // REASON_TIMEOUT
        22 -> Why("the phone's notification assistant removed it", false) // REASON_ASSISTANT_CANCEL
        23 -> Why("the phone was locked down", false) // REASON_LOCKDOWN
        4, 12, 13, 16 -> Why("Android regrouped or dropped it (reason $reason)", false)
        else -> Why("Android gave reason $reason", true) // unknown: end, as before
    }
}
