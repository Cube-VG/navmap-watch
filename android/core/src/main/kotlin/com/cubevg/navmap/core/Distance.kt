package com.cubevg.navmap.core

import kotlin.math.roundToInt

/** A distance as Google Maps printed it ("350 m", "0.3 mi"), and the same distance in metres. */
data class Distance(val text: String, val meters: Int)

object Distances {
    private val units: Map<String, Double> = buildMap {
        listOf("m", "meter", "meters", "metre", "metres", "metri", "metros", "mètres", "公尺", "米", "м", "मी", "मीटर")
            .forEach { put(it, 1.0) }
        listOf("km", "kilometer", "kilometers", "kilometre", "kilometres", "公里", "千米", "км", "किमी", "कि.मी")
            .forEach { put(it, 1000.0) }
        listOf("ft", "feet", "foot", "pi", "pés").forEach { put(it, 0.3048) }
        listOf("yd", "yard", "yards").forEach { put(it, 0.9144) }
        listOf("mi", "mile", "miles").forEach { put(it, 1609.344) }
    }

    // "1,000 ft" uses a thousands separator; "1,2 km" a decimal comma.
    private val thousands = Regex("\\d{1,3}(,\\d{3})+")
    private val pattern = Regex("(\\d{1,3}(?:,\\d{3})+|\\d+(?:[.,]\\d+)?) ?(\\D.*)")

    /** Parses text that is only a distance, such as "350 m" or "1,2 km". Anything else gives null. */
    fun parse(s: String?): Distance? {
        val text = Text.clean(s)
        val m = pattern.matchEntire(text) ?: return null
        val unit = m.groupValues[2].trim().lowercase().trimEnd('.', '॰')
        val factor = units[unit] ?: return null
        val raw = m.groupValues[1]
        val number = (if (thousands.matches(raw)) raw.replace(",", "") else raw.replace(',', '.'))
            .toDoubleOrNull() ?: return null
        return Distance(text, (number * factor).roundToInt())
    }
}
