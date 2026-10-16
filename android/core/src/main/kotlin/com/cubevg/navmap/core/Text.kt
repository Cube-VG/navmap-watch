package com.cubevg.navmap.core

/** Helpers for text copied out of other apps' notifications. */
object Text {
    private val invisible = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val spaces = Regex("[\\s\\u00A0\\u2007\\u2009\\u200A\\u202F\\u3000]+")
    private val separator = Regex("\\s+[·•]\\s+")

    /** Drops bidi marks, turns non-breaking and thin spaces into plain ones, collapses runs, trims. */
    fun clean(s: CharSequence?): String =
        if (s == null) "" else spaces.replace(invisible.replace(s, ""), " ").trim()

    /**
     * Splits "350 m · Turn left onto Elm St" on the middle dot Google Maps puts between fields.
     * The dot must have spaces around it, so names like "Paral·lel" stay whole.
     */
    fun fields(s: String): List<String> = s.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
}
