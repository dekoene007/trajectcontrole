package be.koen.trajectcontrole.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

const val R_EARTH = 6371000.0

/** Lokale vlakke projectie (meter) rond een referentiepunt; ruim nauwkeurig genoeg voor trajecten < 30 km. */
fun toXY(lat: Double, lon: Double, refLat: Double, refLon: Double): DoubleArray {
    val k = cos(Math.toRadians(refLat))
    return doubleArrayOf(
        (lon - refLon) * PI / 180.0 * R_EARTH * k,
        (lat - refLat) * PI / 180.0 * R_EARTH
    )
}

fun angDiff(a: Double, b: Double): Double {
    val d = Math.abs(a - b) % 360.0
    return min(d, 360.0 - d)
}

fun headingDeg(dx: Double, dy: Double): Double = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0

/** Resultaat van een projectie op de polylijn. progress mag < 0 of > lengte zijn (eerste/laatste stuk verlengd). */
data class Projection(val distance: Double, val progress: Double, val heading: Double)

class Polyline(latLons: List<DoubleArray>) {
    private val refLat = latLons[0][0]
    private val refLon = latLons[0][1]
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val cum: DoubleArray
    val length: Double

    // bounding box (graden) voor snelle voorselectie
    val minLat: Double; val maxLat: Double; val minLon: Double; val maxLon: Double

    init {
        require(latLons.size >= 2) { "polylijn heeft minstens 2 punten nodig" }
        xs = DoubleArray(latLons.size); ys = DoubleArray(latLons.size); cum = DoubleArray(latLons.size)
        for (i in latLons.indices) {
            val p = toXY(latLons[i][0], latLons[i][1], refLat, refLon)
            xs[i] = p[0]; ys[i] = p[1]
            if (i > 0) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        length = cum[latLons.size - 1]
        minLat = latLons.minOf { it[0] }; maxLat = latLons.maxOf { it[0] }
        minLon = latLons.minOf { it[1] }; maxLon = latLons.maxOf { it[1] }
    }

    fun nearBox(lat: Double, lon: Double, marginDeg: Double = 0.003): Boolean =
        lat >= minLat - marginDeg && lat <= maxLat + marginDeg && lon >= minLon - marginDeg && lon <= maxLon + marginDeg

    fun project(lat: Double, lon: Double): Projection {
        val p = toXY(lat, lon, refLat, refLon)
        val x = p[0]; val y = p[1]
        var best: Projection? = null
        val n = xs.size - 1
        for (i in 0 until n) {
            val ax = xs[i]; val ay = ys[i]; val dx = xs[i + 1] - ax; val dy = ys[i + 1] - ay
            val l2 = dx * dx + dy * dy
            if (l2 == 0.0) continue
            val t = ((x - ax) * dx + (y - ay) * dy) / l2
            val lo = if (i == 0) Double.NEGATIVE_INFINITY else 0.0
            val hi = if (i == n - 1) Double.POSITIVE_INFINITY else 1.0
            val tc = max(lo, min(hi, t))
            val d = hypot(x - (ax + tc * dx), y - (ay + tc * dy))
            if (best == null || d < best.distance) {
                best = Projection(d, cum[i] + tc * sqrt(l2), headingDeg(dx, dy))
            }
        }
        return best!!
    }
}
