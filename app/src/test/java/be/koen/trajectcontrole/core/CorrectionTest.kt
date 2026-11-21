package be.koen.trajectcontrole.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Correcties en nieuwe trajecten uit een gereden spoor (rechte weg noordwaarts, 1 km = 1/111,195 graad). */
class CorrectionTest {
    private val m2lat = 1.0 / 111195.0
    private fun pt(km: Double) = doubleArrayOf(51.0 + km * 1000 * m2lat, 4.0)
    private fun seg(a: Double, b: Double) = Segment(7, "test", 70, Polyline((0..10).map { pt(a + (b - a) * it / 10.0) }))
    /** Spoor van km -2 tot km 8 aan 20 m/s, 1 fix per seconde. */
    private val fixes = (0..500).map { i -> val km = -2.0 + i * 0.02; Fix(i.toDouble(), pt(km)[0], pt(km)[1], 20.0, 5.0, 0.0) }
    private fun tAt(km: Double) = (km + 2.0) / 0.02

    @Test fun suggestiePerKant() {
        val s = seg(1.0, 5.0)
        val a = Corrections.suggest(listOf(s), pt(0.6)[0], pt(0.6)[1], 0.0)!!
        assertTrue(a.atStart); assertEquals(-400.0, a.deltaM, 2.0)
        val b = Corrections.suggest(listOf(s), pt(5.3)[0], pt(5.3)[1], 0.0)!!
        assertTrue(!b.atStart); assertEquals(300.0, b.deltaM, 2.0)
        assertNull(Corrections.suggest(listOf(s), pt(0.6)[0], pt(0.6)[1], 180.0))   // andere rijrichting
    }

    @Test fun beginLaterKortIn() {
        val s = seg(1.0, 5.0)
        val pts = Corrections.apply(s, true, pt(1.5)[0], pt(1.5)[1], tAt(1.5), fixes)!!
        assertEquals(3500.0, Track.length(pts), 5.0)
    }

    @Test fun beginVroegerVoegtSpoorToe() {
        val s = seg(1.0, 5.0)
        val pts = Corrections.apply(s, true, pt(0.2)[0], pt(0.2)[1], tAt(0.2), fixes)!!
        assertEquals(4800.0, Track.length(pts), 10.0)
        // spoor na de markering nog niet beschikbaar -> null
        assertNull(Corrections.apply(s, true, pt(0.2)[0], pt(0.2)[1], tAt(0.2), fixes.filter { it.t < tAt(0.5) }))
    }

    @Test fun eindeLaterEnVroeger() {
        val s = seg(1.0, 5.0)
        assertEquals(4600.0, Track.length(Corrections.apply(s, false, pt(5.6)[0], pt(5.6)[1], tAt(5.6), fixes)!!), 10.0)
        assertEquals(3000.0, Track.length(Corrections.apply(s, false, pt(4.0)[0], pt(4.0)[1], tAt(4.0), fixes)!!), 5.0)
    }

    @Test fun nieuwTrajectUitSpoor() {
        val pts = Track.simplify(Track.between(fixes, tAt(2.0), tAt(3.9)))
        assertEquals(2, pts.size)                  // rechte lijn -> 2 punten
        assertEquals(1900.0, Track.length(pts), 5.0)
        val poly = Polyline(pts); assertNotNull(poly)
    }

    @Test fun limietOverschrijven() {
        val s = seg(0.0, 4.0)
        val eng = Engine(listOf(s))
        var st: Status? = null
        for (f in fixes.filter { it.t <= tAt(1.0) }) st = eng.onFix(f) ?: st
        assertEquals(70, st!!.limit)
        eng.setLimitOverride(7, 50)
        st = eng.status(tAt(1.0))
        assertEquals(50, st!!.limit); assertTrue(st.limitOverridden)
        assertEquals(56.0, st.threshold, 0.01)
    }
}
