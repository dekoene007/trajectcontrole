package be.koen.trajectcontrole.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineTest {

    private fun res(path: String): File = File(javaClass.classLoader!!.getResource(path)!!.toURI())

    @Test
    fun gesimuleerdeRitten() {
        val out = EngineScenarios.runAll(res("segments.tsv").reader(), res("scenarios"))
        out.forEach { o -> println("== ${o.name}"); o.lines.forEach { println("   $it") } }
        assertTrue(out.filterNot { it.ok }.joinToString { it.name }, out.all { it.ok })
    }

    @Test
    fun boetedrempels() {
        assertEquals(56.0, fineThreshold(50), 0.01)
        assertEquals(76.0, fineThreshold(70), 0.01)
        assertEquals(127.66, fineThreshold(120), 0.01)
    }

    @Test
    fun regressieTijdstip() {
        // voortgang 10 m/s, start op t=5 -> voortgang 0 op t=5
        val s = (5..12).map { it.toDouble() to (it - 5) * 10.0 }
        assertEquals(5.0, fitTimeAt(s, 0.0)!!, 1e-9)
        assertEquals(null, fitTimeAt(listOf(1.0 to 3.0, 2.0 to 3.0), 0.0))   // stilstand
    }
}
