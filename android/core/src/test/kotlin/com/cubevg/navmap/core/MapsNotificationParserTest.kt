package com.cubevg.navmap.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsNotificationParserTest {
    private val maps = MapsNotificationParser.MAPS_PACKAGE

    private fun nav(title: String? = null, text: String? = null, sub: String? = null) =
        MapsNotificationParser.parse(RawNotification(maps, title, text, sub, "navigation", ongoing = true))

    @Test
    fun liveUpdate() {
        val info = nav("350 m · Turn left onto Elm Street", sub = "Arrive 10:01")!!
        assertEquals(NavKind.NAVIGATING, info.kind)
        assertEquals("Turn left onto Elm Street", info.instruction)
        assertEquals(Distance("350 m", 350), info.distance)
        assertEquals("Arrive 10:01", info.trip)
        assertEquals("10:01", info.eta)
    }

    @Test
    fun classic() {
        val info = nav("220 m", "toward Queen St", "8 hr 37 min · 166 km · 5:46 p.m.")!!
        assertEquals("toward Queen St", info.instruction)
        assertEquals(220, info.distance?.meters)
        assertEquals("8 hr 37 min · 5:46 p.m.", info.trip)
        assertEquals("5:46 p.m.", info.eta)
    }

    @Test
    fun classicEtaSuffixIsDropped() {
        val info = nav("350 m", "Take exit 12", "4 min · 2.1 km · 07:40 ETA")!!
        assertEquals("4 min · 07:40", info.trip)
        assertEquals("07:40", info.eta)
    }

    @Test
    fun distanceInText() {
        val info = nav("Turn right onto Example Street", "1,2 km", "Arrive 23:59")!!
        assertEquals("Turn right onto Example Street", info.instruction)
        assertEquals(1200, info.distance?.meters)
    }

    @Test
    fun noDistanceInStreetName() {
        val info = nav("Turn right onto 5 m Street", sub = "Arrive 12:00")!!
        assertNull(info.distance)
        assertEquals("Turn right onto 5 m Street", info.instruction)
    }

    @Test
    fun tripDistanceIsNotTurnDistance() {
        val info = nav(text = "Example Rd", sub = "1 min · 270 m · 07:40 ETA")!!
        assertNull(info.distance)
        assertEquals("Example Rd", info.instruction)
    }

    @Test
    fun statusCards() {
        assertEquals(NavKind.STARTING, nav("Starting navigation…")!!.kind)
        assertEquals(NavKind.REROUTING, nav("Rerouting...", sub = "Arrive")!!.kind)
        assertEquals(NavKind.REROUTING, nav(text = "正在重新規劃路線…")!!.kind)
        val rerouting = nav("Rerouting", "to Example Destination")!!
        assertEquals("Rerouting to Example Destination", rerouting.instruction)
        val arriving = nav("Arriving", "at Destination")!!
        assertEquals(NavKind.ARRIVED, arriving.kind)
        assertEquals("Arriving at Destination", arriving.instruction)
        assertEquals("You have arrived at your destination", nav("You have arrived at your destination", "Now")!!.instruction)
        assertEquals(NavKind.ARRIVED, nav(text = "抵達目的地", sub = "預計到達時間：13:30")!!.kind)
    }

    @Test
    fun arriveInUsesDestinationName() {
        val info = nav("Arrive in 1 min (70 m)", "Example Destination")!!
        assertEquals("Example Destination", info.instruction)
        assertEquals(70, info.distance?.meters)
    }

    @Test
    fun ignoresOtherNotifications() {
        assertNull(MapsNotificationParser.parse(RawNotification("com.whatsapp", "200 m", "Turn left", null, "navigation", true)))
        // Maps, but not navigation: a commute card that isn't ongoing.
        assertNull(MapsNotificationParser.parse(RawNotification(maps, "Traffic on your commute", "25 min · Leave by 8:15", null, null, false)))
        // Ongoing but nothing nav-like, e.g. location sharing.
        assertNull(MapsNotificationParser.parse(RawNotification(maps, "Sharing your location", "with Alex", null, null, true)))
        assertNull(MapsNotificationParser.parse(RawNotification(maps, null, null, null, "navigation", true)))
    }

    @Test
    fun acceptsOngoingNavWithoutCategory() {
        val info = MapsNotificationParser.parse(RawNotification(maps, "200 m", "Turn left", "Arrive 12:00", null, true))
        assertNotNull(info)
    }

    /** Every capture and sample from PebbleNTN, checked against the distance, ETA and text they expect. */
    @Test
    fun pebbleNtnFixtures() {
        val json = javaClass.getResourceAsStream("/pebblentn/google-maps.json")!!.bufferedReader().readText()
        val fixtures = JSONObject(json).getJSONArray("fixtures")
        val failures = mutableListOf<String>()
        var checked = 0
        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i)
            val name = f.getString("name")
            val s = f.getJSONObject("snapshot")
            val e = f.getJSONObject("expected")
            val raw = RawNotification(
                f.getString("packageName"),
                s.optString("title").ifEmpty { null },
                s.optString("text").ifEmpty { null },
                s.optString("subText").ifEmpty { null },
                s.optString("category").ifEmpty { "navigation" },
                ongoing = true,
            )
            val info = MapsNotificationParser.parse(raw)
            if (info == null) {
                failures += "$name: not parsed"
                continue
            }
            checked++
            if (e.has("distanceMeters")) {
                val want = if (e.isNull("distanceMeters")) null else e.getInt("distanceMeters")
                if (info.distance?.meters != want) failures += "$name: distance ${info.distance} != $want"
            }
            if (e.optBoolean("noDistance") && info.distance != null) failures += "$name: distance ${info.distance} != none"
            if (e.has("secondaryText") && info.eta != e.getString("secondaryText")) {
                failures += "$name: eta ${info.eta} != ${e.getString("secondaryText")}"
            }
            // PebbleNTN shortens transit and walking cards to the stop or place name; NavMap keeps Google's wording.
            if (e.has("primaryText") && name !in keepsGoogleWording && info.instruction != e.getString("primaryText")) {
                failures += "$name: instruction '${info.instruction}' != '${e.getString("primaryText")}'"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(fixtures.length(), checked)
    }

    private val keepsGoogleWording = setOf("capture-walk-overview", "capture-transit-ride", "transit-ride-station-named-destination")
}
