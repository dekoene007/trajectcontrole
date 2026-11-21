package be.koen.trajectcontrole.car

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import be.koen.trajectcontrole.StatusBus
import be.koen.trajectcontrole.TrackingService
import be.koen.trajectcontrole.core.Segment
import be.koen.trajectcontrole.ui.DialRenderer
import be.koen.trajectcontrole.ui.MapCamera
import be.koen.trajectcontrole.ui.MapData
import be.koen.trajectcontrole.ui.MapLayers
import be.koen.trajectcontrole.ui.Theme
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Levende kaart in Android Auto: een gewone MapLibre-kaart op een virtueel scherm dat op het oppervlak van
 * Android Auto getekend wordt. Daardoor volgt de kaart je vloeiend (positie centraal, noord boven, hele traject
 * in beeld) en tekenen we het paneel met de meter zelf.
 *
 * Indeling: Android Auto legt linksboven zijn eigen kaartje (titel). Ons paneel komt in de linkerkolom
 * daaronder; de kaart centreert je positie in het vrije deel rechts ervan.
 * Lukt het virtuele scherm niet, dan valt de app terug op de stilstaande kaart (CarMapRenderer).
 */
class CarLiveMap(private val ctx: CarContext) : SurfaceCallback {

    private var vd: VirtualDisplay? = null
    private var pres: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var layers: MapLayers? = null
    private var panel: PanelView? = null
    private var fallback: CarMapRenderer? = null
    private var container: SurfaceContainer? = null
    private var visible = Rect()
    private var segs: Map<Long, Segment> = emptyMap()
    private var segVersion = -1
    private var lastZoom = 15.0
    private var lastCam = 0L

    init { MapLibre.getInstance(ctx) }

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        container = c
        if (visible.isEmpty) visible = Rect(0, 0, c.width, c.height)
        try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val d = dm.createVirtualDisplay("trajectcontrole", c.width, c.height, c.dpi, c.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) ?: throw IllegalStateException("geen virtueel scherm")
            vd = d
            val p = Presentation(ctx, d.display)
            val root = FrameLayout(p.context).apply { setBackgroundColor(Theme.BG) }
            val mv = MapView(p.context)
            root.addView(mv, FrameLayout.LayoutParams(-1, -1))
            val pv = PanelView(p.context)
            root.addView(pv, FrameLayout.LayoutParams(-1, -1))
            root.addView(TextView(p.context).apply {
                text = MapData.ATTRIBUTION; textSize = 9f; setTextColor(Color.argb(140, 163, 173, 187))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 12, 8) })
            p.setContentView(root)
            p.show()
            mv.onCreate(Bundle()); mv.onStart(); mv.onResume()
            mapView = mv; panel = pv; pres = p
            mv.getMapAsync { m ->
                map = m
                m.uiSettings.setAllGesturesEnabled(false)
                m.uiSettings.isCompassEnabled = false; m.uiSettings.isLogoEnabled = false; m.uiSettings.isAttributionEnabled = false
                m.setStyle(Style.Builder().fromUri(MapData.STYLE_DARK)) { st -> layers = MapLayers(st); render(force = true) }
            }
            saveArea("virtueel scherm ${c.width}×${c.height} @${c.dpi}dpi")
        } catch (e: Exception) {
            release()
            saveArea("terugval stilstaande kaart: ${e.message}")
            fallback = CarMapRenderer(ctx).also { it.onSurfaceAvailable(c) }
        }
    }

    override fun onVisibleAreaChanged(r: Rect) {
        visible = Rect(r)
        fallback?.onVisibleAreaChanged(r)
        saveArea(null)
        render(force = true)
    }
    override fun onStableAreaChanged(r: Rect) { fallback?.onStableAreaChanged(r) }

    override fun onSurfaceDestroyed(c: SurfaceContainer) {
        fallback?.onSurfaceDestroyed(c); fallback = null
        release(); container = null
    }

    fun release() {
        try { mapView?.onPause(); mapView?.onStop(); mapView?.onDestroy() } catch (_: Exception) {}
        try { pres?.dismiss() } catch (_: Exception) {}
        vd?.release()
        mapView = null; map = null; layers = null; pres = null; vd = null; panel = null
    }

    /** Voor diagnose: zichtbaar gebied volgens Android Auto, te zien in Instellingen. */
    private fun saveArea(note: String?) {
        val c = container ?: return
        val prefs = ctx.getSharedPreferences("aa", Context.MODE_PRIVATE)
        val old = prefs.getString("info", "") ?: ""
        val head = note ?: old.substringBefore(" | ")
        prefs.edit().putString("info", "$head | scherm ${c.width}×${c.height}, vrij gebied $visible").apply()
    }

    // ---------------- indeling ----------------

    /** Paneel: linkerkolom onder het kaartje van Android Auto (of links in het vrije gebied als er geen kolom is). */
    private fun panelRect(w: Int, h: Int): RectF {
        val m = h * 0.03f
        return if (visible.left > w * 0.18f) {
            RectF(m, h * 0.30f, visible.left - m, h - m)
        } else {
            val pw = visible.width() * 0.34f
            RectF(visible.left + m, visible.top + m, visible.left + pw, visible.bottom - m)
        }
    }

    /** Vrij kaartgebied (zonder paneel) waarin je positie gecentreerd wordt. */
    private fun mapArea(w: Int, h: Int, withPanel: Boolean): RectF {
        val p = panelRect(w, h)
        val left = if (withPanel && visible.left <= w * 0.18f) p.right else visible.left.toFloat()
        return RectF(left, visible.top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat())
    }

    // ---------------- tekenen ----------------

    fun render(force: Boolean = false) {
        fallback?.let { it.render(); return }
        val c = container ?: return
        val s = StatusBus.state
        if (s.dataVersion != segVersion || segs.isEmpty()) {
            segVersion = s.dataVersion
            segs = (TrackingService.current?.engineSegments() ?: TrackingService.loadSegments(ctx)).associateBy { it.id }
        }
        panel?.let { it.state = s; it.rect = panelRect(c.width, c.height); it.invalidate() }
        val m = map ?: return
        layers?.update(ctx, s, segs, force)
        val lat = s.lat ?: return; val lon = s.lon ?: return
        val seg = s.status?.let { segs[it.segmentId] }
        val area = mapArea(c.width, c.height, s.status != null)
        val density = c.dpi / 160f
        val z = if (seg != null) MapCamera.zoomFor(lat, lon, seg.poly.points, area, density) else 15.0
        if (Math.abs(z - lastZoom) > 0.15 || force) lastZoom = z
        val now = SystemClock.elapsedRealtime()
        val ms = if (force || now - lastCam > 3000) 0 else 1000
        lastCam = now
        MapCamera.follow(m, lat, lon, lastZoom, area, c.width, c.height, density, ms)
    }

    /** Ons paneel (meter, tegels, voortgang), getekend boven de kaart. */
    private class PanelView(ctx: Context) : View(ctx) {
        var state: StatusBus.State? = null
        var rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val s = state ?: return
            val st = s.status ?: return
            if (rect.width() < 40 || rect.height() < 80) return
            DialRenderer.drawPanel(canvas, rect, st, s.gpsKmh)
        }
    }
}
