package be.koen.trajectcontrole.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Action
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import be.koen.trajectcontrole.StatusBus
import be.koen.trajectcontrole.TrackingService
import be.koen.trajectcontrole.ui.DialRenderer

/**
 * Ingang voor Android Auto. Hoofdscherm: kaart met paneel. Tweede scherm (instrumentenpaneel, als de auto dat
 * aanbiedt aan navigatie-apps): enkel de meter. Bij het openen in de auto start het volgen automatisch.
 */
class SpeedCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        if (sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER) ClusterSession() else MainSession()

    private class MainSession : Session() {
        init {
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                // Android Auto weg (auto uit, verbinding verbroken): app stopt na de ingestelde wachttijd
                override fun onDestroy(owner: LifecycleOwner) { TrackingService.current?.carDisconnected() }
            })
        }
        override fun onCreateScreen(intent: Intent): Screen {
            autoStart(carContext)
            return CarMapScreen(carContext)
        }
    }

    private class ClusterSession : Session() {
        override fun onCreateScreen(intent: Intent): Screen = ClusterScreen(carContext)
    }

    companion object {
        /** Volgen starten zodra Android Auto de app opent (zolang Android Auto actief is, blijft de app actief). */
        fun autoStart(ctx: CarContext) {
            if (StatusBus.state.running) { TrackingService.current?.carConnected(); return }
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            try { TrackingService.start(ctx, fromCar = true) } catch (_: Exception) { /* Android weigerde: knop ▶ blijft beschikbaar */ }
        }
    }
}

/** Instrumentenpaneel: meter zonder kaart, rechtstreeks op het oppervlak getekend. */
class ClusterScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver, SurfaceCallback {
    private var container: SurfaceContainer? = null
    private var visible = Rect()
    private val listener: (StatusBus.State) -> Unit = { draw() }

    init { lifecycle.addObserver(this) }

    override fun onCreate(owner: LifecycleOwner) { carContext.getCarService(AppManager::class.java).setSurfaceCallback(this) }
    override fun onStart(owner: LifecycleOwner) { StatusBus.listen(listener) }
    override fun onStop(owner: LifecycleOwner) { StatusBus.unlisten(listener) }

    override fun onSurfaceAvailable(c: SurfaceContainer) { container = c; if (visible.isEmpty) visible = Rect(0, 0, c.width, c.height); draw() }
    override fun onVisibleAreaChanged(r: Rect) { visible = Rect(r); draw() }
    override fun onStableAreaChanged(r: Rect) {}
    override fun onSurfaceDestroyed(c: SurfaceContainer) { container = null }

    private fun draw() {
        val c = container ?: return
        val surface = c.surface ?: return
        val canvas = try { surface.lockCanvas(null) } catch (_: Exception) { return } ?: return
        try { DialRenderer.drawCluster(canvas, RectF(visible), StatusBus.state) } finally { surface.unlockCanvasAndPost(canvas) }
    }

    // het instrumentenpaneel aanvaardt enkel het navigatiesjabloon
    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build()).build()
}
