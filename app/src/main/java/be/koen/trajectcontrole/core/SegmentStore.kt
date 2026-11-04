package be.koen.trajectcontrole.core

import java.io.BufferedReader
import java.io.Reader

/**
 * Leest segments.tsv (gegenereerd door export_tsv.py uit OpenStreetMap-data).
 * Kolommen: id \t naam \t limiet \t tweerichting(0/1) \t volgende ids (komma) \t lat,lon;lat,lon;...
 * Tweerichtingstrajecten worden ook omgekeerd toegevoegd, met negatief id.
 * Een eigen correctiebestand in hetzelfde formaat kan erna geladen worden: zelfde id = vervangen,
 * limiet 0 = traject uitschakelen.
 */
object SegmentStore {

    fun parse(reader: Reader): List<Segment> {
        val out = LinkedHashMap<Long, Segment>()
        merge(out, reader)
        return out.values.toList()
    }

    fun parse(base: Reader, overrides: Reader?): List<Segment> {
        val out = LinkedHashMap<Long, Segment>()
        merge(out, base)
        if (overrides != null) merge(out, overrides)
        return out.values.filter { it.maxSpeed > 0 }
    }

    private fun merge(out: MutableMap<Long, Segment>, reader: Reader) {
        BufferedReader(reader).useLines { lines ->
            for (raw in lines) {
                val line = raw.trimEnd()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 6) continue
                val id = c[0].trim().toLongOrNull() ?: continue
                val name = c[1].trim()
                val limit = c[2].trim().toIntOrNull() ?: continue
                val bidirectional = c[3].trim() == "1"
                val next = c[4].split(',').mapNotNull { it.trim().toLongOrNull() }
                val pts = c[5].split(';').mapNotNull { p ->
                    val ll = p.split(',')
                    if (ll.size != 2) null else {
                        val la = ll[0].toDoubleOrNull(); val lo = ll[1].toDoubleOrNull()
                        if (la == null || lo == null) null else doubleArrayOf(la, lo)
                    }
                }
                out.remove(-id)
                if (limit <= 0 || pts.size < 2) { out.remove(id); continue }
                out[id] = Segment(id, name, limit, Polyline(pts), next)
                if (bidirectional) {
                    out[-id] = Segment(-id, "$name (omgekeerd)", limit, Polyline(pts.reversed()), emptyList())
                }
            }
        }
    }
}
