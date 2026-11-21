package be.koen.trajectcontrole.ui

import android.os.SystemClock
import android.view.Choreographer

/**
 * Laat de positie vloeiend bewegen tussen twee GPS-punten (die komen ongeveer 1× per seconde).
 * Elk nieuw punt wordt bereikt in ongeveer de tijd tot het vorige punt, zodat de bol gelijkmatig
 * doorschuift i.p.v. te springen. Grote sprongen (> 300 m, bv. eerste fix of tunnel) gebeuren meteen.
 * [onFrame] krijgt per beeld de positie en de (vloeiend bijgestelde) zoom.
 */
class PosAnimator(private val onFrame: (lat: Double, lon: Double, zoom: Double) -> Unit) : Choreographer.FrameCallback {
    private var fromLat = 0.0; private var fromLon = 0.0
    private var toLat = 0.0; private var toLon = 0.0
    var lat = 0.0; private set
    var lon = 0.0; private set
    var zoom = 15.0; private set
    private var targetZoom = 15.0
    private var t0 = 0L; private var dur = 1000L; private var lastFix = 0L
    private var has = false; private var running = false

    val hasPosition get() = has

    fun target(lat: Double, lon: Double, zoom: Double) {
        val now = SystemClock.uptimeMillis()
        targetZoom = zoom
        if (!has || dist(this.lat, this.lon, lat, lon) > 300.0) {
            has = true; this.lat = lat; this.lon = lon; fromLat = lat; fromLon = lon; toLat = lat; toLon = lon
            if (!has || Math.abs(this.zoom - zoom) > 2) this.zoom = zoom
            lastFix = now; onFrame(lat, lon, this.zoom); kick(); return
        }
        if (lat != toLat || lon != toLon) {
            fromLat = this.lat; fromLon = this.lon; toLat = lat; toLon = lon
            dur = (now - lastFix).coerceIn(300L, 1500L); lastFix = now; t0 = now
        }
        kick()
    }

    /** Positie meteen tonen (bv. na wijziging van het kaartgebied). */
    fun redraw() { if (has) onFrame(lat, lon, zoom) }

    fun reset() { has = false; stop() }
    fun stop() { if (running) Choreographer.getInstance().removeFrameCallback(this); running = false }

    private fun kick() { if (!running) { running = true; Choreographer.getInstance().postFrameCallback(this) } }

    override fun doFrame(frameTimeNanos: Long) {
        val f = ((SystemClock.uptimeMillis() - t0).toDouble() / dur).coerceIn(0.0, 1.0)
        lat = fromLat + (toLat - fromLat) * f
        lon = fromLon + (toLon - fromLon) * f
        zoom += (targetZoom - zoom) * 0.06               // zoom glijdt mee in ~1 s
        if (Math.abs(targetZoom - zoom) < 0.005) zoom = targetZoom
        onFrame(lat, lon, zoom)
        if (f < 1.0 || zoom != targetZoom) Choreographer.getInstance().postFrameCallback(this) else running = false
    }

    private fun dist(a: Double, b: Double, c: Double, d: Double): Double {
        val k = 111_320.0
        val dx = (d - b) * k * Math.cos(Math.toRadians(a)); val dy = (c - a) * k
        return Math.sqrt(dx * dx + dy * dy)
    }
}
