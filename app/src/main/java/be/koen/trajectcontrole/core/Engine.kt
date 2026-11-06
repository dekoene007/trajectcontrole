package be.koen.trajectcontrole.core

/*
 * Rekenmotor voor gemiddelde snelheid in trajectcontroles.
 * Oorspronkelijk omgezet uit engine.py; overlappende trajecten (punt 4) bestaan alleen hier.
 *
 * Principes:
 *  1. Voortdurend map-matchen op de trajectlijn i.p.v. een start-trigger.
 *  2. Ringbuffer met GPS-historiek: wordt een traject laat herkend, dan wordt de
 *     echte instaptijd teruggezocht (regressie rond voortgang 0).
 *  3. Aaneensluitende trajecten worden expliciet gekoppeld: het volgende traject
 *     start exact op het uittijdstip van het vorige.
 *  4. Overlappende trajecten (bv. federaal binnen een Vlaams traject) worden tegelijk
 *     gevolgd; de status toont het strengste: de laagste toegelaten snelheid voor de rest.
 *
 * Pure Kotlin, geen Android-afhankelijkheden -> testbaar op de JVM.
 */

class Segment(
    val id: Long,              // negatief = omgekeerde richting van een tweerichtingstraject
    val name: String,
    val maxSpeed: Int,         // km/u
    val poly: Polyline,
    val nextIds: List<Long> = emptyList()
)

/** Eén GPS-meting. speed in m/s, accuracy in m, course in graden (null = onbekend). t in seconden. */
data class Fix(
    val t: Double, val lat: Double, val lon: Double,
    val speed: Double, val accuracy: Double, val course: Double? = null
)

/** Belgische technische correctie: < 100 km/u -> -6 km/u, vanaf 100 km/u -> -6 %.
 *  Geeft de gemeten snelheid terug waarboven de gecorrigeerde snelheid de limiet overschrijdt. */
fun fineThreshold(limit: Int): Double {
    val t = limit + 6.0
    return if (t < 100.0) t else limit / 0.94
}

enum class Level { GREEN, ORANGE, RED }

data class Status(
    val segmentId: Long, val name: String, val limit: Int, val threshold: Double,
    val avgKmh: Double,           // gemiddelde tot nu toe
    val maxRestKmh: Double,       // max. snelheid voor de rest om op de limiet uit te komen (0 = niet meer haalbaar)
    val remainingM: Double, val lengthM: Double,
    val estimated: Boolean,       // instaptijd geschat (historiek reikte niet tot het begin)
    val level: Level,
    val parallel: Int = 0         // aantal andere trajecten die tegelijk gevolgd worden
)

sealed class EngineEvent {
    data class Start(val segment: Segment, val tEntry: Double, val estimated: Boolean) : EngineEvent()
    data class End(val segment: Segment, val avgKmh: Double, val tEntry: Double, val tExit: Double, val estimated: Boolean) : EngineEvent()
    data class Left(val segment: Segment) : EngineEvent()
    /** Je rijdt in de juiste richting op het begin van een traject af (afstand in m tot het begin). */
    data class Approach(val segment: Segment, val distanceM: Double) : EngineEvent()
}

/** Lineaire regressie voortgang(t) -> tijdstip waarop voortgang == target. null bij (bijna) stilstand. */
internal fun fitTimeAt(samples: List<Pair<Double, Double>>, target: Double): Double? {
    val n = samples.size
    if (n < 2) return null
    val mt = samples.sumOf { it.first } / n
    val mp = samples.sumOf { it.second } / n
    val varT = samples.sumOf { (it.first - mt) * (it.first - mt) }
    if (varT == 0.0) return null
    val a = samples.sumOf { (it.first - mt) * (it.second - mp) } / varT
    if (a <= 0.5) return null
    return mt + (target - mp) / a
}

class Engine(segments: List<Segment>, private val onEvent: (EngineEvent) -> Unit = {}) {

    companion object {
        const val MATCH_DIST = 30.0     // m
        const val LEAVE_DIST = 60.0     // m
        const val LEAVE_TIME = 20.0     // s langer dan LEAVE_DIST naast de lijn -> traject verlaten
        const val BUFFER_S = 900.0      // 15 min historiek
        const val MAX_ACC = 50.0        // slechtere fixes negeren
        const val MAX_HEADING_DIFF = 45.0
        const val APPROACH_M = 450.0    // naderingsmelding vanaf ~400 m voor het begin
        const val APPROACH_DIST = 25.0  // m naast de (verlengde) trajectlijn
        const val APPROACH_REPEAT_S = 600.0
        const val SWITCH_MARGIN = 1.0   // km/u: getoond traject wisselt pas bij duidelijk strenger alternatief
    }

    private val segs: Map<Long, Segment> = segments.associateBy { it.id }
    private val buf = ArrayDeque<Fix>()

    private class Active(
        val seg: Segment, val tEntry: Double, var progress: Double, val estimated: Boolean,
        var offSince: Double? = null, var tLast: Double? = null,
        val hist: MutableList<Pair<Double, Double>> = mutableListOf()
    )

    private val active = LinkedHashMap<Long, Active>()
    private var shownId: Long? = null
    private val approached = HashMap<Long, Double>()   // segment-id -> tijdstip laatste naderingsmelding
    val results = mutableListOf<EngineEvent.End>()

    fun onFix(f: Fix): Status? {
        buf.addLast(f)
        while (buf.isNotEmpty() && f.t - buf.first().t > BUFFER_S) buf.removeFirst()
        if (f.accuracy > MAX_ACC) return status(f.t)
        for (a in active.values.toList()) updateActive(a, f)
        tryMatch(f)
        return status(f.t)
    }

    /** Ids van alle trajecten die nu gevolgd worden. */
    val activeIds: Set<Long> get() = active.keys.toSet()

    /** Status van het strengste actieve traject (laagste max. snelheid voor de rest), met hysterese. */
    fun status(t: Double): Status? {
        val all = active.values.mapNotNull { statusOf(it, t) }
        if (all.isEmpty()) { shownId = null; return null }
        fun key(s: Status) = s.maxRestKmh - (if (s.maxRestKmh <= 0.0) s.avgKmh - s.threshold else 0.0)
        val best = all.minWith(compareBy<Status> { key(it) }.thenBy { it.limit }.thenByDescending { it.remainingM })
        val cur = all.firstOrNull { it.segmentId == shownId }
        val pick = if (cur != null && key(cur) <= key(best) + SWITCH_MARGIN) cur else best
        shownId = pick.segmentId
        return pick.copy(parallel = all.size - 1)
    }

    private fun statusOf(a: Active, t: Double): Status? {
        val s = a.seg
        val len = s.poly.length
        val el = t - a.tEntry
        if (el <= 0) return null
        val avg = a.progress / el * 3.6
        val thr = fineThreshold(s.maxSpeed)
        val remTimeAtLimit = len / (s.maxSpeed / 3.6) - el
        val rest = len - a.progress
        val maxRest = if (remTimeAtLimit > 0) rest / remTimeAtLimit * 3.6 else 0.0
        val level = when {
            avg <= s.maxSpeed -> Level.GREEN
            avg <= thr -> Level.ORANGE
            else -> Level.RED
        }
        return Status(s.id, s.name, s.maxSpeed, thr, avg, maxRest, rest, len, a.estimated, level)
    }

    // ---------------- intern ----------------

    private fun heading(f: Fix): Double? {
        f.course?.let { return it }
        if (buf.size >= 2) {
            val p = buf[buf.size - 2]
            if (p.lat != f.lat || p.lon != f.lon) {
                val xy = toXY(f.lat, f.lon, p.lat, p.lon)
                return headingDeg(xy[0], xy[1])
            }
        }
        return null
    }

    private fun tryMatch(f: Fix) {
        val hd = heading(f) ?: return
        if (f.speed < 2.0) return   // zonder betrouwbare rijrichting nooit matchen (tegenoverliggende rijbaan!)
        var approach: Pair<Segment, Double>? = null
        for (s in segs.values) {
            if (s.id in active) continue
            if (!s.poly.nearBox(f.lat, f.lon, 0.006)) continue
            val pr = s.poly.project(f.lat, f.lon)
            if (angDiff(hd, pr.heading) > MAX_HEADING_DIFF) continue
            if (pr.progress < 0 && pr.progress >= -APPROACH_M && pr.distance <= APPROACH_DIST) {
                // naderen: verlengde eerste stuk van de trajectlijn, juiste richting
                if (approach == null || -pr.progress < approach.second) approach = s to -pr.progress
                continue
            }
            if (pr.distance > MATCH_DIST || pr.progress < 0 || pr.progress > s.poly.length) continue
            val (tEntry, est) = backfillEntry(s, f)
            activate(s, tEntry, pr.progress, est)
        }
        approach?.let { (s, d) ->
            // binnen een traject alleen een strenger traject verderop aankondigen; nooit een gekoppeld vervolg
            if (active.values.any { s.id in it.seg.nextIds }) return
            if (active.isNotEmpty() && active.values.all { it.seg.maxSpeed <= s.maxSpeed }) return
            val last = approached[s.id]
            if (d >= 30 && (last == null || f.t - last > APPROACH_REPEAT_S)) {
                approached[s.id] = f.t
                onEvent(EngineEvent.Approach(s, d))
            }
        }
    }

    /** Zoek in de historiek het moment waarop voortgang 0 werd gepasseerd. */
    private fun backfillEntry(s: Segment, f: Fix): Pair<Double, Boolean> {
        val hist = ArrayList<Pair<Double, Double>>()
        for (i in buf.indices.reversed()) {
            val g = buf[i]
            if (g.accuracy > MAX_ACC) continue
            val pr = s.poly.project(g.lat, g.lon)
            if (pr.distance > LEAVE_DIST) break
            hist.add(g.t to pr.progress)
            if (pr.progress < -60) break
        }
        hist.reverse()
        if (hist.isNotEmpty() && hist[0].second <= 0) {
            var near = hist.filter { it.second in -80.0..80.0 }.takeLast(8)
            if (near.isEmpty()) near = hist.takeLast(2)
            val t0 = fitTimeAt(near, 0.0)
            if (t0 != null) return t0 to false
        }
        val prog = s.poly.project(f.lat, f.lon).progress
        return (f.t - prog / maxOf(f.speed, 1.0)) to true
    }

    private fun activate(s: Segment, tEntry: Double, prog: Double, est: Boolean) {
        active[s.id] = Active(s, tEntry, maxOf(0.0, prog), est)
        onEvent(EngineEvent.Start(s, tEntry, est))
    }

    private fun updateActive(a: Active, f: Fix) {
        val s = a.seg
        val len = s.poly.length
        val pr = s.poly.project(f.lat, f.lon)
        if (pr.distance > LEAVE_DIST) {
            if (a.offSince == null) a.offSince = f.t
            if (f.t - a.offSince!! > LEAVE_TIME) {
                active.remove(s.id)
                onEvent(EngineEvent.Left(s))
            }
            return
        }
        a.offSince = null
        val prog = pr.progress
        a.hist.add(f.t to prog)
        if (prog >= len) {
            // eindpunt gepasseerd: uittijd via regressie op de laatste fixes rond het einde
            var tExit = fitTimeAt(a.hist.filter { it.second >= len - 80 }.takeLast(8), len)
            if (tExit == null || tExit > f.t || tExit < a.tEntry) {
                val pPrev = a.progress
                val tPrev = a.tLast ?: f.t
                val frac = if (prog > pPrev) (len - pPrev) / (prog - pPrev) else 1.0
                tExit = tPrev + frac * (f.t - tPrev)
            }
            val avg = len / (tExit - a.tEntry) * 3.6
            val end = EngineEvent.End(s, avg, a.tEntry, tExit, a.estimated)
            results.add(end)
            active.remove(s.id)
            onEvent(end)
            // koppeling: volgend traject start exact op tExit
            for (nid in s.nextIds) {
                if (nid in active) continue
                val n = segs[nid] ?: continue
                val pn = n.poly.project(f.lat, f.lon)
                if (pn.distance <= MATCH_DIST && pn.progress >= -50) {
                    activate(n, tExit, pn.progress, a.estimated)
                    active[n.id]!!.tLast = f.t
                    active[n.id]!!.hist.add(f.t to pn.progress)
                    return
                }
            }
            return
        }
        a.progress = maxOf(a.progress, prog)   // monotoon
        a.tLast = f.t
    }
}
