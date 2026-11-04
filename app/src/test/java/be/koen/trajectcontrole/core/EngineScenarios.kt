package be.koen.trajectcontrole.core

import java.io.File
import java.io.Reader

/**
 * Speelt de gesimuleerde ritten (gegenereerd door make_fixtures.py) af door de Kotlin-rekenmotor
 * en vergelijkt met de echte waarden uit de simulatie.
 * Criteria: gemiddelde binnen 1,0 km/u en instaptijd binnen 1,5 s, per traject.
 */
object EngineScenarios {

    data class Truth(val segId: Long, val tEntry: Double, val avg: Double)
    data class Outcome(val name: String, val ok: Boolean, val lines: List<String>)

    fun run(name: String, segments: List<Segment>, fixture: Reader): Outcome {
        val truths = ArrayList<Truth>(); val fixes = ArrayList<Fix>()
        fixture.buffered().useLines { lines ->
            for (l in lines) {
                val c = l.split('\t')
                if (c[0] == "#truth") truths.add(Truth(c[1].toLong(), c[2].toDouble(), c[3].toDouble()))
                else if (c.size >= 5) fixes.add(Fix(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble()))
            }
        }
        val events = ArrayList<EngineEvent>()
        val engine = Engine(segments) { events.add(it) }
        fixes.forEach { engine.onFix(it) }
        var ok = true; val out = ArrayList<String>()
        // naderingsmelding: moet vallen voor het eerste traject (de rit start 400 m ervoor), vóór de start
        val first = truths.first().segId
        val ia = events.indexOfFirst { it is EngineEvent.Approach && it.segment.id == first }
        val isStart = events.indexOfFirst { it is EngineEvent.Start && it.segment.id == first }
        val apOk = ia >= 0 && isStart >= 0 && ia < isStart
        ok = ok && apOk
        out.add("naderingsmelding traject $first: " + if (apOk) "OK op %.0f m".format((events[ia] as EngineEvent.Approach).distanceM) else "FOUT (niet of te laat)")
        val spurious = events.filterIsInstance<EngineEvent.Approach>().filter { a -> truths.none { it.segId == a.segment.id } }
        if (spurious.isNotEmpty()) out.add("let op: naderingsmelding voor ander traject: " + spurious.joinToString { it.segment.id.toString() })
        for (t in truths) {
            val r = engine.results.firstOrNull { it.segment.id == t.segId }
            if (r == null) { ok = false; out.add("traject ${t.segId}: NIET GEMETEN"); continue }
            val da = r.avgKmh - t.avg; val de = r.tEntry - t.tEntry
            val good = Math.abs(da) < 1.0 && Math.abs(de) < 1.5
            ok = ok && good
            out.add("traject ${t.segId}: echt %.1f, app %.1f (fout %+.2f km/u), instaptijd fout %+.2f s -> %s"
                .format(t.avg, r.avgKmh, da, de, if (good) "OK" else "FOUT"))
        }
        return Outcome(name, ok, out)
    }

    fun runAll(segmentsTsv: Reader, scenarioDir: File): List<Outcome> {
        val segs = SegmentStore.parse(segmentsTsv)
        return scenarioDir.listFiles { f -> f.name.endsWith(".fix") }!!.sortedBy { it.name }
            .map { run(it.nameWithoutExtension, segs, it.reader()) }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val res = runAll(File(args[0]).reader(), File(args[1]))
        res.forEach { o -> println("== ${o.name}"); o.lines.forEach { println("   $it") } }
        println("drempels: " + listOf(30, 50, 70, 90, 120).joinToString { "$it -> %.1f".format(fineThreshold(it)) })
        val n = res.count { it.ok }
        println(if (n == res.size) "GESLAAGD ($n/${res.size})" else "NIET GESLAAGD ($n/${res.size})")
        if (n != res.size) System.exit(1)
    }
}
