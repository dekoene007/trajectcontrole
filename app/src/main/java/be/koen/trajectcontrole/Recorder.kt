package be.koen.trajectcontrole

import be.koen.trajectcontrole.core.Fix
import java.io.File
import java.util.Locale

/** Recente GPS-fixes in het geheugen (voor markeren met reactietijd) en het teruglezen van ritlogboeken. */
object FixHistory {
    private const val KEEP_S = 180.0
    private val buf = ArrayDeque<Fix>()

    @Synchronized fun add(f: Fix) {
        buf.addLast(f)
        while (buf.isNotEmpty() && f.t - buf.first().t > KEEP_S) buf.removeFirst()
    }
    @Synchronized fun snapshot(): List<Fix> = buf.toList()
    @Synchronized fun clear() = buf.clear()
}

object RideLogReader {
    /** Alle FIX-regels van een ritlogboek. */
    fun read(file: File): List<Fix> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Fix>()
        file.forEachLine { line ->
            if (!line.startsWith("FIX;")) return@forEachLine
            val c = line.split(';')
            if (c.size < 6) return@forEachLine
            val t = c[1].toDoubleOrNull() ?: return@forEachLine
            val lat = c[2].toDoubleOrNull() ?: return@forEachLine
            val lon = c[3].toDoubleOrNull() ?: return@forEachLine
            out.add(Fix(t, lat, lon, c[4].toDoubleOrNull() ?: 0.0, c[5].toDoubleOrNull() ?: 99.0, c.getOrNull(6)?.toDoubleOrNull()))
        }
        return out
    }
}

/** Logboek van afgewerkte passages: datum, traject, gemiddelde. files/passages.csv */
object PassageLog {
    data class Passage(val wallMs: Long, val segmentId: Long, val name: String, val limit: Int, val avgKmh: Double,
                       val lengthM: Double, val estimated: Boolean)

    private fun file(ctx: android.content.Context) = File(ctx.filesDir, "passages.csv")

    fun add(ctx: android.content.Context, p: Passage) {
        val clean = p.name.replace(';', ',')
        file(ctx).appendText("%d;%d;%s;%d;%.1f;%.0f;%d\n".format(Locale.US, p.wallMs, p.segmentId, clean, p.limit, p.avgKmh, p.lengthM, if (p.estimated) 1 else 0))
    }

    fun all(ctx: android.content.Context): List<Passage> {
        val f = file(ctx); if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            val c = l.split(';'); if (c.size < 7) null else try {
                Passage(c[0].toLong(), c[1].toLong(), c[2], c[3].toInt(), c[4].toDouble(), c[5].toDouble(), c[6] == "1")
            } catch (_: Exception) { null }
        }.reversed()
    }
}
