package be.koen.trajectcontrole

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.os.SystemClock
import be.koen.trajectcontrole.core.Corrections
import be.koen.trajectcontrole.core.Fix
import be.koen.trajectcontrole.core.Segment
import be.koen.trajectcontrole.core.SegmentStore
import be.koen.trajectcontrole.core.Suggestion
import be.koen.trajectcontrole.core.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Markeringen die je tijdens het rijden vastlegt (begin, einde, correctie of later beslissen).
 *
 * De positie wordt vastgelegd op het moment van de tik, min de reactietijd. Wat je daarna kiest,
 * verandert alleen het label. Een begin + einde in dezelfde rit wordt een nieuw traject dat exact het
 * gereden spoor volgt (uit het ritlogboek). Een correctie verplaatst het begin of einde van een bestaand
 * traject. Beide werken meteen op dit toestel (files/eigen.tsv en files/eigen_correcties.tsv) en kunnen
 * daarna ingediend worden voor de gedeelde data.
 */
object MarkStore {

    data class Mark(
        val id: Long,                 // wandklok (ms) van de tik
        val t: Double,                // monotone tijd van de markering (s), zelfde klok als het ritlogboek
        val ride: String?,            // bestandsnaam van het ritlogboek
        val lat: Double, val lon: Double,
        val heading: Double?, val kmh: Double,
        var kind: String = OPEN,      // open | begin | einde | correctie
        var limit: Int? = null,
        var pairId: Long? = null,     // einde: id van het bijhorende begin
        var corrSeg: Long? = null, var corrAtStart: Boolean? = null,
        var geom: String? = null,     // resulterende lijn (lat,lon;…)
        var resultId: Long? = null,   // id van het nieuwe of gecorrigeerde traject
        var name: String? = null,
        var state: String = "",       // actief | wacht | fout: …
        var submitted: Boolean = false,
        var both: Boolean = false     // nieuw traject geldt in beide richtingen
    )

    const val OPEN = "open"; const val BEGIN = "begin"; const val EINDE = "einde"; const val CORR = "correctie"
    private const val OWN_ID_BASE = 920_000_000L

    private fun file(ctx: Context) = File(ctx.filesDir, "markeringen.json")
    fun ownSegmentsFile(ctx: Context) = File(ctx.filesDir, "eigen.tsv")
    fun ownCorrectionsFile(ctx: Context) = File(ctx.filesDir, "eigen_correcties.tsv")
    private fun logsDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "logs")

    // ---------------- opslag ----------------

    @Synchronized fun all(ctx: Context): MutableList<Mark> {
        val f = file(ctx); if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    @Synchronized private fun save(ctx: Context, marks: List<Mark>) {
        val a = JSONArray(); marks.forEach { a.put(toJson(it)) }
        val tmp = File(ctx.filesDir, "markeringen.json.tmp"); tmp.writeText(a.toString()); tmp.renameTo(file(ctx))
        writeLocalFiles(ctx, marks)
        StatusBus.update { it.copy(dataVersion = it.dataVersion + 1) }
    }

    private fun toJson(m: Mark) = JSONObject().apply {
        put("id", m.id); put("t", m.t); put("ride", m.ride ?: ""); put("lat", m.lat); put("lon", m.lon)
        m.heading?.let { put("heading", it) }; put("kmh", m.kmh); put("kind", m.kind)
        m.limit?.let { put("limit", it) }; m.pairId?.let { put("pair", it) }
        m.corrSeg?.let { put("corrSeg", it) }; m.corrAtStart?.let { put("corrAtStart", it) }
        m.geom?.let { put("geom", it) }; m.resultId?.let { put("result", it) }; m.name?.let { put("name", it) }
        put("state", m.state); put("submitted", m.submitted); put("both", m.both)
    }

    private fun fromJson(o: JSONObject) = Mark(
        o.getLong("id"), o.getDouble("t"), o.optString("ride").ifEmpty { null }, o.getDouble("lat"), o.getDouble("lon"),
        if (o.has("heading")) o.getDouble("heading") else null, o.optDouble("kmh", 0.0),
        o.optString("kind", OPEN), if (o.has("limit")) o.getInt("limit") else null,
        if (o.has("pair")) o.getLong("pair") else null, if (o.has("corrSeg")) o.getLong("corrSeg") else null,
        if (o.has("corrAtStart")) o.getBoolean("corrAtStart") else null,
        o.optString("geom").ifEmpty { null }, if (o.has("result")) o.getLong("result") else null,
        o.optString("name").ifEmpty { null }, o.optString("state"), o.optBoolean("submitted"), o.optBoolean("both")
    )

    /** Nieuw traject in één of beide richtingen. Na een wijziging opnieuw indienen (zelfde id = vervangen). */
    fun setBoth(ctx: Context, id: Long, both: Boolean) = update(ctx, id) { m, _ -> m.both = both; m.submitted = false }

    // ---------------- vastleggen ----------------

    /** Legt de positie vast op dit moment min de reactietijd. Geeft null als er geen (recente) positie is. */
    fun capture(ctx: Context): Mark? {
        val reaction = Settings(ctx).reactionSec.toDouble()
        val hist = FixHistory.snapshot()
        val nowMono = SystemClock.elapsedRealtimeNanos() / 1e9
        val last = hist.lastOrNull()
        val m: Mark = if (last != null && nowMono - last.t < 5.0) {
            val tMark = minOf(nowMono - reaction, last.t)
            val pos = Track.positionAt(hist, tMark) ?: doubleArrayOf(last.lat, last.lon)
            Mark(System.currentTimeMillis(), tMark, StatusBus.state.rideFile, pos[0], pos[1],
                last.course ?: StatusBus.state.heading, last.speed * 3.6)
        } else {
            // service draait niet: laatste bekende positie (geen spoor beschikbaar)
            val lm = ctx.getSystemService(LocationManager::class.java)
            val loc = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null } ?: return null
            Mark(System.currentTimeMillis(), loc.elapsedRealtimeNanos / 1e9, null, loc.latitude, loc.longitude,
                if (loc.hasBearing()) loc.bearing.toDouble() else null, if (loc.hasSpeed()) loc.speed * 3.6 else 0.0)
        }
        val marks = all(ctx); marks.add(m); save(ctx, marks)
        return m
    }

    /** Correctievoorstel voor een markering (dichtstbijzijnd begin/einde in dezelfde rijrichting). */
    fun suggestionFor(ctx: Context, m: Mark): Suggestion? =
        Corrections.suggest(segments(ctx), m.lat, m.lon, m.heading)

    private fun segments(ctx: Context): Collection<Segment> =
        TrackingService.current?.engineSegments() ?: TrackingService.loadSegments(ctx)

    // ---------------- label kiezen ----------------

    fun setBegin(ctx: Context, id: Long) = update(ctx, id) { m, _ ->
        m.kind = BEGIN; m.state = "wacht op einde"; m.pairId = null; m.geom = null; m.resultId = null
    }

    fun setOpen(ctx: Context, id: Long) = update(ctx, id) { m, _ ->
        m.kind = OPEN; m.state = ""; m.geom = null; m.resultId = null; m.corrSeg = null; m.corrAtStart = null
    }

    /** Einde: koppelt aan het laatste open begin uit dezelfde rit en maakt het traject. */
    fun setEinde(ctx: Context, id: Long, limit: Int) = update(ctx, id) { m, marks ->
        m.kind = EINDE; m.limit = limit
        val begin = marks.filter { it.kind == BEGIN && it.ride != null && it.ride == m.ride && it.t < m.t &&
            marks.none { e -> e.kind == EINDE && e.pairId == it.id && e.id != m.id } }.maxByOrNull { it.t }
        if (begin == null) { m.state = "fout: geen begin in deze rit"; return@update }
        m.pairId = begin.id
        val fixes = rideFixes(ctx, m.ride)
        val pts = Track.simplify(Track.between(fixes, begin.t, m.t))
        val len = Track.length(pts)
        if (pts.size < 2 || len < 100) { m.state = "fout: spoor te kort (${len.toInt()} m)"; return@update }
        m.geom = SegmentStore.formatPoints(pts)
        m.resultId = OWN_ID_BASE + (begin.id / 1000) % 79_000_000L
        m.name = placeName(ctx, pts[pts.size / 2]) ?: "Eigen traject ${fmt(begin.id)}"
        begin.state = "gekoppeld"
        m.state = "actief"
    }

    /** Correctie van begin of einde van een bestaand traject. */
    fun setCorrectie(ctx: Context, id: Long, sug: Suggestion) = update(ctx, id) { m, _ ->
        m.kind = CORR; m.corrSeg = sug.segment.id; m.corrAtStart = sug.atStart; m.resultId = sug.segment.id
        m.name = sug.segment.name
        resolveCorrection(ctx, m)
    }

    fun delete(ctx: Context, id: Long) {
        val marks = all(ctx); marks.removeAll { it.id == id }
        marks.filter { it.pairId == id }.forEach { it.pairId = null; it.state = "fout: begin verwijderd"; it.geom = null }
        save(ctx, marks)
    }

    fun markSubmitted(ctx: Context, ids: Collection<Long>) {
        val marks = all(ctx); marks.filter { it.id in ids }.forEach { it.submitted = true }; save(ctx, marks)
    }

    /** Correcties die nog op spoor wachten opnieuw proberen (bv. na het passeren van het oude begin). */
    fun resolvePending(ctx: Context) {
        val marks = all(ctx)
        val pending = marks.filter { it.kind == CORR && it.state.startsWith("wacht") }
        if (pending.isEmpty()) return
        pending.forEach { resolveCorrection(ctx, it) }
        save(ctx, marks)
    }

    private fun resolveCorrection(ctx: Context, m: Mark) {
        val seg = segments(ctx).firstOrNull { it.id == m.corrSeg }
            ?: baseSegment(ctx, m.corrSeg) ?: run { m.state = "fout: traject niet gevonden"; return }
        val fixes = rideFixes(ctx, m.ride) + FixHistory.snapshot().filter { f -> f.t > (rideFixesLastT ?: 0.0) }
        val pts = Corrections.apply(seg, m.corrAtStart == true, m.lat, m.lon, m.t, fixes)
        if (pts == null) { m.state = "wacht op spoor"; return }
        m.geom = SegmentStore.formatPoints(Track.simplify(pts, 3.0))
        m.state = "actief"
    }

    /** Het traject zoals in de gedeelde data, zonder eigen correcties (voor een tweede correctie op hetzelfde traject). */
    private fun baseSegment(ctx: Context, id: Long?): Segment? =
        if (id == null) null else SegmentStore.parse(SegmentData.openBest(ctx), null).firstOrNull { it.id == id }

    private var rideFixesLastT: Double? = null
    private fun rideFixes(ctx: Context, ride: String?): List<Fix> {
        if (ride == null) return FixHistory.snapshot().also { rideFixesLastT = it.lastOrNull()?.t }
        val f = RideLogReader.read(File(logsDir(ctx), ride))
        rideFixesLastT = f.lastOrNull()?.t
        return f
    }

    private fun update(ctx: Context, id: Long, block: (Mark, MutableList<Mark>) -> Unit) {
        val marks = all(ctx)
        val m = marks.firstOrNull { it.id == id } ?: return
        block(m, marks)
        save(ctx, marks)
        TrackingService.current?.reloadSegments()
    }

    // ---------------- lokale bestanden ----------------

    private fun writeLocalFiles(ctx: Context, marks: List<Mark>) {
        val own = StringBuilder("# eigen trajecten uit markeringen\n")
        val corr = StringBuilder("# eigen correcties uit markeringen (id \\t lijn)\n")
        for (m in marks) {
            val g = m.geom ?: continue
            if (m.state != "actief") continue
            when (m.kind) {
                EINDE -> own.append("${m.resultId}\t${(m.name ?: "Eigen traject").replace('\t', ' ')}\t${m.limit ?: 50}\t${if (m.both) 1 else 0}\t\t$g\n")
                CORR -> corr.append("${m.corrSeg}\t$g\n")
            }
        }
        ownSegmentsFile(ctx).writeText(own.toString())
        ownCorrectionsFile(ctx).writeText(corr.toString())
    }

    // ---------------- hulp ----------------

    fun fmt(wallMs: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale("nl", "BE")).format(Date(wallMs))

    @Suppress("DEPRECATION")
    private fun placeName(ctx: Context, p: DoubleArray): String? = try {
        if (!Geocoder.isPresent()) null else Geocoder(ctx, Locale("nl", "BE")).getFromLocation(p[0], p[1], 1)
            ?.firstOrNull()?.let { a -> listOfNotNull(a.locality, a.thoroughfare).joinToString(", ").ifEmpty { null } }
    } catch (_: Exception) { null }
}
