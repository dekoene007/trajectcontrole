package be.koen.trajectcontrole

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import be.koen.trajectcontrole.core.Level
import java.util.Locale
import kotlin.math.roundToInt

/** Telefoonscherm: grote cijfers, start/stop. Bewust zonder extra bibliotheken. */
class MainActivity : Activity() {

    companion object { const val EXTRA_AUTOSTART = "autostart" }

    private lateinit var title: TextView
    private lateinit var avg: TextView
    private lateinit var detail: TextView
    private lateinit var last: TextView
    private lateinit var toggle: Button
    private val listener: (StatusBus.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48); setBackgroundColor(Color.BLACK)
        }
        fun tv(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            root.addView(this)
        }
        title = tv(20f)
        avg = tv(88f, bold = true)
        detail = tv(22f)
        last = tv(16f).apply { setTextColor(Color.LTGRAY); setPadding(0, 48, 0, 48) }
        toggle = Button(this).apply {
            textSize = 20f
            setOnClickListener {
                if (StatusBus.state.running) TrackingService.stop(this@MainActivity) else startWithPermissions()
            }
        }
        root.addView(toggle)
        root.addView(Button(this).apply {
            text = "Instellingen"; textSize = 16f
            setOnClickListener { startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        setContentView(root)
        // geopend via de melding 'Tik om te starten' (automatisch starten werd door Android geweigerd)
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true && !StatusBus.state.running) startWithPermissions()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false) && !StatusBus.state.running) startWithPermissions()
    }

    override fun onStart() { super.onStart(); StatusBus.listen(listener); SegmentData.maybeUpdate(this) }
    override fun onStop() { StatusBus.unlisten(listener); super.onStop() }

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

    private fun render(s: StatusBus.State) {
        toggle.text = if (s.running) "Stoppen" else "Starten"
        last.text = s.lastResult?.let { "Laatste traject: $it" } ?: ""
        val st = s.status
        if (!s.running) {
            title.text = "${s.segmentCount.takeIf { it > 0 }?.let { "$it trajecten geladen · " } ?: ""}niet actief"
            avg.text = "—"; avg.setTextColor(Color.GRAY); detail.text = ""
            return
        }
        if (st == null) {
            title.text = if (s.gpsOk) "Geen trajectcontrole" else "Wacht op GPS…"
            avg.text = "—"; avg.setTextColor(Color.GRAY)
            detail.text = "${s.segmentCount} trajecten geladen"
            return
        }
        title.text = st.name + if (st.estimated) " (begin geschat)" else ""
        avg.text = st.avgKmh.roundToInt().toString()
        avg.setTextColor(when (st.level) { Level.GREEN -> Color.rgb(60, 200, 90); Level.ORANGE -> Color.rgb(255, 170, 0); Level.RED -> Color.rgb(230, 50, 50) })
        val nl = Locale("nl", "BE")
        detail.text = "limiet ${st.limit} · boete vanaf ${"%.0f".format(nl, Math.ceil(st.threshold))}\n" +
            "max. rest ${st.maxRestKmh.roundToInt()} · nog ${"%.1f".format(nl, st.remainingM / 1000)} km"
        detail.visibility = View.VISIBLE
    }
}
