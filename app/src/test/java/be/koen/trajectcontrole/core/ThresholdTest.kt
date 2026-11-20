package be.koen.trajectcontrole.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Max. snelheid voor de rest om net onder de boetedrempel (120 → ±128) te eindigen. */
class ThresholdTest {
    private val m2lat = 1.0 / 111195.0
    private fun pt(km: Double) = doubleArrayOf(51.0 + km * 1000 * m2lat, 4.0)
    private fun line(a: Double, b: Double) = Polyline((0..20).map { pt(a + (b - a) * it / 20.0) })

    /** Rijdt 'fastKm' aan 140 vanaf het begin van een 10 km-traject en geeft de status op dat punt. */
    private fun statusAfter(fastKm: Double): Status {
        val eng = Engine(listOf(Segment(1, "test 120", 120, line(0.0, 10.0)))) {}
        var km = -1.0; var t = 0.0; var st: Status? = null
        while (km < fastKm) {
            val v = (if (km < 0) 120.0 else 140.0) / 3.6
            st = eng.onFix(Fix(t, pt(km)[0], pt(km)[1], v, 5.0, 0.0)) ?: st
            km += v / 1000; t += 1.0
        }
        return st!!
    }

    @Test fun tweeKmTeSnel() {
        val s = statusAfter(2.0)
        assertEquals(124.9, s.maxRestThrKmh, 2.0)   // onder de boetegrens blijven: ±125
        assertEquals(115.8, s.maxRestKmh, 2.0)      // exact op de limiet eindigen: ±116
    }

    @Test fun vijfKmTeSnel() {
        val s = statusAfter(5.0)
        assertEquals(117.3, s.maxRestThrKmh, 2.0)
        assertEquals(105.0, s.maxRestKmh, 2.0)
    }
}
