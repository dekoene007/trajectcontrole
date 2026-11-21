package be.koen.trajectcontrole.ui

import be.koen.trajectcontrole.MarkStore
import be.koen.trajectcontrole.core.Segment
import be.koen.trajectcontrole.core.Status
import java.util.Locale

/** GeoJSON voor de kaartlagen (gsm). Bewust als tekst opgebouwd: geen extra bibliotheek nodig. */
object MapData {
    const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
    const val ATTRIBUTION = "© OpenFreeMap · © OpenMapTiles · © OpenStreetMap"

    private fun c(p: DoubleArray) = String.format(Locale.US, "[%.6f,%.6f]", p[1], p[0])
    private fun line(pts: List<DoubleArray>) = """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${pts.joinToString(",") { c(it) }}]}}"""
    private fun point(p: DoubleArray, props: String = "") = """{"type":"Feature","properties":{$props},"geometry":{"type":"Point","coordinates":${c(p)}}}"""
    private fun fc(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    val EMPTY = fc(emptyList())

    /** Alle trajecten behalve het actieve (één richting volstaat: omgekeerde kopieën overslaan). */
    fun others(segs: Collection<Segment>, activeId: Long?): String =
        fc(segs.filter { it.id > 0 && it.id != activeId && it.id != -(activeId ?: 0) }.map { line(it.poly.points) })

    fun starts(segs: Collection<Segment>, activeId: Long?): String =
        fc(segs.filter { it.id > 0 && it.id != activeId }.map { point(it.poly.points.first()) })

    /** Actief traject: afgelegd en resterend deel. */
    fun activeDone(seg: Segment?, st: Status?): String =
        if (seg == null || st == null) EMPTY else fc(listOf(line(seg.poly.slice(0.0, maxOf(1.0, st.progressM)))))
    fun activeRest(seg: Segment?, st: Status?): String =
        if (seg == null || st == null) EMPTY else fc(listOf(line(seg.poly.slice(st.progressM, seg.poly.length))))
    fun activeStart(seg: Segment?): String = if (seg == null) EMPTY else fc(listOf(point(seg.poly.points.first())))
    fun activeEnd(seg: Segment?): String = if (seg == null) EMPTY else fc(listOf(point(seg.poly.points.last())))

    fun position(lat: Double?, lon: Double?): String =
        if (lat == null || lon == null) EMPTY else fc(listOf(point(doubleArrayOf(lat, lon))))

    /** Eigen markeringen die nog geen traject of correctie zijn (open of begin zonder einde). */
    fun marks(marks: List<MarkStore.Mark>): String =
        fc(marks.filter { it.kind == MarkStore.OPEN || (it.kind == MarkStore.BEGIN && it.state != "gekoppeld") }
            .map { point(doubleArrayOf(it.lat, it.lon)) })
}
