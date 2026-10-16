package com.cubevg.navmap.core

/**
 * Writes NavBridge's HTTP replies to the watch: maps, lists, int arrays, strings, numbers and booleans.
 * Everything outside printable ASCII is escaped, so the reply reads the same whatever charset Garmin Connect assumes.
 */
object Json {
    private const val HEX = "0123456789abcdef"

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean, is Int, is Long, is Short, is Byte -> out.append(value)
            is Number -> {
                val d = value.toDouble()
                require(d.isFinite()) { "JSON has no $d" }
                out.append(d)
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is IntArray -> append(out, value.asList())
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("No JSON for ${value.javaClass.name}")
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                in ' '..'~' -> out.append(c)
                else -> {
                    val code = c.code
                    out.append("\\u")
                    for (shift in intArrayOf(12, 8, 4, 0)) out.append(HEX[(code shr shift) and 15])
                }
            }
        }
        out.append('"')
    }
}
