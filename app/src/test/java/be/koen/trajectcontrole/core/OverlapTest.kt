package be.koen.trajectcontrole.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Overlappende trajecten: een 90-zone van km 3 tot 6 binnen een 120-traject van km 0 tot 10. */
class OverlapTest {

    private val m2lat = 1.0 / 111195.0
    private fun pt(km: Double) = doubleArrayOf(51.0 + km * 1000 * m2lat, 4.0)
    private fun line(a: Double, b: Double) = Polyline((0..20).map { pt(a + (b - a) * it / 20.0) })

    private fun speedAt(km: Double) = when { km < 3.0 -> 130.0; km < 6.0 -> 100.0; else -> 110.0 }

    @Test
    fun strengsteWordtGetoondEnBeideGemeten() {
        val outer = Segment(1, "buiten 120", 120, line(0.0, 10.0))
        val inner = Segment(2, "binnen 90", 90, line(3.0, 6.0))
        val events = ArrayList<EngineEvent>()
        val eng = Engine(listOf(outer, inner)) { events.add(it) }
        var km = -1.0; var t = 0.0
        val seen = HashMap<Double, Status?>()
        while (km < 11.0) {
            val v = speedAt(km) / 3.6
            val st = eng.onFix(Fix(t, pt(km)[0], pt(km)[1], v, 5.0, 0.0))
            for (probe in listOf(1.5, 4.5, 8.0)) if (km < probe && km + v / 1000 >= probe) seen[probe] = st
            km += v / 1000; t += 1.0
        }
        // km 1,5: enkel het 120-traject
        assertEquals(1L, seen[1.5]!!.segmentId); assertEquals(0, seen[1.5]!!.parallel)
        // km 4,5: beide actief, de 90-zone is strenger (max. rest ~82 tegen ~121)
        val mid = seen[4.5]; assertNotNull(mid)
        assertEquals(2L, mid!!.segmentId); assertEquals(1, mid.parallel)
        assertTrue("max rest ${mid.maxRestKmh}", mid.maxRestKmh in 78.0..86.0)
        // km 8: 90-zone afgesloten, terug naar het 120-traject
        assertEquals(1L, seen[8.0]!!.segmentId); assertEquals(0, seen[8.0]!!.parallel)
        // beide trajecten correct gemeten
        val rIn = eng.results.first { it.segment.id == 2L }
        val rOut = eng.results.first { it.segment.id == 1L }
        assertEquals(100.0, rIn.avgKmh, 1.0)
        val hours = 3 / 130.0 + 3 / 100.0 + 4 / 110.0
        assertEquals(10 / hours, rOut.avgKmh, 1.0)
        // een strenger traject verderop wordt ook binnen een lopend traject aangekondigd
        assertTrue(events.any { it is EngineEvent.Approach && it.segment.id == 2L })
    }
}
