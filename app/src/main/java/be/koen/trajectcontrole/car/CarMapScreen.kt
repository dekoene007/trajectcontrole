package be.koen.trajectcontrole.car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import be.koen.trajectcontrole.MarkChoice
import be.koen.trajectcontrole.MarkStore
import be.koen.trajectcontrole.R
import be.koen.trajectcontrole.StatusBus
import be.koen.trajectcontrole.TrackingService
import be.koen.trajectcontrole.ui.DialRenderer
import kotlin.math.roundToInt

/**
 * Hoofdscherm in Android Auto (host met Car API 7+): kaart met het hele traject en links het paneel
 * met meter, voortgang, "max. tot einde" en limiet/boetegrens. Rechtsonder een grote markeerzone;
 * daarnaast een "+" in de kaartknoppen van Android Auto als reserve.
 */
class CarMapScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var lastInvalidate = 0L
    private var scheduled = false
    private val renderer = CarLiveMap(ctx)
    private val listener: (StatusBus.State) -> Unit = { renderer.render(); requestRefresh() }

    init { lifecycle.addObserver(this) }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
    }
    override fun onStart(owner: LifecycleOwner) { StatusBus.listen(listener) }
    override fun onStop(owner: LifecycleOwner) { StatusBus.unlisten(listener) }
    override fun onDestroy(owner: LifecycleOwner) { renderer.release() }

    /** Android Auto beperkt hoe vaak het paneel ververst mag worden: hoogstens om de 2 s. De kaart zelf ververst elke fix. */
    private fun requestRefresh() {
        val now = SystemClock.elapsedRealtime()
        val wait = 2000 - (now - lastInvalidate)
        if (wait <= 0) { lastInvalidate = now; invalidate() }
        else if (!scheduled) {
            scheduled = true
            handler.postDelayed({ scheduled = false; lastInvalidate = SystemClock.elapsedRealtime(); invalidate() }, wait)
        }
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val s = StatusBus.state
        val st = s.status
        val pane = Pane.Builder()
        val title: String
        if (!s.running) {
            title = "Trajectcontrole"
            pane.addRow(Row.Builder().setTitle("Niet actief").addText("Tik op Starten om trajectcontroles te volgen").build())
            pane.addAction(Action.Builder().setTitle("Starten").setOnClickListener { startTracking() }.build())
        } else if (st == null) {
            title = if (s.gpsOk) "Geen trajectcontrole" else "Wacht op GPS…"
            pane.addRow(Row.Builder().setTitle("GPS ${s.gpsKmh.roundToInt()} km/u").addText("${s.segmentCount} trajecten geladen").build())
            s.lastResult?.let { pane.addRow(Row.Builder().setTitle("Laatste traject").addText(it).build()) }
        } else {
            // meter, tegels en voortgang tekent de app zelf onder dit kaartje (Android Auto toont hier geen afbeelding)
            title = st.name
            pane.addRow(Row.Builder().setTitle("nog ${DialRenderer.km(st.remainingM)} van ${DialRenderer.km(st.lengthM)}").build())
        }
        val content = PaneTemplate.Builder(pane.build()).setTitle(title).setHeaderAction(Action.APP_ICON).build()

        // één markeerknop (breed, met tekst) in de knoppenbalk van Android Auto; daarnaast Limiet of Starten/Stoppen
        val strip = ActionStrip.Builder()
        strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_plus)).setTitle("Markeer").setOnClickListener { markNow() }.build())
        if (st != null) strip.addAction(Action.Builder().setTitle("Limiet").setOnClickListener {
            screenManager.push(LimitOverrideScreen(carContext))
        }.build())
        else if (s.running) strip.addAction(Action.Builder().setTitle("Stoppen").setOnClickListener { TrackingService.stop(carContext) }.build())
        else strip.addAction(Action.Builder().setTitle("Starten").setOnClickListener { startTracking() }.build())

        return MapWithContentTemplate.Builder()
            .setContentTemplate(content)
            .setActionStrip(strip.build())
            .build()
    }

    private fun markNow() {
        val m = MarkStore.capture(carContext)
        if (m == null) { CarToast.makeText(carContext, "Geen positie beschikbaar", CarToast.LENGTH_SHORT).show(); return }
        CarToast.makeText(carContext, "Positie vastgelegd", CarToast.LENGTH_SHORT).show()
        screenManager.push(MarkChoiceScreen(carContext, m.id))
    }

    private fun startTracking() {
        if (carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            CarToast.makeText(carContext, "Open de app eerst op je telefoon om locatie toe te staan", CarToast.LENGTH_LONG).show()
            return
        }
        try { TrackingService.start(carContext) } catch (e: Exception) {
            CarToast.makeText(carContext, "Starten lukt niet vanuit de auto; start de app op je telefoon", CarToast.LENGTH_LONG).show()
        }
    }
}

/** Keuze na een markering in de auto. De positie staat al vast; dit zet enkel het label. */
class MarkChoiceScreen(ctx: CarContext, private val markId: Long) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val m = MarkStore.all(carContext).firstOrNull { it.id == markId }
        val sug = m?.let { MarkStore.suggestionFor(carContext, it) }
        val items = ItemList.Builder()
        fun row(t: String, sub: String? = null, click: () -> Unit) =
            items.addItem(Row.Builder().setTitle(t).apply { sub?.let { addText(it) } }.setOnClickListener(click).build())
        row("Begin van een traject") { MarkStore.setBegin(carContext, markId); done("Begin opgeslagen") }
        row("Einde van een traject", "Kies daarna de limiet") { screenManager.push(LimitPickScreen(carContext, markId)) }
        if (sug != null) row("Correctie: ${sug.segment.name}", sug.describe()) {
            MarkStore.setCorrectie(carContext, markId, sug); done("Correctie opgeslagen")
        }
        row("Later beslissen") { done("Bewaard bij je markeringen") }
        return ListTemplate.Builder().setSingleList(items.build()).setTitle("Wat heb je gemarkeerd?")
            .setHeaderAction(Action.BACK).build()
    }

    private fun done(t: String) {
        CarToast.makeText(carContext, t, CarToast.LENGTH_SHORT).show()
        screenManager.popToRoot()
    }
}

/** Limiet van een nieuw traject (na "Einde"). */
class LimitPickScreen(ctx: CarContext, private val markId: Long) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        MarkChoice.LIMITS.forEach { lim ->
            items.addItem(Row.Builder().setTitle("$lim km/u").setOnClickListener {
                MarkStore.setEinde(carContext, markId, lim)
                val st = MarkStore.all(carContext).firstOrNull { it.id == markId }?.state ?: ""
                CarToast.makeText(carContext, if (st == "actief") "Nieuw traject actief" else st.removePrefix("fout: "), CarToast.LENGTH_LONG).show()
                screenManager.popToRoot()
            }.build())
        }
        return ListTemplate.Builder().setSingleList(items.build()).setTitle("Limiet van dit traject").setHeaderAction(Action.BACK).build()
    }
}

/** Wisselende limiet voor het traject waarin je rijdt. */
class LimitOverrideScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        items.addItem(Row.Builder().setTitle("Vaste limiet").setOnClickListener { set(null) }.build())
        listOf(50, 70, 90, 100, 120).forEach { lim -> items.addItem(Row.Builder().setTitle("$lim km/u").setOnClickListener { set(lim) }.build()) }
        return ListTemplate.Builder().setSingleList(items.build()).setTitle("Limiet nu").setHeaderAction(Action.BACK).build()
    }
    private fun set(lim: Int?) { TrackingService.current?.setLimit(lim); screenManager.pop() }
}
