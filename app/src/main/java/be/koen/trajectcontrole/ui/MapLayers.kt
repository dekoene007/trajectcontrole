package be.koen.trajectcontrole.ui

import android.graphics.RectF
import be.koen.trajectcontrole.MarkStore
import be.koen.trajectcontrole.StatusBus
import be.koen.trajectcontrole.core.Segment
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Kaartlagen en -inhoud, gedeeld door gsm en Android Auto. */
class MapLayers(private val style: Style) {
    private var drawnActive: Long? = Long.MIN_VALUE
    private var drawnVersion = -1

    init {
        fun src(id: String) = style.addSource(GeoJsonSource(id, MapData.EMPTY))
        listOf("others", "starts", "ends", "both", "done", "rest", "aStart", "aEnd", "marks", "pos").forEach { src(it) }
        style.addLayer(LineLayer("others", "others").withProperties(
            PropertyFactory.lineColor(Theme.hex(Theme.TRACK)), PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineOpacity(0.9f)))
        fun dots(id: String, fill: Int) = style.addLayer(CircleLayer(id, id).withProperties(
            PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(Theme.hex(fill)),
            PropertyFactory.circleStrokeColor(Theme.hex(Theme.BG)), PropertyFactory.circleStrokeWidth(2f)).apply { minZoom = 10f })
        dots("starts", Theme.OK); dots("ends", Theme.TEXT); dots("both", Theme.TRACK)
        style.addLayer(LineLayer("rest", "rest").withProperties(
            PropertyFactory.lineColor(Theme.hex(Theme.OK)), PropertyFactory.lineWidth(7f), PropertyFactory.lineOpacity(0.4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
        style.addLayer(LineLayer("done", "done").withProperties(
            PropertyFactory.lineColor(Theme.hex(Theme.OK)), PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
        style.addLayer(CircleLayer("aStart", "aStart").withProperties(
            PropertyFactory.circleRadius(11f), PropertyFactory.circleColor(Theme.hex(Theme.BG)),
            PropertyFactory.circleStrokeColor(Theme.hex(Theme.OK)), PropertyFactory.circleStrokeWidth(4f)))
        style.addLayer(CircleLayer("aEnd", "aEnd").withProperties(
            PropertyFactory.circleRadius(11f), PropertyFactory.circleColor(Theme.hex(Theme.TEXT)),
            PropertyFactory.circleStrokeColor(Theme.hex(Theme.BG)), PropertyFactory.circleStrokeWidth(4f)))
        style.addLayer(CircleLayer("marks", "marks").withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(Theme.hex(Theme.BG)),
            PropertyFactory.circleStrokeColor(Theme.hex(Theme.BLUE)), PropertyFactory.circleStrokeWidth(3f)))
        style.addLayer(CircleLayer("posHalo", "pos").withProperties(
            PropertyFactory.circleRadius(18f), PropertyFactory.circleColor(Theme.hex(Theme.BLUE)), PropertyFactory.circleOpacity(0.25f)))
        style.addLayer(CircleLayer("pos", "pos").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(Theme.hex(Theme.BLUE)),
            PropertyFactory.circleStrokeColor(Theme.hex(Theme.BG)), PropertyFactory.circleStrokeWidth(3f)))
    }

    private fun set(id: String, json: String) { style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json) }

    fun update(ctx: android.content.Context, s: StatusBus.State, segs: Map<Long, Segment>, versionChanged: Boolean) {
        val st = s.status
        val seg = st?.let { segs[it.segmentId] }
        if (versionChanged || s.dataVersion != drawnVersion) {
            drawnVersion = s.dataVersion; drawnActive = Long.MIN_VALUE
            set("marks", MapData.marks(MarkStore.all(ctx)))
        }
        if (st?.segmentId != drawnActive) {
            set("others", MapData.others(segs.values, st?.segmentId))
            val (sv, ev, bv) = MapData.endpoints(segs.values, st?.segmentId)
            set("starts", sv); set("ends", ev); set("both", bv)
            set("aStart", MapData.activeStart(seg)); set("aEnd", MapData.activeEnd(seg))
            drawnActive = st?.segmentId
        }
        // kleur van het actieve traject = niveau (groen/oranje/rood)
        val col = Theme.hex(Theme.level(st?.level))
        style.getLayerAs<LineLayer>("done")?.setProperties(PropertyFactory.lineColor(col))
        style.getLayerAs<LineLayer>("rest")?.setProperties(PropertyFactory.lineColor(col))
        set("done", MapData.activeDone(seg, st)); set("rest", MapData.activeRest(seg, st))
        if (s.lat == null || s.lon == null) set("pos", MapData.position(null, null))
    }

    /** Positiebol apart, zodat hij per beeld vloeiend kan bewegen. */
    fun setPos(lat: Double, lon: Double) = set("pos", MapData.position(lat, lon))
}

/**
 * Volgcamera: noord boven, je positie in het midden van het vrije kaartgebied [area] (pixels van de kaart),
 * zo ver uitgezoomd dat het hele traject rond je past. Vloeiend: lineaire overgang over [ms].
 */
object MapCamera {
    private const val TILE = 512.0
    private fun wx(lon: Double, z: Double) = (lon + 180.0) / 360.0 * TILE * Math.pow(2.0, z)
    private fun wy(lat: Double, z: Double): Double {
        val r = Math.toRadians(lat); return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * TILE * Math.pow(2.0, z)
    }
    private fun lon(x: Double, z: Double) = x / (TILE * Math.pow(2.0, z)) * 360.0 - 180.0
    private fun lat(y: Double, z: Double) = Math.toDegrees(atan(Math.sinh(PI * (1 - 2 * y / (TILE * Math.pow(2.0, z))))))

    /** Zoom waarbij alle punten in [area] passen met de auto in het midden. density = px per dp van de kaart. */
    fun zoomFor(lat: Double, lon: Double, pts: List<DoubleArray>, area: RectF, density: Float): Double {
        if (pts.isEmpty()) return 15.0
        val cx = wx(lon, 0.0); val cy = wy(lat, 0.0)
        var dx = 1e-9; var dy = 1e-9
        for (p in pts) { dx = max(dx, Math.abs(wx(p[1], 0.0) - cx)); dy = max(dy, Math.abs(wy(p[0], 0.0) - cy)) }
        // MapLibre rekent in dp: gebied in dp, 12 % marge
        val hw = area.width() / density / 2 * 0.88; val hh = area.height() / density / 2 * 0.88
        return log2(min(hw / dx, hh / dy)).coerceIn(10.5, 16.5)
    }

    fun follow(map: MapLibreMap, lat: Double, lon: Double, zoom: Double, area: RectF, viewW: Int, viewH: Int, density: Float, ms: Int) {
        // doel zo kiezen dat de auto in het midden van 'area' staat i.p.v. in het midden van de kaart
        val offX = (viewW / 2.0 - area.centerX()) / density
        val offY = (viewH / 2.0 - area.centerY()) / density
        val tLon = lon(wx(lon, zoom) + offX, zoom); val tLat = lat(wy(lat, zoom) + offY, zoom)
        val cp = CameraPosition.Builder().target(LatLng(tLat, tLon)).zoom(zoom).bearing(0.0).tilt(0.0).build()
        if (ms <= 0) map.moveCamera(CameraUpdateFactory.newCameraPosition(cp))
        else map.easeCamera(CameraUpdateFactory.newCameraPosition(cp), ms, false)
    }
}
