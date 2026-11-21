package be.koen.trajectcontrole

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import be.koen.trajectcontrole.core.Segment
import be.koen.trajectcontrole.core.Status
import be.koen.trajectcontrole.ui.DialRenderer
import be.koen.trajectcontrole.ui.DialView
import be.koen.trajectcontrole.ui.MapCamera
import be.koen.trajectcontrole.ui.MapData
import be.koen.trajectcontrole.ui.MapLayers
import be.koen.trajectcontrole.ui.Theme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Gsm-scherm: donkere kaart (noord boven) ingezoomd op het hele traject, bovenaan een paneel met de meter,
 * "max. tot einde", limiet/boetegrens en voortgang, onderaan een grote markeerknop en de knop voor een
 * wisselende limiet. Zelfde ontwerp als in Android Auto.
 */
class MainActivity : Activity() {

    companion object { const val EXTRA_AUTOSTART = "autostart" }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private lateinit var title: TextView
    private lateinit var badge: TextView
    private lateinit var dial: DialView
    private lateinit var maxTile: TextView
    private lateinit var limTile: TextView
    private lateinit var progress: ProgressView
    private lateinit var info: TextView
    private lateinit var activeBox: LinearLayout
    private lateinit var idleBox: LinearLayout
    private lateinit var idleText: TextView
    private lateinit var toggle: Button
    private lateinit var limitBtn: Button
    private lateinit var card: LinearLayout
    private lateinit var bottom: LinearLayout

    private lateinit var attributionView: TextView
    private var segs: Map<Long, Segment> = emptyMap()
    private var drawnVersion = -1
    private var userMovedAt = 0L
    private val listener: (StatusBus.State) -> Unit = { render(it) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).roundToInt()
    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius); stroke?.let { setStroke(dpi(1.5f), it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Theme.BG
        MapLibre.getInstance(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        mapView = MapView(this).also { it.onCreate(savedInstanceState) }
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        root.addView(attribution(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dpi(14f); bottomMargin = dpi(120f) })

        card = buildCard()
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dpi(10f), dpi(10f), dpi(10f), 0) })
        bottom = buildBottom()
        root.addView(bottom, FrameLayout.LayoutParams(-1, dpi(96f), Gravity.BOTTOM).apply { setMargins(dpi(10f), 0, dpi(10f), dpi(16f)) })
        attributionView = root.getChildAt(1) as TextView
        // paneel en knoppen nooit onder de statusbalk, camera-uitsparing of navigatiebalk
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (card.layoutParams as FrameLayout.LayoutParams).topMargin = dpi(10f) + b.top
            (bottom.layoutParams as FrameLayout.LayoutParams).bottomMargin = dpi(16f) + b.bottom
            (attributionView.layoutParams as FrameLayout.LayoutParams).bottomMargin = dpi(120f) + b.bottom
            card.requestLayout(); bottom.requestLayout(); attributionView.requestLayout()
            insets
        }
        setContentView(root)

        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMovedAt = SystemClock.elapsedRealtime()
            }
            m.setStyle(Style.Builder().fromUri(MapData.STYLE_DARK)) { st -> style = st; layers = MapLayers(st); drawnVersion = -1; render(StatusBus.state) }
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(50.95, 4.2), 8.0))
        }

        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true && !StatusBus.state.running) startWithPermissions()
    }

    // ---------------- opbouw ----------------

    private fun text(size: Float, color: Int = Theme.TEXT, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; isAllCaps = false; setTextColor(Theme.TEXT)
        background = rounded(Theme.CARD2, 14f); minHeight = dpi(40f); minimumHeight = dpi(40f)
        setPadding(dpi(12f), 0, dpi(12f), 0)
        setOnClickListener { onClick() }
    }

    private fun buildCard(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(Theme.CARD, 22f)
            setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = text(15f, bold = true).apply { maxLines = 1 }
        badge = text(13f, Theme.MUTED)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(badge)
        c.addView(head)

        // binnen een traject
        activeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dial = DialView(this)
        row.addView(dial, LinearLayout.LayoutParams(dpi(176f), dpi(176f)))
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tile(): TextView = text(14f).apply {
            background = rounded(Theme.CARD2, 16f); setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f))
            gravity = Gravity.CENTER
        }
        maxTile = tile(); limTile = tile()
        tiles.addView(maxTile, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
        tiles.addView(limTile, LinearLayout.LayoutParams(-1, -2))
        row.addView(tiles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(10f) })
        activeBox.addView(row)
        progress = ProgressView()
        activeBox.addView(progress, LinearLayout.LayoutParams(-1, dpi(36f)).apply { topMargin = dpi(6f) })
        info = text(13f, Theme.MUTED)
        activeBox.addView(info)
        c.addView(activeBox)

        // buiten een traject
        idleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        idleText = text(14f, Theme.MUTED).apply { setPadding(0, dpi(6f), 0, dpi(8f)) }
        idleBox.addView(idleText)
        c.addView(idleBox)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(8f), 0, 0) }
        toggle = smallButton("Starten") { if (StatusBus.state.running) TrackingService.stop(this) else startWithPermissions() }
        val lp = { LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dpi(6f) } }
        buttons.addView(toggle, lp())
        buttons.addView(smallButton("Markeringen") { startActivity(Intent(this, MarksActivity::class.java)) }, lp())
        buttons.addView(smallButton("Passages") { startActivity(Intent(this, PassagesActivity::class.java)) }, lp())
        buttons.addView(smallButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) },
            LinearLayout.LayoutParams(dpi(48f), -2))
        c.addView(buttons)
        return c
    }

    private fun buildBottom(): LinearLayout {
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        limitBtn = Button(this).apply {
            text = "Limiet"; textSize = 15f; isAllCaps = false; setTextColor(Theme.TEXT)
            background = rounded(Color.argb(235, 26, 32, 41), 22f, Theme.LINE)
            setOnClickListener { chooseLimitOverride() }
        }
        b.addView(limitBtn, LinearLayout.LayoutParams(dpi(96f), -1).apply { rightMargin = dpi(10f) })
        val mark = Button(this).apply {
            text = "＋  Markeer"; textSize = 20f; isAllCaps = false; setTextColor(Color.argb(210, 232, 237, 243))
            background = rounded(Color.argb(34, 232, 237, 243), 22f, Color.argb(70, 232, 237, 243))
            setOnClickListener { markNow() }
        }
        b.addView(mark, LinearLayout.LayoutParams(0, -1, 1f))
        return b
    }

    private fun attribution() = text(10f, Color.argb(150, 163, 173, 187)).apply { text = MapData.ATTRIBUTION }

    /** Voortgangsbalk (dezelfde tekening als in Android Auto). */
    inner class ProgressView : View(this@MainActivity) {
        var status: Status? = null; set(v) { field = v; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val st = status ?: return
            DialRenderer.drawProgress(canvas, 0f, dp(4f), width.toFloat(), dp(10f), st, dp(13f))
        }
    }

    // ---------------- kaart ----------------

    private var layers: MapLayers? = null
    private var lastZoom = 15.0
    private var lastCam = 0L

    private fun updateMap(s: StatusBus.State) {
        val m = map ?: return
        val l = layers ?: return
        val changed = s.dataVersion != drawnVersion || segs.isEmpty()
        if (changed) {
            drawnVersion = s.dataVersion
            segs = (TrackingService.current?.engineSegments() ?: TrackingService.loadSegments(this)).associateBy { it.id }
        }
        l.update(this, s, segs, changed)
        val lat = s.lat ?: return; val lon = s.lon ?: return
        if (SystemClock.elapsedRealtime() - userMovedAt < 20_000) return   // even niet volgen na zelf verschuiven
        // positie centraal in het vrije deel tussen paneel en knoppen; hele traject in beeld
        val area = android.graphics.RectF(0f, card.bottom.toFloat(), mapView.width.toFloat(), bottom.top.toFloat())
        if (area.height() < 100 || area.width() < 100) return
        val seg = s.status?.let { segs[it.segmentId] }
        val density = resources.displayMetrics.density
        val z = if (seg != null) MapCamera.zoomFor(lat, lon, seg.poly.points, area, density) else 15.0
        if (Math.abs(z - lastZoom) > 0.15) lastZoom = z
        val now = SystemClock.elapsedRealtime()
        MapCamera.follow(m, lat, lon, lastZoom, area, mapView.width, mapView.height, density, if (now - lastCam > 3000) 0 else 1000)
        lastCam = now
    }

    // ---------------- weergave ----------------

    private fun render(s: StatusBus.State) {
        toggle.text = if (s.running) "Stoppen" else "Starten"
        val st = s.status
        val nl = Locale("nl", "BE")
        if (st == null) {
            activeBox.visibility = View.GONE; idleBox.visibility = View.VISIBLE
            title.text = when { !s.running -> "Niet actief"; s.gpsOk -> "Geen trajectcontrole"; else -> "Wacht op GPS…" }
            badge.text = if (s.running && s.gpsOk) "GPS ${s.gpsKmh.roundToInt()} km/u" else ""
            idleText.text = buildString {
                append("${s.segmentCount.takeIf { it > 0 } ?: "—"} trajecten · ${dataAge()}")
                s.lastResult?.let { append("\nLaatste: $it") }
            }
            idleText.append(legend())
            limitBtn.isEnabled = false; limitBtn.alpha = 0.4f
        } else {
            activeBox.visibility = View.VISIBLE; idleBox.visibility = View.GONE
            title.text = st.name + if (st.estimated) " (begin geschat)" else ""
            badge.text = if (st.parallel > 0) "+${st.parallel} overlap" else ""
            dial.status = st
            maxTile.text = tileText("Nu", "${s.gpsKmh.roundToInt()}")
            limTile.text = tileText("Limiet · boete" + if (st.limitOverridden) " (aangepast)" else "",
                "${st.limit} · ${Math.ceil(st.threshold).toInt()}")
            progress.status = st
            info.text = st.nextName?.let { "Aansluitend: $it, ${DialRenderer.km(st.nextLengthM)}" } ?: ""
            limitBtn.isEnabled = true; limitBtn.alpha = 1f
        }
        updateMap(s)
    }

    /** Legenda van de kaart: lijn = traject, groen = begin, wit = einde, grijs = beide richtingen. */
    private fun legend(): CharSequence {
        val sb = android.text.SpannableStringBuilder("\n")
        fun item(sym: String, color: Int, label: String) {
            val a = sb.length; sb.append(sym)
            sb.setSpan(android.text.style.ForegroundColorSpan(color), a, sb.length, 0)
            sb.append(" $label   ")
        }
        item("━", Theme.TRACK, "traject"); item("●", Theme.OK, "begin"); item("●", Theme.TEXT, "einde"); item("●", Theme.TRACK, "beide richtingen")
        return sb
    }

    /** Tegel: klein grijs label met daaronder een groot vet getal, beide gecentreerd. */
    private fun tileText(label: String, value: String): CharSequence {
        val sb = android.text.SpannableStringBuilder(label + "\n" + value)
        val v0 = label.length + 1
        sb.setSpan(android.text.style.ForegroundColorSpan(Theme.MUTED), 0, label.length, 0)
        sb.setSpan(android.text.style.RelativeSizeSpan(1.9f), v0, sb.length, 0)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), v0, sb.length, 0)
        return sb
    }

    private fun dataAge(): String {
        val v = SegmentData.current(this).version.toString()
        return try {
            val d = SimpleDateFormat("yyyyMMddHHmm", Locale.US).parse(v)!!
            val days = ((System.currentTimeMillis() - d.time) / 86_400_000L).toInt()
            "data van ${SimpleDateFormat("d/MM", Locale("nl", "BE")).format(d)}" + if (days > 21) " ⚠ $days dagen oud" else ""
        } catch (_: Exception) { "data onbekend" }
    }

    // ---------------- markeren en limiet ----------------

    private fun markNow() {
        val m = MarkStore.capture(this)
        if (m == null) { Toast.makeText(this, "Geen positie beschikbaar. Start eerst de app.", Toast.LENGTH_LONG).show(); return }
        MarkChoice.show(this, m)
    }

    private fun chooseLimitOverride() {
        val svc = TrackingService.current ?: return
        val st = StatusBus.state.status ?: return
        val opts = listOf(30, 50, 70, 80, 90, 100, 120)
        val labels = (listOf("Vaste limiet") + opts.map { "$it km/u" }).toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Limiet nu op ${st.name}")
            .setItems(labels) { _, i -> svc.setLimit(if (i == 0) null else opts[i - 1]) }
            .show()
    }

    // ---------------- levenscyclus en toestemmingen ----------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false) && !StatusBus.state.running) startWithPermissions()
    }

    override fun onStart() { super.onStart(); mapView.onStart(); StatusBus.listen(listener); SegmentData.maybeUpdate(this) }
    override fun onResume() { super.onResume(); mapView.onResume(); applyFullscreen() }

    private fun applyFullscreen() {
        val c = window.insetsController ?: return
        if (Settings(this).fullscreen) {
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else c.show(WindowInsets.Type.systemBars())
        window.decorView.requestApplyInsets()
    }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { StatusBus.unlisten(listener); mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    private fun startWithPermissions() {
        val need = mutableListOf<String>()
        // Android 12+: FINE enkel samen met COARSE aanvragen, anders negeert het systeem de vraag
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) TrackingService.start(this) else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            TrackingService.start(this)
        else title.text = "Zonder precieze locatie werkt de app niet."
    }
}
