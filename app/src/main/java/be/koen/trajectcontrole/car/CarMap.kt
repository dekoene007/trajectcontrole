package be.koen.trajectcontrole.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import be.koen.trajectcontrole.StatusBus
import be.koen.trajectcontrole.TrackingService
import be.koen.trajectcontrole.core.Segment
import be.koen.trajectcontrole.ui.MapData
import be.koen.trajectcontrole.ui.Theme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Kaart op het scherm van de auto. Noord boven en ingezoomd op het hele traject, binnen het deel van het
 * scherm dat Android Auto vrij laat naast het paneel en buiten de markeerzone.
 *
 * De ondergrond is een stilstaande kaartafbeelding (MapLibre-snapshot, donkere stijl) die enkel opnieuw
 * gemaakt wordt als het traject of het zichtbare gebied verandert. Traject, voortgang en je positie worden
 * er elke seconde zelf overheen getekend. Zuinig en zonder verschuivende kaart tijdens het rijden.
 */
class CarMapRenderer(private val ctx: CarContext, private val onMarkZone: () -> Unit) : SurfaceCallback {

    private var container: SurfaceContainer? = null
    private var visible = Rect()
    private var snapshotter: MapSnapshotter? = null
    private var snap: Bitmap? = null
    private var cam: Cam? = null
    private var camKey: String? = null
    private var lastSnapAt = 0L
    private var segs: Map<Long, Segment> = emptyMap()
    private var segVersion = -1

    /** Web-Mercator-camera: middelpunt in wereldpixels (512-tegels) bij zoom z. */
    private data class Cam(val z: Double, val cx: Double, val cy: Double, val w: Int, val h: Int) {
        private val scale = 512.0 * Math.pow(2.0, z)
        fun x(lon: Double) = (lon + 180.0) / 360.0 * scale
        fun y(lat: Double): Double { val r = Math.toRadians(lat); return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * scale }
        fun sx(lon: Double) = (w / 2.0 + x(lon) - cx).toFloat()
        fun sy(lat: Double) = (h / 2.0 + y(lat) - cy).toFloat()
        fun lon(x: Double) = x / scale * 360.0 - 180.0
        fun lat(y: Double) = Math.toDegrees(atan(Math.sinh(PI * (1 - 2 * y / scale))))
    }

    init { MapLibre.getInstance(ctx) }

    // ---------------- SurfaceCallback ----------------

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        container = c
        if (visible.isEmpty) visible = Rect(0, 0, c.width, c.height)
        camKey = null; render()
    }
    override fun onVisibleAreaChanged(r: Rect) { visible = Rect(r); camKey = null; render() }
    override fun onStableAreaChanged(r: Rect) {}
    override fun onSurfaceDestroyed(c: SurfaceContainer) { container = null; snapshotter?.cancel(); snapshotter = null }
    override fun onClick(x: Float, y: Float) { if (zone().contains(x, y)) onMarkZone() }

    fun release() { snapshotter?.cancel(); snapshotter = null; snap = null }

    // ---------------- geometrie ----------------

    /** Markeerzone: groot vlak rechtsonder in het zichtbare gebied, ±30 % van de kleinste schermmaat. */
    fun zone(): RectF {
        val s = min(visible.width(), visible.height()) * 0.30f
        val m = s * 0.08f
        return RectF(visible.right - s - m, visible.bottom - s - m, visible.right - m, visible.bottom - m)
    }

    private fun fit(pts: List<DoubleArray>, w: Int, h: Int): Cam {
        val z0 = Cam(0.0, 0.0, 0.0, w, h)
        val xs = pts.map { z0.x(it[1]) }; val ys = pts.map { z0.y(it[0]) }
        val bx0 = xs.min(); val bx1 = xs.max(); val by0 = ys.min(); val by1 = ys.max()
        val z = zone(); val pad = min(visible.width(), visible.height()) * 0.08f
        // twee kandidaten: zone als strook rechts of als strook onderaan; de grootste zoom wint
        val cands = listOf(
            RectF(visible.left + pad, visible.top + pad, z.left - pad, visible.bottom - pad),
            RectF(visible.left + pad, visible.top + pad, visible.right - pad, z.top - pad))
        var best: Cam? = null
        for (r in cands) {
            if (r.width() < 50 || r.height() < 50) continue
            val dx = max(bx1 - bx0, 1e-9); val dy = max(by1 - by0, 1e-9)
            val zoom = log2(min(r.width() / dx, r.height() / dy)).coerceIn(5.0, 16.5)
            val sc = Math.pow(2.0, zoom)
            val bcx = (bx0 + bx1) / 2 * sc; val bcy = (by0 + by1) / 2 * sc
            val cam = Cam(zoom, bcx + (w / 2.0 - r.centerX()), bcy + (h / 2.0 - r.centerY()), w, h)
            if (best == null || cam.z > best.z) best = cam
        }
        return best ?: Cam(12.0, z0.x(pts[0][1]) * 4096, z0.y(pts[0][0]) * 4096, w, h)
    }

    private fun around(lat: Double, lon: Double, w: Int, h: Int): Cam {
        val c = Cam(13.0, 0.0, 0.0, w, h)
        return Cam(13.0, c.x(lon) + (w / 2.0 - visible.exactCenterX()), c.y(lat) + (h / 2.0 - visible.exactCenterY()), w, h)
    }

    // ---------------- tekenen ----------------

    fun render() {
        val c = container ?: return
        val surface = c.surface ?: return
        if (c.width <= 0 || c.height <= 0) return
        val s = StatusBus.state
        if (s.dataVersion != segVersion || segs.isEmpty()) {
            segVersion = s.dataVersion
            segs = (TrackingService.current?.engineSegments() ?: emptyList()).associateBy { it.id }
        }
        val st = s.status
        val seg = st?.let { segs[it.segmentId] }

        // camera kiezen
        val key: String
        val want: Cam? = when {
            seg != null -> { key = "seg:${seg.id}:${visible}"; if (camKey == key) cam else fit(seg.poly.points, c.width, c.height) }
            s.lat != null && s.lon != null -> {
                val cur = cam
                val inside = cur != null && camKey?.startsWith("pos") == true &&
                    RectF(visible).apply { inset(width() * 0.2f, height() * 0.2f) }.contains(cur.sx(s.lon), cur.sy(s.lat))
                key = if (inside) camKey!! else "pos:${SystemClock.elapsedRealtime() / 5000}"
                if (inside) cur else around(s.lat, s.lon, c.width, c.height)
            }
            else -> { key = "none"; null }
        }
        if (key != camKey && want != null) {
            cam = want; camKey = key; snap = null
            requestSnapshot(want)
        }

        val canvas = try { surface.lockCanvas(null) } catch (_: Exception) { return } ?: return
        try {
            canvas.drawColor(Theme.BG)
            val k = cam
            snap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            if (k != null) drawOverlay(canvas, k, seg, s)
            drawZone(canvas)
            drawAttribution(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun requestSnapshot(k: Cam) {
        lastSnapAt = SystemClock.elapsedRealtime()
        snapshotter?.cancel()
        val opts = MapSnapshotter.Options(k.w, k.h)
            .withStyleBuilder(Style.Builder().fromUri(MapData.STYLE_DARK))
            .withCameraPosition(CameraPosition.Builder().target(LatLng(k.lat(k.cy), k.lon(k.cx))).zoom(k.z).build())
            .withPixelRatio(1f)
            .withLogo(false)
        val sn = MapSnapshotter(ctx, opts)
        snapshotter = sn
        sn.start(object : MapSnapshotter.SnapshotReadyCallback {
            override fun onSnapshotReady(snapshot: MapSnapshot) {
                if (cam == k) { snap = snapshot.bitmap; render() }
            }
        }, object : MapSnapshotter.ErrorHandler {
            override fun onError(error: String) { /* geen netwerk: enkel eigen tekening */ }
        })
    }

    private fun path(k: Cam, pts: List<DoubleArray>): Path {
        val p = Path()
        pts.forEachIndexed { i, q -> if (i == 0) p.moveTo(k.sx(q[1]), k.sy(q[0])) else p.lineTo(k.sx(q[1]), k.sy(q[0])) }
        return p
    }

    private fun drawOverlay(c: Canvas, k: Cam, seg: Segment?, s: StatusBus.State) {
        val unit = min(visible.width(), visible.height()) / 100f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        // andere trajecten in beeld
        val west = k.lon(k.cx - k.w / 2.0); val east = k.lon(k.cx + k.w / 2.0)
        val north = k.lat(k.cy - k.h / 2.0); val south = k.lat(k.cy + k.h / 2.0)
        line.color = Theme.OTHER; line.strokeWidth = unit * 0.8f; line.alpha = 200
        for (o in segs.values) {
            if (o.id <= 0 || o.id == seg?.id) continue
            if (o.poly.maxLon < west || o.poly.minLon > east || o.poly.maxLat < south || o.poly.minLat > north) continue
            c.drawPath(path(k, o.poly.points), line)
        }
        line.alpha = 255
        val st = s.status
        if (seg != null && st != null) {
            val col = Theme.level(st.level)
            line.color = col; line.strokeWidth = unit * 1.6f; line.alpha = 110
            c.drawPath(path(k, seg.poly.slice(st.progressM, seg.poly.length)), line)
            line.alpha = 255; line.strokeWidth = unit * 1.8f
            c.drawPath(path(k, seg.poly.slice(0.0, maxOf(1.0, st.progressM))), line)
            marker(c, k, seg.poly.points.first(), unit, Theme.OK, start = true)
            marker(c, k, seg.poly.points.last(), unit, Theme.TEXT, start = false)
        }
        if (s.lat != null && s.lon != null) {
            val x = k.sx(s.lon); val y = k.sy(s.lat)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.argb(60, 90, 169, 255); c.drawCircle(x, y, unit * 4.5f, p)
            val arrow = Path().apply {
                moveTo(0f, -unit * 3.2f); lineTo(unit * 2.4f, unit * 2.4f); lineTo(0f, unit * 1.2f); lineTo(-unit * 2.4f, unit * 2.4f); close()
            }
            c.save(); c.translate(x, y); c.rotate((s.heading ?: 0.0).toFloat())
            p.color = Theme.BLUE; c.drawPath(arrow, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = unit * 0.4f; p.color = Theme.BG; c.drawPath(arrow, p)
            c.restore()
        }
    }

    private fun marker(c: Canvas, k: Cam, pt: DoubleArray, unit: Float, color: Int, start: Boolean) {
        val x = k.sx(pt[1]); val y = k.sy(pt[0]); val r = unit * 3.2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Theme.BG; c.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = unit * 0.7f; p.color = color; c.drawCircle(x, y, r, p)
        p.style = Paint.Style.FILL
        if (start) {
            c.drawPath(Path().apply { moveTo(x - r * 0.3f, y - r * 0.45f); lineTo(x + r * 0.5f, y); lineTo(x - r * 0.3f, y + r * 0.45f); close() }, p)
        } else {
            p.strokeWidth = unit * 0.5f
            c.drawLine(x - r * 0.35f, y + r * 0.5f, x - r * 0.35f, y - r * 0.5f, p.apply { style = Paint.Style.STROKE })
            p.style = Paint.Style.FILL
            c.drawPath(Path().apply { moveTo(x - r * 0.3f, y - r * 0.5f); lineTo(x + r * 0.45f, y - r * 0.28f); lineTo(x - r * 0.3f, y - r * 0.05f); close() }, p)
        }
    }

    private fun drawZone(c: Canvas) {
        val z = zone(); val rad = z.width() * 0.13f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(26, 232, 237, 243); c.drawRoundRect(z, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = z.width() * 0.01f; p.color = Color.argb(60, 232, 237, 243)
        c.drawRoundRect(z, rad, rad, p)
        p.color = Color.argb(190, 232, 237, 243); p.strokeWidth = z.width() * 0.035f; p.strokeCap = Paint.Cap.ROUND
        val cx = z.centerX(); val cy = z.centerY() - z.height() * 0.06f; val a = z.width() * 0.13f
        c.drawLine(cx - a, cy, cx + a, cy, p); c.drawLine(cx, cy - a, cx, cy + a, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 232, 237, 243); textSize = z.width() * 0.09f; textAlign = Paint.Align.CENTER }
        c.drawText("Markeer", cx, z.bottom - z.height() * 0.14f, t)
    }

    private fun drawAttribution(c: Canvas) {
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 163, 173, 187); textSize = min(visible.width(), visible.height()) * 0.025f }
        c.drawText(MapData.ATTRIBUTION, visible.left + t.textSize, visible.bottom - t.textSize, t)
    }
}
