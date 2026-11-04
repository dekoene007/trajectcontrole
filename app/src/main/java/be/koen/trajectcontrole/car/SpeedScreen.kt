package be.koen.trajectcontrole.car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import be.koen.trajectcontrole.R
import be.koen.trajectcontrole.StatusBus
import be.koen.trajectcontrole.TrackingService
import be.koen.trajectcontrole.core.Level
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Scherm in Android Auto: één paneel met drie regels.
 *  - Gemiddeld (gekleurde stip groen/oranje/rood)
 *  - Maximum voor de rest van het traject
 *  - Resterende afstand + limiet
 * Ververst hoogstens om de 2 s (Android Auto beperkt hoe vaak een scherm mag verversen).
 */
class SpeedScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var lastInvalidate = 0L
    private var scheduled = false
    private val listener: (StatusBus.State) -> Unit = { requestRefresh() }
    private val nl = Locale("nl", "BE")

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) { StatusBus.listen(listener) }
    override fun onStop(owner: LifecycleOwner) { StatusBus.unlisten(listener) }

    private fun requestRefresh() {
        val now = SystemClock.elapsedRealtime()
        val wait = 2000 - (now - lastInvalidate)
        if (wait <= 0) { lastInvalidate = now; invalidate() }
        else if (!scheduled) {
            scheduled = true
            handler.postDelayed({ scheduled = false; lastInvalidate = SystemClock.elapsedRealtime(); invalidate() }, wait)
        }
    }

    private fun dot(color: CarColor) = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_dot))
        .setTint(color).build()

    override fun onGetTemplate(): Template {
        val s = StatusBus.state
        val st = s.status
        val pane = Pane.Builder()

        if (!s.running) {
            pane.addRow(Row.Builder().setTitle("Niet actief").addText("Tik op Starten om trajectcontroles te volgen").build())
            pane.addAction(Action.Builder().setTitle("Starten").setOnClickListener { startTracking() }.build())
        } else if (st == null) {
            pane.addRow(Row.Builder().setTitle(if (s.gpsOk) "Geen trajectcontrole" else "Wacht op GPS…")
                .addText("${s.segmentCount} trajecten geladen").build())
            s.lastResult?.let { pane.addRow(Row.Builder().setTitle("Laatste traject").addText(it).build()) }
            pane.addAction(Action.Builder().setTitle("Stoppen").setOnClickListener { TrackingService.stop(carContext) }.build())
        } else {
            val color = when (st.level) { Level.GREEN -> CarColor.GREEN; Level.ORANGE -> CarColor.YELLOW; Level.RED -> CarColor.RED }
            pane.addRow(Row.Builder()
                .setTitle("Gemiddeld ${st.avgKmh.roundToInt()} km/u")
                .addText("limiet ${st.limit} · boete vanaf ${Math.ceil(st.threshold).toInt()}" + if (st.estimated) " · begin geschat" else "")
                .setImage(dot(color), Row.IMAGE_TYPE_ICON)
                .build())
            pane.addRow(Row.Builder()
                .setTitle(if (st.maxRestKmh > 0) "Max. ${st.maxRestKmh.roundToInt()} km/u voor de rest" else "Limiet niet meer haalbaar")
                .addText("nog ${"%.1f".format(nl, st.remainingM / 1000)} km van ${"%.1f".format(nl, st.lengthM / 1000)} km")
                .build())
            pane.addAction(Action.Builder().setTitle("Stoppen").setOnClickListener { TrackingService.stop(carContext) }.build())
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle(st?.name ?: "Trajectcontrole")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun startTracking() {
        if (carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            CarToast.makeText(carContext, "Open de app eerst op je telefoon om locatie toe te staan", CarToast.LENGTH_LONG).show()
            return
        }
        try {
            TrackingService.start(carContext)
        } catch (e: Exception) {
            // Android kan het starten van een locatieservice vanuit de achtergrond weigeren
            CarToast.makeText(carContext, "Starten lukt niet vanuit de auto; start de app op je telefoon", CarToast.LENGTH_LONG).show()
        }
    }
}
