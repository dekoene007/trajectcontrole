package be.koen.trajectcontrole.core

import kotlin.math.hypot

/** Hulpmiddelen om uit een gereden GPS-spoor een trajectlijn te maken of een traject te corrigeren. */
object Track {

    /** Afstand in meter tussen twee punten. */
    fun dist(a: DoubleArray, b: DoubleArray): Double {
        val xy = toXY(b[0], b[1], a[0], a[1]); return hypot(xy[0], xy[1])
    }

    fun length(pts: List<DoubleArray>): Double = (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]) }

    /** Douglas-Peucker: vereenvoudigt een lijn tot een afwijking van hoogstens [tolM] meter. */
    fun simplify(pts: List<DoubleArray>, tolM: Double = 5.0): List<DoubleArray> {
        if (pts.size <= 2) return pts
        val ref = pts[0]
        val xy = pts.map { toXY(it[0], it[1], ref[0], ref[1]) }
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            if (j <= i + 1) continue
            val ax = xy[i][0]; val ay = xy[i][1]; val dx = xy[j][0] - ax; val dy = xy[j][1] - ay
            val l2 = dx * dx + dy * dy
            var best = -1.0; var bi = -1
            for (k in i + 1 until j) {
                val d = if (l2 == 0.0) hypot(xy[k][0] - ax, xy[k][1] - ay) else {
                    val t = (((xy[k][0] - ax) * dx + (xy[k][1] - ay) * dy) / l2).coerceIn(0.0, 1.0)
                    hypot(xy[k][0] - (ax + t * dx), xy[k][1] - (ay + t * dy))
                }
                if (d > best) { best = d; bi = k }
            }
            if (best > tolM) { keep[bi] = true; stack.addLast(i to bi); stack.addLast(bi to j) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** Positie op tijdstip t (lineair tussen de omliggende fixes); null als t buiten het spoor valt. */
    fun positionAt(fixes: List<Fix>, t: Double): DoubleArray? {
        if (fixes.isEmpty() || t < fixes.first().t || t > fixes.last().t) return null
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1]; val b = fixes[i]
            if (t <= b.t) {
                val f = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 0.0
                return doubleArrayOf(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
            }
        }
        return doubleArrayOf(fixes.last().lat, fixes.last().lon)
    }

    /** Lijn tussen twee tijdstippen uit het spoor, begin- en eindpunt geïnterpoleerd. */
    fun between(fixes: List<Fix>, t0: Double, t1: Double, maxAcc: Double = 30.0): List<DoubleArray> {
        val a = positionAt(fixes, t0) ?: return emptyList()
        val b = positionAt(fixes, t1) ?: return emptyList()
        val out = ArrayList<DoubleArray>()
        out.add(a)
        for (f in fixes) if (f.t > t0 && f.t < t1 && f.accuracy <= maxAcc) out.add(doubleArrayOf(f.lat, f.lon))
        out.add(b)
        return out
    }

    /** Eerste tijdstip (na tFrom) waarop het spoor voortgang [progress] op de lijn bereikt; null = nog niet. */
    fun timeAtProgress(fixes: List<Fix>, poly: Polyline, progress: Double, tFrom: Double, maxDist: Double = 60.0): Double? {
        var prev: Pair<Double, Double>? = null
        for (f in fixes) {
            if (f.t < tFrom) continue
            val pr = poly.project(f.lat, f.lon)
            if (pr.distance > maxDist) { prev = null; continue }
            val cur = f.t to pr.progress
            if (prev != null && prev.second < progress && cur.second >= progress) {
                val r = (progress - prev.second) / (cur.second - prev.second)
                return prev.first + r * (cur.first - prev.first)
            }
            if (prev == null && cur.second >= progress) return f.t
            prev = cur
        }
        return null
    }
}

/** Waar ligt een markering ten opzichte van een bestaand traject: begin of einde, en hoeveel ervoor/erna. */
data class Suggestion(val segment: Segment, val atStart: Boolean, val deltaM: Double) {
    /** Tekst zoals "begin 1,4 km vroeger" (delta < 0 = stroomopwaarts). */
    fun describe(): String {
        val d = Math.abs(deltaM)
        val amount = if (d >= 1000) "%.1f km".format(java.util.Locale("nl", "BE"), d / 1000) else "${d.toInt()} m"
        val dir = if (deltaM < 0) "vroeger" else "later"
        return "${if (atStart) "begin" else "einde"} $amount $dir"
    }
}

object Corrections {
    const val SEARCH_M = 2000.0         // tot hoe ver van een bestaand begin/einde een correctie voorgesteld wordt
    const val MAX_SIDE_M = 40.0         // max. zijdelingse afstand tot (het verlengde van) de lijn
    const val MAX_HEADING = 45.0

    /** Dichtstbijzijnde begin of einde van een traject in dezelfde rijrichting. */
    fun suggest(segs: Collection<Segment>, lat: Double, lon: Double, heading: Double?): Suggestion? {
        var best: Suggestion? = null
        for (s in segs) {
            if (!s.poly.nearBox(lat, lon, 0.03)) continue
            val pr = s.poly.project(lat, lon)
            if (pr.distance > MAX_SIDE_M) continue
            if (heading != null && angDiff(heading, pr.heading) > MAX_HEADING) continue
            val len = s.poly.length
            for (atStart in listOf(true, false)) {
                val delta = if (atStart) pr.progress else pr.progress - len
                if (Math.abs(delta) > SEARCH_M) continue
                // een begin-correctie mag niet voorbij het einde liggen en omgekeerd
                if (atStart && pr.progress >= len) continue
                if (!atStart && pr.progress <= 0) continue
                if (best == null || Math.abs(delta) < Math.abs(best.deltaM)) best = Suggestion(s, atStart, delta)
            }
        }
        return best
    }

    /**
     * Nieuwe lijn van het traject na correctie.
     * - begin later / einde vroeger: de bestaande lijn inkorten;
     * - begin vroeger / einde later: het gereden spoor tussen de markering en het oude begin/einde toevoegen.
     * Geeft null terug als het benodigde stuk spoor (nog) ontbreekt.
     */
    fun apply(seg: Segment, atStart: Boolean, markLat: Double, markLon: Double, markT: Double, fixes: List<Fix>): List<DoubleArray>? {
        val poly = seg.poly; val len = poly.length
        val p = poly.project(markLat, markLon).progress
        val mark = doubleArrayOf(markLat, markLon)
        return if (atStart) {
            if (p >= 0) listOf(mark) + poly.slice(p, len).drop(1)
            else {
                val tOld = Track.timeAtProgress(fixes, poly, 0.0, markT) ?: return null
                Track.between(fixes, markT, tOld) + poly.points.drop(1)
            }
        } else {
            if (p <= len) poly.slice(0.0, p).dropLast(1) + listOf(mark)
            else {
                val tOld = Track.timeAtProgress(fixes.filter { it.t <= markT }, poly, len, 0.0)
                    ?: return null
                poly.points.dropLast(1) + Track.between(fixes, tOld, markT)
            }
        }
    }
}
