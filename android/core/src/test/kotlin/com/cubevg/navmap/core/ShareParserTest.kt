package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareParserTest {
    @Test
    fun shareText() {
        val shared = ShareParser.fromShareText("Joe's Pizza\n1435 Broadway, New York, NY 10018\nhttps://maps.app.goo.gl/AbC123xYz")
        assertEquals("https://maps.app.goo.gl/AbC123xYz", shared.url)
        assertEquals("Joe's Pizza", shared.name)
        assertEquals(listOf("Joe's Pizza", "1435 Broadway, New York, NY 10018"), shared.lines)
        assertTrue(ShareParser.isShortLink(shared.url!!))
    }

    @Test
    fun shareTextWithSubjectAndInlineLink() {
        val shared = ShareParser.fromShareText("Dropped pin https://maps.app.goo.gl/q1", subject = "Dropped pin")
        assertEquals("https://maps.app.goo.gl/q1", shared.url)
        assertEquals("Dropped pin", shared.name)
    }

    @Test
    fun shortLinks() {
        assertTrue(ShareParser.isShortLink("https://maps.app.goo.gl/xyz"))
        assertTrue(ShareParser.isShortLink("https://goo.gl/maps/xyz"))
        assertFalse(ShareParser.isShortLink("https://www.google.com/maps/place/x"))
        assertFalse(ShareParser.isShortLink("not a url"))
    }

    @Test
    fun placeLinkUsesThePinNotTheView() {
        val url = "https://www.google.com/maps/place/Joe's+Pizza/@40.7546,-73.9870,17z/data=!3m1!4b1!4m6!3m5" +
            "!1s0x89c259ab:0x5f3b!8m2!3d40.754639!4d-73.986732!16s%2Fg%2F1tdr"
        assertEquals(Destination.Point(40.754639, -73.986732, "Joe's Pizza"), ShareParser.destination(url))
    }

    @Test
    fun queryLinks() {
        assertEquals(Destination.Point(40.7128, -74.006, null), ShareParser.destination("https://maps.google.com/?q=40.7128,-74.0060"))
        assertEquals(
            Destination.Address("1600 Amphitheatre Pkwy", "1600 Amphitheatre Pkwy"),
            ShareParser.destination("https://maps.google.com/maps?q=1600+Amphitheatre+Pkwy"),
        )
        assertEquals(
            Destination.Place("ChIJLU7jZClu5kcR4PcOOO6p3I0", "Eiffel Tower"),
            ShareParser.destination("https://www.google.com/maps/search/?api=1&query=Eiffel%20Tower&query_place_id=ChIJLU7jZClu5kcR4PcOOO6p3I0"),
        )
        assertEquals(
            Destination.Point(48.8584, 2.2945, null),
            ShareParser.destination("https://www.google.com/maps/dir/?api=1&destination=48.8584,2.2945&travelmode=driving"),
        )
    }

    @Test
    fun directionsLink() {
        val url = "https://www.google.com/maps/dir/Home/Office+Park/@51.5,-0.12,13z/data=!3m1!4b1!4m13!4m12" +
            "!1m5!1m1!1s0x0:0x0!2m2!1d-0.1276!2d51.5072!1m5!1m1!1s0x0:0x1!2m2!1d-0.0754!2d51.5155"
        assertEquals(Destination.Point(51.5155, -0.0754, null), ShareParser.destination(url))
        assertEquals(
            Destination.Address("Office Park", "Office Park"),
            ShareParser.destination("https://www.google.com/maps/dir/Home/Office+Park/@51.5,-0.12,13z"),
        )
    }

    @Test
    fun searchLink() {
        assertEquals(
            Destination.Address("pizza near me", "pizza near me"),
            ShareParser.destination("https://www.google.com/maps/search/pizza+near+me/@40.7,-74.0,14z"),
        )
    }

    @Test
    fun geoUris() {
        assertEquals(Destination.Point(37.422, -122.084, "Googleplex"), ShareParser.destination("geo:0,0?q=37.422,-122.084(Googleplex)"))
        assertEquals(Destination.Point(37.422, -122.084, null), ShareParser.destination("geo:37.422,-122.084?z=15"))
        assertEquals(Destination.Address("1600 Amphitheatre Pkwy", "1600 Amphitheatre Pkwy"), ShareParser.destination("geo:0,0?q=1600+Amphitheatre+Pkwy"))
        assertEquals(Destination.Point(37.422, -122.084, null), ShareParser.destination("google.navigation:q=37.422,-122.084&mode=l"))
        assertNull(ShareParser.destination("geo:0,0"))
    }

    @Test
    fun plainMapViewHasNoDestination() {
        val url = "https://www.google.com/maps/@40.7,-74.0,14z"
        assertNull(ShareParser.destination(url))
        assertEquals(Destination.Point(40.7, -74.0), ShareParser.viewportCentre(url))
    }
}
