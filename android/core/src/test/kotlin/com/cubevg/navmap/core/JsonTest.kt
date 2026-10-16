package com.cubevg.navmap.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun writesWatchReplies() {
        val reply = linkedMapOf<String, Any?>(
            "t" to "nav",
            "dm" to -1,
            "ar" to true,
            "a" to intArrayOf(1, -2, Int.MIN_VALUE),
            "p" to listOf(1297000, 7759000),
            "x" to null,
            "big" to Long.MAX_VALUE,
        )
        assertEquals(
            """{"t":"nav","dm":-1,"ar":true,"a":[1,-2,-2147483648],"p":[1297000,7759000],"x":null,"big":9223372036854775807}""",
            Json.write(reply),
        )
        assertEquals("{}", Json.write(emptyMap<String, Any>()))
        assertEquals("[]", Json.write(emptyList<Int>()))
    }

    @Test
    fun stringsComeOutAsAscii() {
        val street = "Turn right onto \"MG Rd\" \\ ಬೆಂಗಳೂರು\n10:01\u0007 Café 🙂 </script>"
        val text = Json.write(mapOf("i" to street))
        assertTrue(text, text.all { it.code in 0x20..0x7e })
        assertEquals(street, JSONObject(text).getString("i"))
        assertEquals("\"\\u00e9\\ud83d\\ude42\"", Json.write("\u00e9\ud83d\ude42"))
    }
}
