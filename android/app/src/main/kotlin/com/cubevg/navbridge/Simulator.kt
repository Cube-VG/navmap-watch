package com.cubevg.navbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.cubevg.navmap.core.ArrowEncoder
import com.cubevg.navmap.core.LatLng
import com.cubevg.navmap.core.MapsNotificationParser
import com.cubevg.navmap.core.RawNotification
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

/**
 * A made-up 1.5 km ride for testing at home. It goes through the same steps as real navigation: the text is
 * shaped like Google Maps' notification and read by the same parser, and the watch gets the prompt, turns and a
 * route line starting where the watch is.
 */
object Simulator {
    private const val SPEED = 12.0 // m/s, about 43 km/h
    private const val WAIT_FOR_WATCH_S = 40

    private enum class Turn { STRAIGHT, RIGHT, LEFT, ARRIVE }

    /** A maneuver at the end of a straight stretch heading [bearing] degrees. */
    private data class Step(val instruction: String, val turn: Turn, val meters: Int, val bearing: Double)

    private val steps = listOf(
        Step("Turn right onto Test Street", Turn.RIGHT, 300, 0.0),
        Step("Turn left onto Sample Avenue", Turn.LEFT, 450, 90.0),
        Step("Turn right onto Example Lane", Turn.RIGHT, 400, 0.0),
        Step("Demo Café will be on the right", Turn.ARRIVE, 350, 90.0),
    )

    private val main = Handler(Looper.getMainLooper())
    var running = false
        private set
    private var step = 0
    private var left = 0.0
    private var waited = 0
    private var arrivedFor = 0
    private var routeSent = false

    fun start() {
        if (running) stop()
        running = true
        step = -1
        waited = 0
        arrivedFor = 0
        routeSent = false
        DebugLog.log("Simulation started")
        post("Starting navigation…", null, Turn.STRAIGHT)
        main.postDelayed(loop, 1_000)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(loop)
        DebugLog.log("Simulation stopped")
        NavSession.onNavEnded("the simulation ended", mapsStopped = true)
    }

    fun onWatchReady() {
        if (running && !routeSent) sendRoute()
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            when {
                step == -1 -> {
                    // Like Maps before the first turn: no distance yet. Wait here until NavMap is open.
                    post("Head north on Demo Road", eta(), Turn.STRAIGHT)
                    if (NavSession.watchShowing || ++waited > WAIT_FOR_WATCH_S) {
                        step = 0
                        left = steps[0].meters.toDouble()
                    }
                }
                step < steps.size -> {
                    left -= SPEED
                    if (left <= 0) {
                        step++
                        if (step < steps.size) left = steps[step].meters.toDouble()
                    }
                    if (step < steps.size) {
                        val s = steps[step]
                        post("${distance(left)} · ${s.instruction}", eta(), s.turn)
                    } else {
                        post("Arriving", "Arrive now", Turn.ARRIVE, text = "at Demo Café")
                    }
                    if (!routeSent) sendRoute()
                }
                else -> {
                    // A few seconds on "Arriving", then the notification goes away like in Maps.
                    if (++arrivedFor >= 8) {
                        running = false
                        DebugLog.log("Simulation finished")
                        NavSession.onNavEnded("the simulation ended", mapsStopped = true)
                        return
                    }
                }
            }
            main.postDelayed(this, 1_000)
        }
    }

    private fun post(title: String, sub: String?, turn: Turn, text: String? = null) {
        val raw = RawNotification(MapsNotificationParser.MAPS_PACKAGE, title, text, sub, MapsNotificationParser.CATEGORY_NAVIGATION, true)
        val info = MapsNotificationParser.parse(raw) ?: return
        NavSession.onNav(info, arrows[turn])
    }

    private fun remaining(): Double = left + steps.drop(step + 1).sumOf { it.meters }

    private fun eta(): String {
        val at = Date(System.currentTimeMillis() + (remaining() / SPEED * 1000).toLong())
        return "Arrive " + DateFormat.getTimeInstance(DateFormat.SHORT).format(at)
    }

    // Rounded the way Google Maps rounds: 10 m steps below 1 km.
    private fun distance(m: Double): String =
        if (m >= 1000) String.format(Locale.getDefault(), "%.1f km", m / 1000) else "${maxOf(0, (m / 10).toInt() * 10)} m"

    private fun sendRoute() {
        val start = NavSession.watchPosition ?: return
        val points = mutableListOf(start)
        for (s in steps) {
            val last = points.last()
            val dLat = if (s.bearing == 0.0) s.meters / 111_320.0 else 0.0
            val dLng = if (s.bearing == 90.0) s.meters / (111_320.0 * cos(Math.toRadians(last.lat))) else 0.0
            points += LatLng(last.lat + dLat, last.lng + dLng)
        }
        routeSent = true
        NavSession.setRoute(points)
    }

    // Simple white arrows on transparency, like Google's maneuver icons.
    private val arrows: Map<Turn, IntArray?> by lazy { Turn.entries.associateWith(::drawArrow) }

    private fun drawArrow(turn: Turn): IntArray? {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 14f
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        when (turn) {
            Turn.STRAIGHT -> {
                canvas.drawLine(48f, 90f, 48f, 34f, stroke)
                canvas.drawPath(Path().apply { moveTo(48f, 6f); lineTo(72f, 38f); lineTo(24f, 38f); close() }, fill)
            }
            Turn.RIGHT, Turn.LEFT -> {
                if (turn == Turn.LEFT) canvas.scale(-1f, 1f, 48f, 48f)
                canvas.drawPath(Path().apply { moveTo(30f, 92f); lineTo(30f, 46f); lineTo(62f, 46f) }, stroke)
                canvas.drawPath(Path().apply { moveTo(90f, 46f); lineTo(60f, 22f); lineTo(60f, 70f); close() }, fill)
            }
            Turn.ARRIVE -> {
                canvas.drawCircle(48f, 36f, 26f, fill)
                canvas.drawPath(Path().apply { moveTo(26f, 50f); lineTo(70f, 50f); lineTo(48f, 92f); close() }, fill)
            }
        }
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        return ArrowEncoder.encode(pixels, size, size)
    }
}
